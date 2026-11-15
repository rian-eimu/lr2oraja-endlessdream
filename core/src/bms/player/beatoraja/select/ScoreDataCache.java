package bms.player.beatoraja.select;

import bms.player.beatoraja.ScoreData;
import bms.player.beatoraja.ScoreDatabaseAccessor.ScoreDataCollector;
import bms.player.beatoraja.song.SongData;
import com.badlogic.gdx.utils.*;

import javax.annotation.Nullable;
import java.util.function.ToIntFunction;

/**
 * スコアデータのキャッシュ
 *
 * @author exch
 */
public abstract class ScoreDataCache {

    // TODO ResourcePoolベースに移行する

    /**
     * スコアデータのキャッシュ
     */
    private ObjectMap<String, ScoreData>[] scorecache;

    public ScoreDataCache() {
        scorecache = new ObjectMap[5];
        for (int i = 0; i < scorecache.length; i++) {
            scorecache[i] = new ObjectMap(2000);
        }
    }

    /**
     * 指定した楽曲データ、LN MODEに対するスコアデータを返す
     * @param song 楽曲データ
     * @param lnmode LN MODE
     * @return スコアデータ。存在しない場合はnull
     */
    @Nullable
    public ScoreData readScoreData(SongData song, int lnmode) {
        final int cacheindex = song.hasAnyLongNote() ? lnmode : 4;
        if (scorecache[cacheindex].containsKey(song.getSha256())) {
            return scorecache[cacheindex].get(song.getSha256());
        }
        ScoreData score = readScoreDatasFromSource(song, lnmode);
        scorecache[cacheindex].put(song.getSha256(), score);
        return score;
    }

    /**
     *
     * @param collector
     * @param songs
     * @param lnmode
     */
    public void readScoreDatas(ScoreDataCollector collector, SongData[] songs, int lnmode) {
        // キャッシュからの抽出
        Array<SongData> noscore = null;
        for (SongData song : songs) {
            final int cacheindex = song.hasAnyLongNote() ? lnmode : 4;

            if (scorecache[cacheindex].containsKey(song.getSha256())) {
                collector.collect(song, scorecache[cacheindex].get(song.getSha256()));
            } else {
            	if(noscore == null) {
            		noscore = new Array<SongData>();
            	}
                noscore.add(song);
            }
        }

        if(noscore == null) {
            return;
        }
        // キャッシュに存在しなかったスコアデータをキャッシュに登録
        final SongData[] noscores = noscore.toArray(SongData.class);

        final ScoreDataCollector cachecollector = (song, score) -> {
            final int cacheindex = song.hasAnyLongNote() ? lnmode : 4;
            scorecache[cacheindex].put(song.getSha256(), score);
        	collector.collect(song, score);
        };
        readScoreDatasFromSource(cachecollector, noscores, lnmode);
    }

    /**
     * 楽曲毎に LN MODE が異なる場合のスコアデータ一括取得。
     * LN MODE は KEY モード毎に設定されるため、フォルダ内でモードが混在する場合に使用する。
     * LN MODE 毎にまとめて {@link #readScoreDatas(ScoreDataCollector, SongData[], int)} を呼び出すため、
     * collector の呼び出し順は songs の順序と一致しない。
     *
     * @param collector スコアデータの収集先
     * @param songs 楽曲データ
     * @param lnmodeOf 楽曲データから LN MODE を返す関数
     */
    public void readScoreDatas(ScoreDataCollector collector, SongData[] songs, ToIntFunction<SongData> lnmodeOf) {
        final int lnmodes = scorecache.length - 1;
        final Array<SongData>[] groups = new Array[lnmodes];
        for (SongData song : songs) {
            final int lnmode = Math.max(0, Math.min(lnmodes - 1, lnmodeOf.applyAsInt(song)));
            if (groups[lnmode] == null) {
                groups[lnmode] = new Array<SongData>();
            }
            groups[lnmode].add(song);
        }
        for (int lnmode = 0; lnmode < lnmodes; lnmode++) {
            if (groups[lnmode] != null) {
                readScoreDatas(collector, groups[lnmode].toArray(SongData.class), lnmode);
            }
        }
    }

    boolean existsScoreDataCache(SongData song, int lnmode) {
        final int cacheindex = song.hasAnyLongNote() ? lnmode : 4;
        return scorecache[cacheindex].containsKey(song.getSha256());
    }

    public void clear() {
        for (ObjectMap<?, ?> cache : scorecache) {
            cache.clear();
        }
    }

    public void update(SongData song, int lnmode) {
        final int cacheindex = song.hasAnyLongNote() ? lnmode : 4;
        ScoreData score = readScoreDatasFromSource(song, lnmode);
        scorecache[cacheindex].put(song.getSha256(), score);
    }

    protected abstract ScoreData readScoreDatasFromSource(SongData songs, int lnmode);

    protected abstract void readScoreDatasFromSource(ScoreDataCollector collector, SongData[] songs, int lnmode);
}
