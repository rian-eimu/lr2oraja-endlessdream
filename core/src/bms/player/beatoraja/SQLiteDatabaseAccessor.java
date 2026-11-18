package bms.player.beatoraja;

import java.beans.IntrospectionException;
import java.beans.PropertyDescriptor;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import javax.sql.DataSource;

import org.apache.commons.dbutils.QueryRunner;
import org.apache.commons.dbutils.ResultSetHandler;
import org.apache.commons.dbutils.handlers.BeanListHandler;
import org.apache.commons.dbutils.handlers.MapListHandler;

import bms.player.beatoraja.SQLiteDatabaseRepairer.IntegrityCheckMode;

/**
 * SQLiteデータベースアクセス用抽象クラス
 * 
 * @author exch
 */
public abstract class SQLiteDatabaseAccessor {

	private final ResultSetHandler<List<Column>> columnhandler = new BeanListHandler<Column>(Column.class);

	private final Table[] tables;
	
	public SQLiteDatabaseAccessor(Table... tables) {
		this.tables = tables;
	}

	/**
	 * テーブル定義一覧を返す（破損時のスキーマ再構築に使用）
	 */
	public Table[] getTables() {
		return tables;
	}

	/**
	 * 起動時の整合性チェック（破損時は自動修復）を行った上で、
	 * 実行時の破損検知・自動修復・再試行機能を備えたQueryRunnerを生成する
	 *
	 * @param ds データソース
	 * @param filepath データベースファイルのパス
	 * @param mode 起動時の整合性チェック種別
	 */
	protected QueryRunner createRepairableQueryRunner(DataSource ds, String filepath, IntegrityCheckMode mode) {
		final Path dbPath = Paths.get(filepath).toAbsolutePath().normalize();
		SQLiteDatabaseRepairer.checkAndRepairOnStartup(dbPath, mode, this);
		return new RepairingQueryRunner(ds, dbPath);
	}

	/**
	 * 実行時の自動修復完了後に呼び出されるフック。テーブル定義を再検証する。
	 * インデックス等の追加オブジェクトが必要なサブクラスはオーバーライドして再作成すること。
	 */
	protected void onDatabaseRepaired(QueryRunner qr) throws SQLException {
		validate(qr);
	}

	@FunctionalInterface
	private interface SqlCall<T> {
		T call() throws SQLException;
	}

	/**
	 * 実行中にデータベース破損（SQLITE_CORRUPT / SQLITE_NOTADB）を検知した場合に、
	 * 自動修復を行って1回だけ再試行するQueryRunner
	 */
	private final class RepairingQueryRunner extends QueryRunner {

		private final Path dbPath;
		/**
		 * 通常の操作は読み取りロック、修復（ファイル置換）は書き込みロックで排他する
		 */
		private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
		/**
		 * 修復処理中のスレッドでは再帰的な修復を行わない
		 */
		private final ThreadLocal<Boolean> repairing = ThreadLocal.withInitial(() -> Boolean.FALSE);
		/**
		 * 修復世代。同時に複数スレッドが破損を検知した場合の重複修復を防ぐ
		 */
		private final AtomicLong generation = new AtomicLong();
		/**
		 * 外部コネクション経由の操作で破損を検知した場合、次回操作前に修復する
		 */
		private volatile boolean pendingRepair;
		private volatile long pendingGeneration;
		private volatile String pendingReason;
		/**
		 * 修復に失敗した場合はバックアップの大量生成を避けるため以降の実行時修復を停止する
		 */
		private volatile boolean runtimeRepairDisabled;

		RepairingQueryRunner(DataSource ds, Path dbPath) {
			super(ds);
			this.dbPath = dbPath;
		}

		private <T> T execute(SqlCall<T> call) throws SQLException {
			if (repairing.get()) {
				return call.call();
			}
			if (pendingRepair) {
				repair(pendingReason, pendingGeneration);
			}
			final long gen = generation.get();
			try {
				return withReadLock(call);
			} catch (SQLException e) {
				if (!SQLiteDatabaseRepairer.isCorruptionException(e) || !repair(e.getMessage(), gen)) {
					throw e;
				}
				return withReadLock(call);
			}
		}

		private <T> T executeOnConnection(SqlCall<T> call) throws SQLException {
			try {
				return call.call();
			} catch (SQLException e) {
				if (!repairing.get() && SQLiteDatabaseRepairer.isCorruptionException(e) && !runtimeRepairDisabled) {
					// 呼び出し元がコネクションを保持しているためここでは修復せず、次回の操作前に修復する
					pendingGeneration = generation.get();
					pendingReason = e.getMessage();
					pendingRepair = true;
				}
				throw e;
			}
		}

		private <T> T withReadLock(SqlCall<T> call) throws SQLException {
			lock.readLock().lock();
			try {
				return call.call();
			} finally {
				lock.readLock().unlock();
			}
		}

		/**
		 * @return 再試行してよい場合はtrue
		 */
		private boolean repair(String reason, long detectedGeneration) {
			lock.writeLock().lock();
			repairing.set(Boolean.TRUE);
			try {
				if (generation.get() != detectedGeneration) {
					// 他スレッドが既に修復済み
					return true;
				}
				pendingRepair = false;
				if (runtimeRepairDisabled) {
					return false;
				}
				final boolean repaired = SQLiteDatabaseRepairer
						.repairDatabase(dbPath, SQLiteDatabaseAccessor.this, reason).repaired();
				if (!repaired) {
					runtimeRepairDisabled = true;
					return false;
				}
				generation.incrementAndGet();
				try {
					onDatabaseRepaired(this);
				} catch (SQLException e) {
					// テーブル定義は修復処理内で再構築済みのため、ここでの失敗は再試行時に判明させる
				}
				return true;
			} finally {
				repairing.set(Boolean.FALSE);
				lock.writeLock().unlock();
			}
		}

		@Override
		public <T> T query(String sql, ResultSetHandler<T> rsh, Object... params) throws SQLException {
			return execute(() -> super.query(sql, rsh, params));
		}

		@Override
		public <T> T query(String sql, ResultSetHandler<T> rsh) throws SQLException {
			return execute(() -> super.query(sql, rsh));
		}

		@Override
		public int update(String sql) throws SQLException {
			return execute(() -> super.update(sql));
		}

		@Override
		public int update(String sql, Object param) throws SQLException {
			return execute(() -> super.update(sql, param));
		}

		@Override
		public int update(String sql, Object... params) throws SQLException {
			return execute(() -> super.update(sql, params));
		}

		@Override
		public <T> T query(Connection conn, String sql, ResultSetHandler<T> rsh, Object... params) throws SQLException {
			return executeOnConnection(() -> super.query(conn, sql, rsh, params));
		}

		@Override
		public <T> T query(Connection conn, String sql, ResultSetHandler<T> rsh) throws SQLException {
			return executeOnConnection(() -> super.query(conn, sql, rsh));
		}

		@Override
		public int update(Connection conn, String sql) throws SQLException {
			return executeOnConnection(() -> super.update(conn, sql));
		}

		@Override
		public int update(Connection conn, String sql, Object param) throws SQLException {
			return executeOnConnection(() -> super.update(conn, sql, param));
		}

		@Override
		public int update(Connection conn, String sql, Object... params) throws SQLException {
			return executeOnConnection(() -> super.update(conn, sql, params));
		}
	}

	/**
	 * 指定のカラムを持つテーブルを作成する。 テーブルやカラムが存在しない場合、作成する。
	 * 
	 * @param qr
	 *            QueryRunner
	 * @throws SQLException
	 */
	public void validate(QueryRunner qr) throws SQLException {
		
		for(Table table : tables) {
			List<Column> pk = new ArrayList<Column>();
			if (qr.query("SELECT * FROM sqlite_master WHERE name = ? and type='table';", new MapListHandler(), table.getName())
					.size() == 0) {
				StringBuilder sql = new StringBuilder("CREATE TABLE [" + table.getName() + "] (");
				boolean comma = false;
				for (Column column : table.getColumn()) {
					sql.append(comma ? "," : "").append('[').append(column.getName()).append("] ").append(column.getType())
							.append(column.getNotnull() == 1 ? " NOT NULL" : "").append(column.getDefaultval() != null && column.getDefaultval().length() > 0 ? " DEFAULT " + column.getDefaultval() : "");
					comma = true;
					if (column.getPk() == 1) {
						pk.add(column);
					}
				}

				if (pk.size() > 0) {
					sql.append(",PRIMARY KEY(");
					comma = false;
					for (Column column : pk) {
						sql.append(comma ? "," : "").append(column.getName());
						comma = true;
					}
					sql.append(")");
				}
				sql.append(");");
				qr.update(sql.toString());
			}

			List<Column> adds = new ArrayList<Column>(Arrays.asList(table.getColumn()));
			for (Column songcolumn : qr.query("PRAGMA table_info('" + table.getName() + "');",
					columnhandler)) {
				final String name = (String) songcolumn.getName();
				for (int i = 0; i < adds.size(); i++) {
					if (adds.get(i).getName().equals(name)) {
						adds.remove(i);
						break;
					}
				}
			}
			for (Column add : adds) {
				qr.update("ALTER TABLE " + table.getName() + " ADD COLUMN [" + add.getName() + "] " + add.getType()
						+ (add.getNotnull() == 1 ? " NOT NULL" : "") + (add.getDefaultval() != null && add.getDefaultval().length() > 0 ? " DEFAULT " + add.getDefaultval() : ""));
			}			
		}

	}

	protected void insert(QueryRunner qr, String tablename,
			Object entity) throws SQLException {
		insert(qr, null, tablename, entity);
	}

	protected void insert(QueryRunner qr, Connection con, String tablename,
			Object entity) throws SQLException {
		Column[] columns = null;
		for(Table table : tables) {
			if(table.getName().equals(tablename)) {
				columns = table.getColumn();
				break;
			}
		}
		if(columns == null) {
			return;
		}
		
		StringBuilder sql = new StringBuilder("INSERT OR REPLACE INTO " + tablename + " (");
		boolean comma = false;
		for (Column column : columns) {
			sql.append(comma ? "," : "").append(column.getName());
			comma = true;
		}
		sql.append(") VALUES(");

		Object[] params = new Object[columns.length];
		comma = false;
		for (int i = 0; i < columns.length; i++) {
			sql.append(comma ? ",?" : "?");
			comma = true;

			PropertyDescriptor pd;
			try {
				pd = new PropertyDescriptor((String)columns[i].getName(), entity.getClass());
				Method getterMethod = pd.getReadMethod();
				params[i] = getterMethod.invoke(entity);
			} catch (IntrospectionException | ReflectiveOperationException | IllegalArgumentException e) {
				e.printStackTrace();
			}
		}
		sql.append(");");

		if(con != null) {
			qr.update(con, sql.toString(), params);
		} else {
			qr.update(sql.toString(), params);			
		}
	}
	
	/**
	 * SQLiteテーブル
	 * 
	 * @author exch
	 */
	public static class Table {

		/**
		 * テーブル名
		 */
		private String name;
		
		/**
		 * カラム
		 */
		private Column[] column;
		
		public Table(String name, Column... column) {
			this.name = name;
			this.column = column;
		}

		public String getName() {
			return name;
		}

		public void setName(String name) {
			this.name = name;
		}

		public Column[] getColumn() {
			return column;
		}

		public void setColumn(Column[] column) {
			this.column = column;
		}
	}

	/**
	 * SQLiteカラム
	 * 
	 * @author exch
	 */
	public static class Column {

		/**
		 * カラム名
		 */
		private String name;
		/**
		 * 値の型式
		 */
		private String type;

		/**
		 * NOT NULL = 1
		 */
		private int notnull;

		/**
		 * PRIMAL KEY = 1
		 */
		private int pk;
		
		private String defaultval;
		
		public Column() {
			
		}

		public Column(String name, String type) {
			this(name, type, 0, 0);
		}

		public Column(String name, String type, int notnull, int pk) {
			this.name = name;
			this.type = type;
			this.notnull = notnull;
			this.pk = pk;
		}
		
		public Column(String name, String type, int notnull, int pk, String defaultval) {
			this.name = name;
			this.type = type;
			this.notnull = notnull;
			this.pk = pk;
			this.setDefaultval(defaultval);
		}
		
		public String getName() {
			return name;
		}
		
		public void setName(String name) {
			this.name = name;
		}
		
		public String getType() {
			return type;
		}
		
		public void setType(String type) {
			this.type = type;
		}
		
		public int getNotnull() {
			return notnull;
		}
		
		public void setNotnull(int notnull) {
			this.notnull = notnull;
		}
		
		public int getPk() {
			return pk;
		}
		
		public void setPk(int pk) {
			this.pk = pk;
		}

		public String getDefaultval() {
			return defaultval;
		}

		public void setDefaultval(String defaultval) {
			this.defaultval = defaultval;
		}
	}	
}
