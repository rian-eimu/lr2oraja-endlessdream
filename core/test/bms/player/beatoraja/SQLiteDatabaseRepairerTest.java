package bms.player.beatoraja;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import bms.player.beatoraja.SQLiteDatabaseRepairer.IntegrityCheckMode;
import bms.player.beatoraja.SQLiteDatabaseRepairer.RepairMethod;
import bms.player.beatoraja.SQLiteDatabaseRepairer.RepairResult;

/**
 * SQLiteデータベース破損時の自動検知・自動修復の単体テスト（実際にバイナリを破壊したファイルで検証する）
 */
class SQLiteDatabaseRepairerTest {

	@TempDir
	Path tempDir;

	@BeforeAll
	static void disableSqliteCli() throws ClassNotFoundException {
		Class.forName("org.sqlite.JDBC");
		// 実行環境の sqlite3 CLI の有無に左右されず Pure Java 経路を検証する
		SQLiteDatabaseRepairer.overrideSqliteCliForTest(null);
	}

	@AfterAll
	static void discardPendingNotifications() {
		// テストで発生した通知をキューに残さない
		try {
			SQLiteDatabaseRepairer.flushPendingNotifications();
		} catch (Throwable ignored) {
		}
	}

	/** 主キー付きのスコアテーブル定義 */
	private static SQLiteDatabaseAccessor scoreAccessor() {
		return new SQLiteDatabaseAccessor(new SQLiteDatabaseAccessor.Table("score",
				new SQLiteDatabaseAccessor.Column("sha256", "TEXT", 1, 1),
				new SQLiteDatabaseAccessor.Column("mode", "INTEGER", 0, 1),
				new SQLiteDatabaseAccessor.Column("clear", "INTEGER"),
				new SQLiteDatabaseAccessor.Column("ghost", "TEXT"),
				new SQLiteDatabaseAccessor.Column("date", "INTEGER"))) {
		};
	}

	/** 主キー無しのスコアログテーブル定義（scorelog.db 相当） */
	private static SQLiteDatabaseAccessor scoreLogAccessor() {
		return new SQLiteDatabaseAccessor(new SQLiteDatabaseAccessor.Table("scorelog",
				new SQLiteDatabaseAccessor.Column("sha256", "TEXT", 1, 0),
				new SQLiteDatabaseAccessor.Column("mode", "INTEGER"),
				new SQLiteDatabaseAccessor.Column("pad", "TEXT"),
				new SQLiteDatabaseAccessor.Column("date", "INTEGER"))) {
		};
	}

	private static Connection open(Path db) throws SQLException {
		return DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
	}

	private static void createScoreDb(Path db, int rows) throws SQLException {
		try (Connection conn = open(db); Statement st = conn.createStatement()) {
			st.execute("CREATE TABLE [score] ([sha256] TEXT NOT NULL,[mode] INTEGER,[clear] INTEGER,[ghost] TEXT,[date] INTEGER,"
					+ "PRIMARY KEY(sha256,mode));");
			conn.setAutoCommit(false);
			try (PreparedStatement ps = conn.prepareStatement("INSERT INTO score VALUES (?,?,?,?,?)")) {
				for (int i = 0; i < rows; i++) {
					ps.setString(1, String.format("%064d", i));
					ps.setInt(2, 0);
					ps.setInt(3, i % 10);
					ps.setString(4, "g".repeat(200) + i);
					ps.setLong(5, 100000L + i);
					ps.executeUpdate();
				}
			}
			conn.commit();
		}
	}

	private static void createScoreLogDb(Path db, int rows) throws SQLException {
		try (Connection conn = open(db); Statement st = conn.createStatement()) {
			st.execute("CREATE TABLE [scorelog] ([sha256] TEXT NOT NULL,[mode] INTEGER,[pad] TEXT,[date] INTEGER);");
			conn.setAutoCommit(false);
			try (PreparedStatement ps = conn.prepareStatement("INSERT INTO scorelog VALUES (?,?,?,?)")) {
				for (int i = 0; i < rows; i++) {
					ps.setString(1, String.format("%064d", i % 50));
					ps.setInt(2, 0);
					ps.setString(3, "p".repeat(200));
					ps.setLong(4, 200000L + i);
					ps.executeUpdate();
				}
			}
			conn.commit();
		}
	}

	/**
	 * 中央付近のテーブルリーフページ（ページ種別 0x0D）を 0xFF で塗り潰して破損させる
	 */
	private static void corruptMiddleTableLeafPage(Path db) throws IOException {
		final byte[] data = Files.readAllBytes(db);
		int pageSize = ((data[16] & 0xFF) << 8) | (data[17] & 0xFF);
		if (pageSize == 1) {
			pageSize = 65536;
		}
		final int pages = data.length / pageSize;
		final List<Integer> leafPages = new ArrayList<>();
		for (int p = 2; p <= pages; p++) {
			if ((data[(p - 1) * pageSize] & 0xFF) == 0x0D) {
				leafPages.add(p);
			}
		}
		assertTrue(leafPages.size() > 10, "テストDBのリーフページ数が不足しています");
		final int target = leafPages.get(leafPages.size() / 2);
		final int offset = (target - 1) * pageSize;
		Arrays.fill(data, offset, offset + pageSize, (byte) 0xFF);
		Files.write(db, data);
	}

	private static long count(Path db, String sql) throws SQLException {
		try (Connection conn = open(db); Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
			assertTrue(rs.next());
			return rs.getLong(1);
		}
	}

	private List<Path> backups(String dbName) throws IOException {
		try (Stream<Path> s = Files.list(tempDir)) {
			return s.filter(p -> {
				final String n = p.getFileName().toString();
				return n.startsWith(dbName + ".corrupt-") && n.endsWith(".bak");
			}).toList();
		}
	}

	private List<Path> leftoverTempFiles() throws IOException {
		try (Stream<Path> s = Files.list(tempDir)) {
			return s.filter(p -> p.getFileName().toString().contains(".repair-")).toList();
		}
	}

	@Test
	void healthyDatabaseIsLeftUntouched() throws Exception {
		final Path db = tempDir.resolve("score.db");
		createScoreDb(db, 200);
		final byte[] before = Files.readAllBytes(db);

		final RepairResult result = SQLiteDatabaseRepairer.checkAndRepairOnStartup(db, IntegrityCheckMode.FULL, scoreAccessor());

		assertFalse(result.repaired());
		assertEquals(RepairMethod.NONE, result.method());
		assertTrue(backups("score.db").isEmpty());
		assertArrayEquals(before, Files.readAllBytes(db));
	}

	@Test
	void indexOnlyCorruptionIsRepairedLosslesslyByReindex() throws Exception {
		final Path db = tempDir.resolve("score.db");
		createScoreDb(db, 500);
		try (Connection conn = open(db); Statement st = conn.createStatement()) {
			st.execute("CREATE INDEX idx_clear ON score(clear)");
		}
		// インデックス定義だけを書き換え、実データとインデックス内容を不整合にする
		try (Connection conn = open(db); Statement st = conn.createStatement()) {
			st.execute("PRAGMA writable_schema = ON");
			st.executeUpdate("UPDATE sqlite_master SET sql = 'CREATE INDEX idx_clear ON score(date)' WHERE name = 'idx_clear'");
			st.execute("PRAGMA writable_schema = OFF");
		}
		assertNotNull(SQLiteDatabaseRepairer.diagnoseDatabase(db, IntegrityCheckMode.FULL), "破損の作成に失敗しています");

		final RepairResult result = SQLiteDatabaseRepairer.checkAndRepairOnStartup(db, IntegrityCheckMode.FULL, scoreAccessor());

		assertTrue(result.repaired());
		assertEquals(RepairMethod.REINDEX, result.method());
		assertEquals(500, count(db, "SELECT COUNT(*) FROM score"));
		assertNull(SQLiteDatabaseRepairer.diagnoseDatabase(db, IntegrityCheckMode.FULL));
		assertEquals(1, backups("score.db").size());
		assertTrue(leftoverTempFiles().isEmpty());
	}

	@Test
	void corruptedTablePageIsSalvagedAroundTheDamagedPage() throws Exception {
		final int rows = 3000;
		final Path db = tempDir.resolve("score.db");
		createScoreDb(db, rows);
		corruptMiddleTableLeafPage(db);
		assertNotNull(SQLiteDatabaseRepairer.diagnoseDatabase(db, IntegrityCheckMode.FULL), "破損の作成に失敗しています");

		final RepairResult result = SQLiteDatabaseRepairer.checkAndRepairOnStartup(db, IntegrityCheckMode.FULL, scoreAccessor());

		assertTrue(result.repaired());
		assertEquals(RepairMethod.JAVA_SALVAGE, result.method());
		final long salvaged = count(db, "SELECT COUNT(*) FROM score");
		assertEquals(salvaged, result.salvagedRows());
		// 破損ページ 1 枚分のみ欠損し、前後のレコードは救出される
		assertTrue(salvaged < rows, "破損ページのレコードが残っています: " + salvaged);
		assertTrue(salvaged > rows - 100, "救出件数が少なすぎます: " + salvaged);
		// 末尾側（破損ページより後ろ）のレコードも救出されている
		assertEquals(1, count(db, "SELECT COUNT(*) FROM score WHERE sha256 = '" + String.format("%064d", rows - 1) + "'"));
		assertEquals(1, count(db, "SELECT COUNT(*) FROM score WHERE sha256 = '" + String.format("%064d", 0) + "'"));
		assertNull(SQLiteDatabaseRepairer.diagnoseDatabase(db, IntegrityCheckMode.FULL));
		// 元の破損ファイルはバックアップとして保全されている
		final List<Path> backups = backups("score.db");
		assertEquals(1, backups.size());
		assertNotNull(SQLiteDatabaseRepairer.diagnoseDatabase(backups.get(0), IntegrityCheckMode.FULL));
		assertTrue(leftoverTempFiles().isEmpty());
	}

	@Test
	void tableWithoutPrimaryKeyIsSalvagedWithoutDuplicates() throws Exception {
		final int rows = 3000;
		final Path db = tempDir.resolve("scorelog.db");
		createScoreLogDb(db, rows);
		corruptMiddleTableLeafPage(db);

		final RepairResult result = SQLiteDatabaseRepairer.checkAndRepairOnStartup(db, IntegrityCheckMode.FULL, scoreLogAccessor());

		assertTrue(result.repaired());
		assertEquals(RepairMethod.JAVA_SALVAGE, result.method());
		final long salvaged = count(db, "SELECT COUNT(*) FROM scorelog");
		assertTrue(salvaged < rows && salvaged > rows - 100, "救出件数: " + salvaged);
		assertEquals(salvaged, count(db, "SELECT COUNT(DISTINCT date) FROM scorelog"), "重複レコードが発生しています");
		assertNull(SQLiteDatabaseRepairer.diagnoseDatabase(db, IntegrityCheckMode.FULL));
	}

	@Test
	void nonDatabaseFileIsBackedUpAndReinitializedOnAccessorStartup() throws Exception {
		final Path db = tempDir.resolve("score.db");
		final byte[] garbage = "this is not a sqlite database file. ".repeat(500).getBytes(StandardCharsets.US_ASCII);
		Files.write(db, garbage);

		final ScoreDatabaseAccessor accessor = new ScoreDatabaseAccessor(db.toString());
		accessor.createTable();

		assertNull(SQLiteDatabaseRepairer.diagnoseDatabase(db, IntegrityCheckMode.FULL));
		assertEquals(1, accessor.getPlayerDatas(1).length);
		final List<Path> backups = backups("score.db");
		assertEquals(1, backups.size());
		assertArrayEquals(garbage, Files.readAllBytes(backups.get(0)));
	}

	@Test
	void corruptionDetectedAtRuntimeIsRepairedAndQueryIsRetried() throws Exception {
		final Path db = tempDir.resolve("score.db");
		final ScoreDatabaseAccessor accessor = new ScoreDatabaseAccessor(db.toString());
		accessor.createTable();
		assertEquals(1, accessor.getPlayerDatas(1).length);

		// 起動後にディスク上のファイルが破損した状況を再現する
		Files.write(db, "broken database content ".repeat(400).getBytes(StandardCharsets.US_ASCII));

		// 破損例外は内部で自動修復・再試行されるため、空の結果として正常に返る
		assertEquals(0, accessor.getPlayerDatas(1).length);
		assertNull(SQLiteDatabaseRepairer.diagnoseDatabase(db, IntegrityCheckMode.FULL));
		assertEquals(1, backups("score.db").size());

		// 修復後は通常どおり読み書きできる
		accessor.createTable();
		assertEquals(1, accessor.getPlayerDatas(1).length);
	}

	@Test
	void corruptionExceptionIsDetectedFromErrorCodeAndMessage() {
		assertTrue(SQLiteDatabaseRepairer.isCorruptionException(new SQLException("x", "", 11)));
		assertTrue(SQLiteDatabaseRepairer.isCorruptionException(new SQLException("x", "", 26)));
		assertTrue(SQLiteDatabaseRepairer.isCorruptionException(
				new RuntimeException(new SQLException("[SQLITE_CORRUPT] The database disk image is malformed (database disk image is malformed)"))));
		assertFalse(SQLiteDatabaseRepairer.isCorruptionException(new SQLException("[SQLITE_BUSY] database is locked", "", 5)));
		assertFalse(SQLiteDatabaseRepairer.isCorruptionException(null));
	}

	@Test
	void dumpRollbackIsRewrittenToCommit() {
		assertEquals("COMMIT;", SQLiteDatabaseRepairer.rewriteDumpSqlLine("ROLLBACK; -- due to errors"));
		assertEquals("COMMIT;", SQLiteDatabaseRepairer.rewriteDumpSqlLine("  rollback;"));
		assertEquals("INSERT INTO score VALUES('ROLLBACK;');",
				SQLiteDatabaseRepairer.rewriteDumpSqlLine("INSERT INTO score VALUES('ROLLBACK;');"));
		assertEquals("COMMIT;", SQLiteDatabaseRepairer.rewriteDumpSqlLine("COMMIT;"));
	}
}
