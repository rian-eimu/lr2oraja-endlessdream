package bms.player.beatoraja.select.bar;

import java.io.File;
import java.util.LinkedHashSet;
import java.util.Set;

import bms.model.Mode;
import bms.player.beatoraja.PlayerConfig;

import bms.player.beatoraja.MainController;
import bms.player.beatoraja.select.MusicSelector;

/**
 * SQLで問い合わせた楽曲を表示するためのバー
 *
 * @author exch
 */
public class CommandBar extends DirectoryBar {

	/**
	 * バータイトル
	 */
    private final String title;
    /**
     * DBに対するSQL
     */
    private final String sql;

    public CommandBar(MusicSelector selector, String title, String sql) {
    	this(selector, title, sql, false);
    }

    public CommandBar(MusicSelector selector, String title, String sql, boolean showInvisibleChart) {
    	super(selector, showInvisibleChart);
        this.title = title;
        this.sql = sql;
    }

    @Override
    public String getTitle() {
        return title;
    }

    @Override
    public Bar[] getChildren() {
    	final MainController main = selector.main;
        return SongBar.toSongBarArray(main.getSongDatabase().getSongDatas(getEffectiveSql(main),main.getConfig().getPlayerpath() + File.separatorChar + main.getConfig().getPlayername() + "/score.db"
        		,main.getConfig().getPlayerpath() + File.separatorChar + main.getConfig().getPlayername() + "/scorelog.db",main.getInfoDatabase() != null ? "songinfo.db" : null));
    }

    public void updateFolderStatus() {
    	final MainController main = selector.main;
        updateFolderStatus(main.getSongDatabase().getSongDatas(getEffectiveSql(main),main.getConfig().getPlayerpath() + File.separatorChar + main.getConfig().getPlayername() + "/score.db"
        		,main.getConfig().getPlayerpath() + File.separatorChar + main.getConfig().getPlayername() + "/scorelog.db",main.getInfoDatabase() != null ? "songinfo.db" : null));
    }

    /**
     * song.mode に応じた LN MODE を返す SQL 式を生成する
     */
    static String lnmodeSqlExpression(PlayerConfig config) {
        StringBuilder sb = new StringBuilder("(CASE song.mode");
        Set<Integer> ids = new LinkedHashSet<>();
        for (Mode mode : Mode.values()) {
            if (ids.add(mode.id)) {
                sb.append(" WHEN ").append(mode.id).append(" THEN ").append(config.getLnmode(mode.id));
            }
        }
        sb.append(" ELSE ").append(config.getLnmode(Mode.BEAT_7K)).append(" END)");
        return sb.toString();
    }

    private String getEffectiveSql(MainController main) {
        String effectiveSql = sql;
        if (effectiveSql.contains("score.") || effectiveSql.contains("scorelog.")) {
            // LN MODE は KEY モード毎の設定のため、楽曲のモード (song.mode) 毎に値を切り替える
            String lnmode = lnmodeSqlExpression(main.getPlayerConfig());
            
            String upper = effectiveSql.toUpperCase();
            int splitIdx = effectiveSql.length();
            int orderByIdx = upper.indexOf("ORDER BY");
            int limitIdx = upper.indexOf("LIMIT");
            if (orderByIdx >= 0) {
                splitIdx = orderByIdx;
            } else if (limitIdx >= 0) {
                splitIdx = limitIdx;
            }
            String condition = effectiveSql.substring(0, splitIdx).trim();
            String orderLimit = effectiveSql.substring(splitIdx).trim();
            
            condition = "(" + condition + ") AND score.mode = (CASE WHEN (song.feature & 1) != 0 THEN " + lnmode + " ELSE 0 END)";
            if (sql.contains("scorelog.")) {
                condition += " AND (scorelog.sha256 IS NULL OR scorelog.mode = (CASE WHEN (song.feature & 1) != 0 THEN " + lnmode + " ELSE 0 END))";
            }
            effectiveSql = condition + " " + orderLimit;
        }
        return effectiveSql;
    }
}
