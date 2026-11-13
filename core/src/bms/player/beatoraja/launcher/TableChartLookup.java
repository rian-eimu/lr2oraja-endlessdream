package bms.player.beatoraja.launcher;

import bms.player.beatoraja.song.SongData;
import org.apache.commons.dbutils.QueryRunner;
import org.apache.commons.dbutils.handlers.BeanListHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import java.sql.SQLException;

/** ゲーム側のデータベースをマイグレーション・更新せずに譜面を検索する読み取り専用ルックアップ。 */
final class TableChartLookup {
    private static final Logger logger = LoggerFactory.getLogger(TableChartLookup.class);
    private final QueryRunner query;
    private final BeanListHandler<SongData> handler = new BeanListHandler<>(SongData.class);

    TableChartLookup(String path) {
        var config = new SQLiteConfig();
        config.setReadOnly(true);
        config.setBusyTimeout(250);
        var source = new SQLiteDataSource(config);
        source.setUrl("jdbc:sqlite:" + path);
        query = new QueryRunner(source);
    }

    SongData[] byHash(String hash) {
        if (hash == null || !TableEditorView.isMd5OrSha256Hash(hash)) return SongData.EMPTY;
        return find(hash.length() == 32 ? "SELECT * FROM song WHERE md5 = ?" : "SELECT * FROM song WHERE sha256 = ?", hash);
    }

    SongData[] byText(String text) {
        return find("SELECT * FROM song WHERE rtrim(title||' '||subtitle||' '||artist||' '||subartist||' '||genre) LIKE ?"
                + " GROUP BY sha256", "%" + text + "%");
    }

    private SongData[] find(String sql, String parameter) {
        try {
            return query.query(sql, handler, parameter).toArray(SongData[]::new);
        } catch (SQLException e) {
            logger.warn("Table editor chart search failed: {}", e.getClass().getSimpleName());
            return SongData.EMPTY;
        }
    }
}

