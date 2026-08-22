package bms.player.beatoraja.select.bar;

import java.io.File;

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

    private String getEffectiveSql(MainController main) {
        String effectiveSql = sql;
        if (effectiveSql.contains("score.") || effectiveSql.contains("scorelog.")) {
            int lnmode = main.getPlayerConfig().getLnmode();
            
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
