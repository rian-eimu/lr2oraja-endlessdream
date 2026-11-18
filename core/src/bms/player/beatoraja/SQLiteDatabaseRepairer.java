package bms.player.beatoraja;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

import org.apache.commons.dbutils.QueryRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import com.badlogic.gdx.Gdx;

import bms.player.beatoraja.SQLiteDatabaseAccessor.Column;
import bms.player.beatoraja.SQLiteDatabaseAccessor.Table;
import bms.player.beatoraja.modmenu.ImGuiNotify;

/**
 * SQLiteデータベースファイルの整合性チェックおよび破損時の多段階自動修復を行うユーティリティクラス
 */
public final class SQLiteDatabaseRepairer {

	private static final Logger logger = LoggerFactory.getLogger(SQLiteDatabaseRepairer.class);

	/**
	 * SQLiteの基本エラーコード: SQLITE_CORRUPT (11)
	 */
	private static final int SQLITE_CORRUPT = 11;
	/**
	 * SQLiteの基本エラーコード: SQLITE_NOTADB (26)
	 */
	private static final int SQLITE_NOTADB = 26;

	private static final int BATCH_COMMIT_SIZE = 2000;
	private static final int MAX_BISECTION_PROBES = 64;
	private static final long CLI_TIMEOUT_SECONDS = 60L;
	private static final int NOTIFY_DISMISS_MS = 10000;

	private static final DateTimeFormatter BACKUP_TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

	/**
	 * ゲームウィンドウ（ImGui）初期化前に発生した修復通知の待機キュー
	 */
	private static final Queue<PendingNotification> pendingNotifications = new ConcurrentLinkedQueue<>();

	/**
	 * 外部 sqlite3 CLI の利用可否キャッシュ（テストから差し替え可能）
	 */
	private static volatile String sqliteCliExecutable;
	private static volatile boolean sqliteCliChecked;

	/**
	 * 起動時の整合性チェック種別
	 */
	public enum IntegrityCheckMode {
		/**
		 * PRAGMA integrity_check(1) による完全チェック（小規模なスコア・評価系DB向け）
		 */
		FULL("PRAGMA integrity_check(1)"),
		/**
		 * PRAGMA quick_check(1) による高速チェック（大容量の楽曲・楽曲情報DB向け）
		 */
		QUICK("PRAGMA quick_check(1)");

		public final String pragmaSql;

		IntegrityCheckMode(String pragmaSql) {
			this.pragmaSql = pragmaSql;
		}
	}

	/**
	 * 修復手段の種別
	 */
	public enum RepairMethod {
		NONE,
		REINDEX,
		CLI_RECOVER,
		CLI_DUMP,
		JAVA_SALVAGE,
		REINITIALIZED
	}

	/**
	 * 修復処理の結果情報
	 */
	public record RepairResult(
			boolean repaired,
			RepairMethod method,
			long salvagedRows,
			Path backupPath,
			String reason) {
	}

	private record PendingNotification(ImGuiNotify.ToastType type, String message) {
	}

	private SQLiteDatabaseRepairer() {
	}

	/**
	 * 例外チェーンを辿り、SQLiteデータベース破損（SQLITE_CORRUPT / SQLITE_NOTADB）に起因する例外かを判定する
	 */
	public static boolean isCorruptionException(Throwable throwable) {
		Throwable current = throwable;
		int depth = 0;
		while (current != null && depth++ < 16) {
			if (current instanceof SQLException sqlEx) {
				final int primaryCode = sqlEx.getErrorCode() & 0xFF;
				if (primaryCode == SQLITE_CORRUPT || primaryCode == SQLITE_NOTADB) {
					return true;
				}
				final String msg = sqlEx.getMessage();
				if (msg != null) {
					final String lower = msg.toLowerCase(Locale.ROOT);
					if (lower.contains("database disk image is malformed")
							|| lower.contains("file is not a database")
							|| lower.contains("malformed database schema")) {
						return true;
					}
				}
			}
			current = current.getCause();
		}
		return false;
	}

	/**
	 * 起動時にデータベースの整合性を検査し、破損が検出された場合はバックアップ退避のうえ自動修復を実行する
	 */
	public static RepairResult checkAndRepairOnStartup(
			Path dbPath,
			IntegrityCheckMode mode,
			SQLiteDatabaseAccessor accessor) {
		if (dbPath == null || !Files.exists(dbPath)) {
			return new RepairResult(false, RepairMethod.NONE, 0, null, null);
		}
		try {
			if (Files.size(dbPath) == 0L) {
				return new RepairResult(false, RepairMethod.NONE, 0, null, null);
			}
		} catch (IOException e) {
			logger.warn("データベースファイルサイズの確認に失敗しました ({}): {}", dbPath, e.getMessage());
			return new RepairResult(false, RepairMethod.NONE, 0, null, null);
		}

		final String issue = diagnoseDatabase(dbPath, mode);
		if (issue == null) {
			return new RepairResult(false, RepairMethod.NONE, 0, null, null);
		}
		return repairDatabase(dbPath, accessor, issue);
	}

	/**
	 * 指定のPRAGMAチェックを実行し、正常であれば null、異常があればその詳細文字列を返す
	 */
	public static String diagnoseDatabase(Path dbPath, IntegrityCheckMode mode) {
		final SQLiteDataSource probeDs = createIsolatedDataSource(dbPath, true);
		try (Connection conn = probeDs.getConnection();
				Statement stmt = conn.createStatement();
				ResultSet rs = stmt.executeQuery(mode.pragmaSql)) {
			if (rs.next()) {
				final String status = rs.getString(1);
				if ("ok".equalsIgnoreCase(status) && !rs.next()) {
					return null;
				}
				return status != null ? status : "integrity check returned non-ok";
			}
			return "integrity check returned empty result";
		} catch (SQLException e) {
			if (isCorruptionException(e)) {
				return e.getMessage();
			}
			logger.warn("データベース整合性チェック中に破損以外のSQL例外が発生しました ({}): {}", dbPath, e.getMessage());
			return null;
		}
	}

	/**
	 * 破損したデータベースファイルをバックアップし、多段階パイプラインで自動修復する。
	 * 呼び出し元はこのデータベースへの全JDBCコネクションを閉じた状態で呼び出すこと。
	 */
	public static synchronized RepairResult repairDatabase(
			Path dbPath,
			SQLiteDatabaseAccessor accessor,
			String detectedReason) {
		final Path absPath = dbPath.toAbsolutePath().normalize();
		final String dbName = absPath.getFileName().toString();
		logger.warn("データベース破損を検出しました ({}): {} - 自動修復を開始します", dbName, detectedReason);

		if (!Files.exists(absPath)) {
			return new RepairResult(false, RepairMethod.NONE, 0, null, detectedReason);
		}

		final Path backupPath;
		try {
			backupPath = createCorruptBackup(absPath);
			logger.warn("破損した元データベースをバックアップ退避しました: {}", backupPath);
		} catch (IOException e) {
			logger.error("破損データベースのバックアップ作成に失敗したため、自動修復を中止しました ({}): {}", absPath, e.getMessage());
			enqueueNotification(ImGuiNotify.ToastType.Error,
					String.format("DB破損のバックアップ作成に失敗しました: %s", dbName));
			return new RepairResult(false, RepairMethod.NONE, 0, null, detectedReason);
		}

		final Path parentDir = absPath.getParent() != null ? absPath.getParent() : Paths.get(".");
		final String tempPrefix = dbName + ".repair-" + System.nanoTime();
		final Path workDbPath = parentDir.resolve(tempPrefix + ".tmp");
		final Path workSqlPath = parentDir.resolve(tempPrefix + ".sql.tmp");

		try {
			final boolean isNotADb = detectedReason != null
					&& detectedReason.toLowerCase(Locale.ROOT).contains("file is not a database");

			// Stage 1: インデックス破損のみの場合は REINDEX で無損失修復を試行
			if (!isNotADb && tryReindexRepair(backupPath, workDbPath, accessor)) {
				replaceDatabaseFile(workDbPath, absPath);
				final long rows = countTotalRows(absPath, accessor);
				logger.info("データベースを REINDEX により無損失で自動修復しました ({}, 合計 {} 件)", dbName, rows);
				enqueueNotification(ImGuiNotify.ToastType.Info,
						String.format("破損したDBを自動修復しました (%s / REINDEX)", dbName));
				return new RepairResult(true, RepairMethod.REINDEX, rows, backupPath, detectedReason);
			}

			// Stage 2: OS環境に sqlite3 CLI が存在する場合は .recover を試行
			final String cli = resolveSqliteCli();
			if (!isNotADb && cli != null) {
				deleteTempDbAndSidecars(workDbPath);
				Files.deleteIfExists(workSqlPath);
				if (tryCliRecover(cli, backupPath, workSqlPath, workDbPath, accessor)) {
					final long rows = countTotalRows(workDbPath, accessor);
					if (rows > 0) {
						replaceDatabaseFile(workDbPath, absPath);
						logger.info("データベースを sqlite3 .recover により自動修復しました ({}, 救出レコード数: {})", dbName, rows);
						enqueueNotification(ImGuiNotify.ToastType.Warning,
								String.format("破損したDBを自動修復しました (%s / %d件復元)", dbName, rows));
						return new RepairResult(true, RepairMethod.CLI_RECOVER, rows, backupPath, detectedReason);
					}
				}
			}

			// Stage 3: Pure Java スキーマ再構築 + _rowid_ 双方向スキャン＆二分探索によるレコード救出
			deleteTempDbAndSidecars(workDbPath);
			long javaSalvagedRows = -1L;
			if (!isNotADb) {
				javaSalvagedRows = tryJavaRowSalvage(backupPath, workDbPath, accessor);
			}

			// sqlite3 CLI が存在し、かつ Pure Java 救出が失敗または 0 件だった場合は .dump (ROLLBACK補正付き) も比較試行
			if (!isNotADb && cli != null && javaSalvagedRows <= 0) {
				final Path dumpDbPath = parentDir.resolve(tempPrefix + ".dump.tmp");
				try {
					Files.deleteIfExists(workSqlPath);
					if (tryCliDump(cli, backupPath, workSqlPath, dumpDbPath, accessor)) {
						final long dumpRows = countTotalRows(dumpDbPath, accessor);
						if (javaSalvagedRows < 0 || dumpRows > javaSalvagedRows) {
							replaceDatabaseFile(dumpDbPath, absPath);
							logger.info("データベースを sqlite3 .dump により自動修復しました ({}, 救出レコード数: {})", dbName, dumpRows);
							enqueueNotification(ImGuiNotify.ToastType.Warning,
									String.format("破損したDBを自動修復しました (%s / %d件復元)", dbName, dumpRows));
							return new RepairResult(true, RepairMethod.CLI_DUMP, dumpRows, backupPath, detectedReason);
						}
					}
				} finally {
					deleteTempDbAndSidecars(dumpDbPath);
				}
			}

			if (javaSalvagedRows > 0 && verifyDatabaseHealthy(workDbPath, accessor)) {
				replaceDatabaseFile(workDbPath, absPath);
				logger.info("データベースをレコード救出により自動修復しました ({}, 救出レコード数: {}, バックアップ: {})",
						dbName, javaSalvagedRows, backupPath.getFileName());
				enqueueNotification(ImGuiNotify.ToastType.Warning,
						String.format("破損したDBを自動修復しました (%s / %d件復元, 元ファイル: %s)",
								dbName, javaSalvagedRows, backupPath.getFileName()));
				return new RepairResult(true, RepairMethod.JAVA_SALVAGE, javaSalvagedRows, backupPath, detectedReason);
			}

			// Stage 4: ヘッダ全損等でレコード抽出不能な場合でも正常な空スキーマDBを再構築して起動不能を防止
			deleteTempDbAndSidecars(workDbPath);
			if (initializeCleanDatabase(workDbPath, accessor) && verifyDatabaseHealthy(workDbPath, accessor)) {
				replaceDatabaseFile(workDbPath, absPath);
				logger.warn("破損が深刻なため空のデータベースを再作成しました ({}, 元ファイル退避先: {})",
						dbName, backupPath);
				enqueueNotification(ImGuiNotify.ToastType.Warning,
						String.format("破損したDBを再初期化しました (%s, 退避先: %s)",
								dbName, backupPath.getFileName()));
				return new RepairResult(true, RepairMethod.REINITIALIZED, 0, backupPath, detectedReason);
			}
		} catch (Exception e) {
			logger.error("データベース自動修復中に予期しない例外が発生しました ({}): {}", absPath, e.getMessage(), e);
		} finally {
			deleteTempDbAndSidecars(workDbPath);
			try {
				Files.deleteIfExists(workSqlPath);
			} catch (IOException ignored) {
			}
		}

		enqueueNotification(ImGuiNotify.ToastType.Error,
				String.format("破損したDBの自動修復に失敗しました: %s", dbName));
		return new RepairResult(false, RepairMethod.NONE, 0, backupPath, detectedReason);
	}

	/**
	 * 待機中のDB修復トースト通知を ImGuiNotify へ送出する（MainController 初期化完了時に呼び出す）
	 */
	public static void flushPendingNotifications() {
		PendingNotification item;
		while ((item = pendingNotifications.poll()) != null) {
			ImGuiNotify.insertNotification(new ImGuiNotify.Toast(item.type(), NOTIFY_DISMISS_MS, item.message()));
		}
	}

	private static void enqueueNotification(ImGuiNotify.ToastType type, String message) {
		if (Gdx.app != null) {
			Gdx.app.postRunnable(() -> ImGuiNotify.insertNotification(
					new ImGuiNotify.Toast(type, NOTIFY_DISMISS_MS, message)));
		} else {
			pendingNotifications.offer(new PendingNotification(type, message));
		}
	}

	/**
	 * 破損ファイルを <ファイル名>.corrupt-<日時>.bak にコピー退避する（付属ジャーナルも存在する場合は退避）
	 */
	static Path createCorruptBackup(Path dbPath) throws IOException {
		final Path parent = dbPath.getParent() != null ? dbPath.getParent() : Paths.get(".");
		final String baseName = dbPath.getFileName().toString();
		final String timestamp = LocalDateTime.now().format(BACKUP_TIMESTAMP_FORMAT);

		Path candidate = parent.resolve(baseName + ".corrupt-" + timestamp + ".bak");
		int suffix = 1;
		while (Files.exists(candidate)) {
			candidate = parent.resolve(baseName + ".corrupt-" + timestamp + "-" + suffix + ".bak");
			suffix++;
		}
		Files.copy(dbPath, candidate, StandardCopyOption.COPY_ATTRIBUTES);

		for (String sidecarExt : new String[] { "-journal", "-wal" }) {
			final Path sidecar = parent.resolve(baseName + sidecarExt);
			if (Files.exists(sidecar)) {
				final Path sidecarBackup = parent.resolve(candidate.getFileName().toString() + sidecarExt);
				Files.copy(sidecar, sidecarBackup, StandardCopyOption.REPLACE_EXISTING);
			}
		}
		return candidate;
	}

	/**
	 * Stage 1: 作業コピー上で REINDEX を実行し、完全な integrity_check に合格するかを検証する
	 */
	private static boolean tryReindexRepair(Path backupPath, Path workDbPath, SQLiteDatabaseAccessor accessor) {
		try {
			Files.copy(backupPath, workDbPath, StandardCopyOption.REPLACE_EXISTING);
			final SQLiteDataSource workDs = createIsolatedDataSource(workDbPath, false);
			try (Connection conn = workDs.getConnection();
					Statement stmt = conn.createStatement()) {
				stmt.execute("REINDEX;");
			}
			final QueryRunner workQr = new QueryRunner(workDs);
			accessor.validate(workQr);
			return verifyDatabaseHealthy(workDbPath, accessor);
		} catch (Exception e) {
			logger.debug("REINDEX による修復では解決できませんでした ({}): {}", workDbPath.getFileName(), e.getMessage());
			return false;
		}
	}

	/**
	 * Stage 2a: 外部 sqlite3 CLI の .recover コマンドを用いて修復を試みる
	 */
	static boolean tryCliRecover(
			String sqliteCli,
			Path backupPath,
			Path workSqlPath,
			Path workDbPath,
			SQLiteDatabaseAccessor accessor) {
		try {
			final ProcessBuilder pb = new ProcessBuilder(
					sqliteCli,
					backupPath.getFileName().toString(),
					".recover");
			if (backupPath.getParent() != null) {
				pb.directory(backupPath.getParent().toFile());
			}
			pb.redirectOutput(workSqlPath.toFile());
			final Process proc = pb.start();
			drainStderrAsync(proc);
			if (!proc.waitFor(CLI_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
				proc.destroyForcibly();
				return false;
			}
			if (proc.exitValue() != 0 || !Files.exists(workSqlPath) || Files.size(workSqlPath) == 0L) {
				return false;
			}
			if (!importSqlFileWithCli(sqliteCli, workSqlPath, workDbPath)) {
				return false;
			}
			// .recover が生成し得る一時テーブル lost_and_found を除去し、本来必要なスキーマを補完
			final SQLiteDataSource workDs = createIsolatedDataSource(workDbPath, false);
			try (Connection conn = workDs.getConnection();
					Statement stmt = conn.createStatement()) {
				stmt.execute("DROP TABLE IF EXISTS lost_and_found;");
			}
			accessor.validate(new QueryRunner(workDs));
			return verifyDatabaseHealthy(workDbPath, accessor);
		} catch (Exception e) {
			logger.debug("sqlite3 .recover による修復に失敗しました: {}", e.getMessage());
			return false;
		}
	}

	/**
	 * Stage 2b: 外部 sqlite3 CLI の .dump コマンドを用いて修復を試みる（エラー時の末尾 ROLLBACK; を COMMIT; に補正）
	 */
	static boolean tryCliDump(
			String sqliteCli,
			Path backupPath,
			Path workSqlPath,
			Path workDbPath,
			SQLiteDatabaseAccessor accessor) {
		try {
			final ProcessBuilder pb = new ProcessBuilder(
					sqliteCli,
					backupPath.getFileName().toString(),
					".dump");
			if (backupPath.getParent() != null) {
				pb.directory(backupPath.getParent().toFile());
			}
			final Process proc = pb.start();
			drainStderrAsync(proc);

			long lineCount = 0;
			try (BufferedReader reader = new BufferedReader(
					new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8));
					BufferedWriter writer = Files.newBufferedWriter(workSqlPath, StandardCharsets.UTF_8)) {
				String line;
				while ((line = reader.readLine()) != null) {
					writer.write(rewriteDumpSqlLine(line));
					writer.newLine();
					lineCount++;
				}
			}
			if (!proc.waitFor(CLI_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
				proc.destroyForcibly();
				return false;
			}
			if (lineCount == 0 || !Files.exists(workSqlPath) || Files.size(workSqlPath) == 0L) {
				return false;
			}
			if (!importSqlFileWithCli(sqliteCli, workSqlPath, workDbPath)) {
				return false;
			}
			final SQLiteDataSource workDs = createIsolatedDataSource(workDbPath, false);
			accessor.validate(new QueryRunner(workDs));
			return verifyDatabaseHealthy(workDbPath, accessor);
		} catch (Exception e) {
			logger.debug("sqlite3 .dump による修復に失敗しました: {}", e.getMessage());
			return false;
		}
	}

	/**
	 * sqlite3 .dump が破損検出時に出力する "ROLLBACK; -- due to errors" を "COMMIT;" に書き換える
	 */
	static String rewriteDumpSqlLine(String line) {
		if (line == null) {
			return "";
		}
		final String trimmed = line.trim();
		if (trimmed.regionMatches(true, 0, "ROLLBACK;", 0, "ROLLBACK;".length())) {
			return "COMMIT;";
		}
		return line;
	}

	private static boolean importSqlFileWithCli(String sqliteCli, Path sqlFile, Path targetDbPath)
			throws IOException, InterruptedException {
		final ProcessBuilder pb = new ProcessBuilder(sqliteCli, targetDbPath.getFileName().toString());
		if (targetDbPath.getParent() != null) {
			pb.directory(targetDbPath.getParent().toFile());
		}
		pb.redirectInput(sqlFile.toFile());
		pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
		final Process proc = pb.start();
		drainStderrAsync(proc);
		if (!proc.waitFor(CLI_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
			proc.destroyForcibly();
			return false;
		}
		return proc.exitValue() == 0 && Files.exists(targetDbPath) && Files.size(targetDbPath) > 0L;
	}

	private static void drainStderrAsync(Process process) {
		final Thread t = new Thread(() -> {
			try (var in = process.getErrorStream()) {
				in.transferTo(java.io.OutputStream.nullOutputStream());
			} catch (IOException ignored) {
			}
		}, "sqlite3-stderr-drain");
		t.setDaemon(true);
		t.start();
	}

	/**
	 * Stage 3: Java側のスキーマ定義から新規DBを構築し、破損DBから _rowid_ 昇順・降順・二分探索で生存行を救出する。
	 * 戻り値は救出した合計行数（スキーマ初期化自体に失敗した場合は -1）。
	 */
	static long tryJavaRowSalvage(Path backupPath, Path workDbPath, SQLiteDatabaseAccessor accessor) {
		if (!initializeCleanDatabase(workDbPath, accessor)) {
			return -1L;
		}
		final SQLiteDataSource srcDs = createIsolatedDataSource(backupPath, true);
		final SQLiteDataSource dstDs = createIsolatedDataSource(workDbPath, false);

		long totalSalvaged = 0L;
		boolean anySourceProbeSucceeded = false;

		try (Connection srcConn = srcDs.getConnection();
				Connection dstConn = dstDs.getConnection()) {
			try (Statement pragmaStmt = srcConn.createStatement()) {
				pragmaStmt.execute("PRAGMA ignore_check_constraints = ON;");
			} catch (SQLException ignored) {
			}
			dstConn.setAutoCommit(false);

			for (Table table : accessor.getTables()) {
				final List<Column> readableColumns = resolveReadableColumns(srcConn, table);
				if (readableColumns.isEmpty()) {
					continue;
				}
				anySourceProbeSucceeded = true;
				totalSalvaged += salvageTableRows(srcConn, dstConn, table, readableColumns);
			}
			dstConn.commit();
		} catch (SQLException e) {
			logger.debug("Javaレコード救出中にソースDB接続レベルで例外が発生しました: {}", e.getMessage());
			if (!anySourceProbeSucceeded && totalSalvaged == 0L) {
				return -1L;
			}
		}
		return totalSalvaged;
	}

	/**
	 * 破損元DBのテーブルから読み取り可能なカラム一覧を特定する
	 */
	private static List<Column> resolveReadableColumns(Connection srcConn, Table table) {
		final Map<String, Column> targetMap = new HashMap<>();
		for (Column col : table.getColumn()) {
			targetMap.put(col.getName().toLowerCase(Locale.ROOT), col);
		}

		final List<Column> resolved = new ArrayList<>();
		try (Statement stmt = srcConn.createStatement();
				ResultSet rs = stmt.executeQuery("PRAGMA table_info('" + table.getName() + "');")) {
			while (rs.next()) {
				final String name = rs.getString("name");
				if (name != null) {
					final Column matched = targetMap.get(name.toLowerCase(Locale.ROOT));
					if (matched != null) {
						resolved.add(matched);
					}
				}
			}
		} catch (SQLException e) {
			logger.debug("PRAGMA table_info の取得に失敗したため既定カラム定義で試行します ({}): {}",
					table.getName(), e.getMessage());
		}

		if (!resolved.isEmpty()) {
			return resolved;
		}

		// PRAGMA table_info が取得できなかった場合は、カラムごとに SELECT 可能か個別に確認する
		for (Column col : table.getColumn()) {
			try (Statement stmt = srcConn.createStatement();
					ResultSet rs = stmt.executeQuery(
							"SELECT [" + col.getName() + "] FROM [" + table.getName() + "] LIMIT 0")) {
				resolved.add(col);
			} catch (SQLException ignored) {
			}
		}
		return resolved;
	}

	/**
	 * 単一テーブルの生存レコードを _rowid_ 昇順スキャン・降順スキャン・破損領域の二分探索により重複ゼロで救出する
	 */
	private static long salvageTableRows(
			Connection srcConn,
			Connection dstConn,
			Table table,
			List<Column> columns) throws SQLException {
		final String selectCols = buildSelectColumnList(columns);
		final String insertSql = buildInsertSql(table.getName(), columns);

		long tableSalvaged = 0L;
		long lastAscRowId = Long.MIN_VALUE;
		boolean hasAscRow = false;
		boolean ascCompleted = false;

		try (PreparedStatement insertStmt = dstConn.prepareStatement(insertSql)) {
			// 1. 先頭からの昇順スキャン（破損が無いテーブルは1パスで全件完了）
			final String ascSql = "SELECT _rowid_, " + selectCols + " FROM [" + table.getName() + "] ORDER BY _rowid_ ASC";
			int uncommitted = 0;
			try (Statement stmt = srcConn.createStatement()) {
				stmt.setFetchSize(1000);
				try (ResultSet rs = stmt.executeQuery(ascSql)) {
					while (rs.next()) {
						final long rowId = rs.getLong(1);
						if (bindAndInsertRow(rs, insertStmt, columns)) {
							tableSalvaged++;
							if (++uncommitted >= BATCH_COMMIT_SIZE) {
								dstConn.commit();
								uncommitted = 0;
							}
						}
						lastAscRowId = rowId;
						hasAscRow = true;
					}
					ascCompleted = true;
				}
			} catch (SQLException e) {
				logger.warn("テーブル [{}] の昇順スキャン中に破損ページを検知しました (到達 rowid={}): {}",
						table.getName(), hasAscRow ? lastAscRowId : "なし", e.getMessage());
			}
			dstConn.commit();

			if (ascCompleted) {
				return tableSalvaged;
			}

			// 2. 末尾からの降順スキャン（昇順で到達した lastAscRowId より後ろの生存ページを末尾側から救出）
			long lowestDescRowId = Long.MAX_VALUE;
			boolean hasDescRow = false;
			boolean descCompleted = false;
			uncommitted = 0;

			final String descSql = hasAscRow
					? "SELECT _rowid_, " + selectCols + " FROM [" + table.getName() + "] WHERE _rowid_ > ? ORDER BY _rowid_ DESC"
					: "SELECT _rowid_, " + selectCols + " FROM [" + table.getName() + "] ORDER BY _rowid_ DESC";
			try (PreparedStatement descStmt = srcConn.prepareStatement(descSql)) {
				descStmt.setFetchSize(1000);
				if (hasAscRow) {
					descStmt.setLong(1, lastAscRowId);
				}
				try (ResultSet rs = descStmt.executeQuery()) {
					while (rs.next()) {
						final long rowId = rs.getLong(1);
						if (bindAndInsertRow(rs, insertStmt, columns)) {
							tableSalvaged++;
							if (++uncommitted >= BATCH_COMMIT_SIZE) {
								dstConn.commit();
								uncommitted = 0;
							}
						}
						lowestDescRowId = rowId;
						hasDescRow = true;
					}
					descCompleted = true;
				}
			} catch (SQLException e) {
				logger.warn("テーブル [{}] の降順スキャン中に破損ページを検知しました (到達下限 rowid={}): {}",
						table.getName(), hasDescRow ? lowestDescRowId : "なし", e.getMessage());
			}
			dstConn.commit();

			if (descCompleted) {
				return tableSalvaged;
			}

			// 3. 中間に複数の破損ページが存在する場合、未走査の (lowExclusive, highExclusive) 区間を二分探索で救出
			final Deque<long[]> pendingRanges = new ArrayDeque<>();
			if (hasDescRow) {
				final long lo = hasAscRow ? lastAscRowId : 0L;
				if (lowestDescRowId - lo > 1L) {
					pendingRanges.addLast(new long[] { lo, lowestDescRowId });
				}
			} else {
				// 先頭ページと末尾ページの双方が破損していた場合、指数間隔で中間ページ群を探索対象に追加
				long base = hasAscRow ? Math.max(1L, lastAscRowId) : 0L;
				long step = 64L;
				for (int i = 0; i < 12; i++) {
					final long next = base + step;
					pendingRanges.addLast(new long[] { base, next });
					base = next;
					step *= 4L;
				}
			}

			final String rangeSql = "SELECT _rowid_, " + selectCols
					+ " FROM [" + table.getName() + "] WHERE _rowid_ >= ? AND _rowid_ < ? ORDER BY _rowid_ ASC";
			int probes = 0;
			try (PreparedStatement rangeStmt = srcConn.prepareStatement(rangeSql)) {
				rangeStmt.setFetchSize(500);
				while (!pendingRanges.isEmpty() && probes < MAX_BISECTION_PROBES) {
					final long[] range = pendingRanges.removeFirst();
					final long loExclusive = range[0];
					final long hiExclusive = range[1];
					if (hiExclusive - loExclusive <= 1L) {
						continue;
					}
					probes++;
					final long mid = loExclusive + ((hiExclusive - loExclusive) >>> 1);
					if (mid > loExclusive + 1L) {
						pendingRanges.addLast(new long[] { loExclusive, mid });
					}

					long reached = mid - 1L;
					boolean rangeCompleted = false;
					uncommitted = 0;
					try {
						rangeStmt.setLong(1, mid);
						rangeStmt.setLong(2, hiExclusive);
						try (ResultSet rs = rangeStmt.executeQuery()) {
							while (rs.next()) {
								final long rowId = rs.getLong(1);
								if (bindAndInsertRow(rs, insertStmt, columns)) {
									tableSalvaged++;
									if (++uncommitted >= BATCH_COMMIT_SIZE) {
										dstConn.commit();
										uncommitted = 0;
									}
								}
								reached = rowId;
							}
							rangeCompleted = true;
						}
					} catch (SQLException ignored) {
					}
					dstConn.commit();

					if (!rangeCompleted) {
						final long nextStart = Math.max(mid, reached) + 1L;
						if (hiExclusive - nextStart > 1L) {
							pendingRanges.addLast(new long[] { nextStart - 1L, hiExclusive });
						}
					}
				}
			}
		}
		return tableSalvaged;
	}

	private static String buildSelectColumnList(List<Column> columns) {
		final StringBuilder sb = new StringBuilder();
		for (int i = 0; i < columns.size(); i++) {
			if (i > 0) {
				sb.append(", ");
			}
			sb.append('[').append(columns.get(i).getName()).append(']');
		}
		return sb.toString();
	}

	private static String buildInsertSql(String tableName, List<Column> columns) {
		final StringBuilder sb = new StringBuilder("INSERT OR IGNORE INTO [").append(tableName).append("] (");
		for (int i = 0; i < columns.size(); i++) {
			if (i > 0) {
				sb.append(',');
			}
			sb.append('[').append(columns.get(i).getName()).append(']');
		}
		sb.append(") VALUES (");
		for (int i = 0; i < columns.size(); i++) {
			if (i > 0) {
				sb.append(',');
			}
			sb.append('?');
		}
		sb.append(')');
		return sb.toString();
	}

	private static boolean bindAndInsertRow(ResultSet rs, PreparedStatement insertStmt, List<Column> columns)
			throws SQLException {
		for (int i = 0; i < columns.size(); i++) {
			final Column col = columns.get(i);
			Object value;
			try {
				value = rs.getObject(i + 2);
			} catch (SQLException cellEx) {
				// 破損セルが読み取れない場合
				value = null;
			}
			if (value == null && col.getNotnull() == 1) {
				if (col.getPk() == 1 || "sha256".equalsIgnoreCase(col.getName()) || "id".equalsIgnoreCase(col.getName())) {
					return false;
				}
				value = fallbackNotNullValue(col);
			}
			insertStmt.setObject(i + 1, value);
		}
		insertStmt.executeUpdate();
		return true;
	}

	private static Object fallbackNotNullValue(Column col) {
		final String def = col.getDefaultval();
		final String type = col.getType() != null ? col.getType().toUpperCase(Locale.ROOT) : "TEXT";
		if (type.contains("INT")) {
			if (def != null && !def.isEmpty()) {
				try {
					return Long.parseLong(def.trim());
				} catch (NumberFormatException ignored) {
				}
			}
			return 0L;
		}
		if (type.contains("REAL") || type.contains("FLOA") || type.contains("DOUB")) {
			if (def != null && !def.isEmpty()) {
				try {
					return Double.parseDouble(def.trim());
				} catch (NumberFormatException ignored) {
				}
			}
			return 0.0;
		}
		return def != null ? def.replace("'", "") : "";
	}

	private static boolean initializeCleanDatabase(Path targetDbPath, SQLiteDatabaseAccessor accessor) {
		try {
			deleteTempDbAndSidecars(targetDbPath);
			final SQLiteDataSource cleanDs = createIsolatedDataSource(targetDbPath, false);
			accessor.validate(new QueryRunner(cleanDs));
			return true;
		} catch (Exception e) {
			logger.error("クリーンなデータベースの初期化に失敗しました ({}): {}", targetDbPath, e.getMessage());
			return false;
		}
	}

	private static boolean verifyDatabaseHealthy(Path dbPath, SQLiteDatabaseAccessor accessor) {
		if (!Files.exists(dbPath)) {
			return false;
		}
		final String issue = diagnoseDatabase(dbPath, IntegrityCheckMode.FULL);
		if (issue != null) {
			return false;
		}
		final SQLiteDataSource ds = createIsolatedDataSource(dbPath, true);
		try (Connection conn = ds.getConnection();
				PreparedStatement ps = conn.prepareStatement(
						"SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?")) {
			for (Table table : accessor.getTables()) {
				ps.setString(1, table.getName());
				try (ResultSet rs = ps.executeQuery()) {
					if (!rs.next()) {
						return false;
					}
				}
			}
			return true;
		} catch (SQLException e) {
			return false;
		}
	}

	private static long countTotalRows(Path dbPath, SQLiteDatabaseAccessor accessor) {
		long total = 0L;
		final SQLiteDataSource ds = createIsolatedDataSource(dbPath, true);
		try (Connection conn = ds.getConnection();
				Statement stmt = conn.createStatement()) {
			for (Table table : accessor.getTables()) {
				try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM [" + table.getName() + "]")) {
					if (rs.next()) {
						total += rs.getLong(1);
					}
				}
			}
		} catch (SQLException ignored) {
		}
		return total;
	}

	/**
	 * 残留ジャーナル（-journal / -wal / -shm）を除去した上で、検証済みの修復DBで本体DBを置換する
	 */
	private static void replaceDatabaseFile(Path repairedDbPath, Path targetDbPath) throws IOException {
		deleteSidecarsOnly(repairedDbPath);
		deleteSidecarsOnly(targetDbPath);
		try {
			Files.move(repairedDbPath, targetDbPath,
					StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(repairedDbPath, targetDbPath, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private static void deleteTempDbAndSidecars(Path dbPath) {
		if (dbPath == null) {
			return;
		}
		deleteSidecarsOnly(dbPath);
		try {
			Files.deleteIfExists(dbPath);
		} catch (IOException ignored) {
		}
	}

	private static void deleteSidecarsOnly(Path dbPath) {
		final Path parent = dbPath.getParent() != null ? dbPath.getParent() : Paths.get(".");
		final String name = dbPath.getFileName().toString();
		for (String ext : new String[] { "-journal", "-wal", "-shm" }) {
			try {
				Files.deleteIfExists(parent.resolve(name + ext));
			} catch (IOException ignored) {
			}
		}
	}

	private static SQLiteDataSource createIsolatedDataSource(Path dbPath, boolean readOnly) {
		final SQLiteConfig conf = new SQLiteConfig();
		conf.setSharedCache(false);
		if (readOnly) {
			conf.setReadOnly(true);
		}
		final SQLiteDataSource ds = new SQLiteDataSource(conf);
		ds.setUrl("jdbc:sqlite:" + dbPath.toAbsolutePath());
		return ds;
	}

	/**
	 * 外部 sqlite3 CLI の利用設定を上書きする（テスト用。null を指定すると CLI を使用しない）
	 */
	static void overrideSqliteCliForTest(String executable) {
		synchronized (SQLiteDatabaseRepairer.class) {
			sqliteCliExecutable = executable;
			sqliteCliChecked = true;
		}
	}

	private static String resolveSqliteCli() {
		if (sqliteCliChecked) {
			return sqliteCliExecutable;
		}
		synchronized (SQLiteDatabaseRepairer.class) {
			if (sqliteCliChecked) {
				return sqliteCliExecutable;
			}
			try {
				final Process proc = new ProcessBuilder("sqlite3", "-version")
						.redirectErrorStream(true)
						.start();
				try (var in = proc.getInputStream()) {
					in.transferTo(java.io.OutputStream.nullOutputStream());
				}
				if (proc.waitFor(3, TimeUnit.SECONDS) && proc.exitValue() == 0) {
					sqliteCliExecutable = "sqlite3";
				}
			} catch (Exception ignored) {
				sqliteCliExecutable = null;
			}
			sqliteCliChecked = true;
			return sqliteCliExecutable;
		}
	}
}
