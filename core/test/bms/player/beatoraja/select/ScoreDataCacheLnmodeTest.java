package bms.player.beatoraja.select;

import bms.player.beatoraja.ScoreData;
import bms.player.beatoraja.ScoreDatabaseAccessor.ScoreDataCollector;
import bms.player.beatoraja.song.SongData;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * KEY モード毎の LN MODE で楽曲毎にスコアを取得する ScoreDataCache の単体テスト
 */
class ScoreDataCacheLnmodeTest {

    private static SongData song(String sha256, int mode) {
        SongData song = new SongData();
        song.setSha256(sha256);
        song.setMode(mode);
        song.setFeature(SongData.FEATURE_UNDEFINEDLN);
        return song;
    }

    @Test
    void readScoreDatasQueriesEachSongWithItsOwnLnmode() {
        // 楽曲 sha256 -> 取得に使われた lnmode
        Map<String, Integer> requested = new HashMap<>();
        List<Integer> batches = new ArrayList<>();
        ScoreDataCache cache = new ScoreDataCache() {
            @Override
            protected ScoreData readScoreDatasFromSource(SongData song, int lnmode) {
                requested.put(song.getSha256(), lnmode);
                return null;
            }

            @Override
            protected void readScoreDatasFromSource(ScoreDataCollector collector, SongData[] songs, int lnmode) {
                batches.add(lnmode);
                for (SongData song : songs) {
                    requested.put(song.getSha256(), lnmode);
                    ScoreData score = new ScoreData();
                    score.setMode(lnmode);
                    collector.collect(song, score);
                }
            }
        };

        SongData a7 = song("a", 7);
        SongData b14 = song("b", 14);
        SongData c7 = song("c", 7);
        SongData d9 = song("d", 9);
        Map<Integer, Integer> lnmodeByMode = Map.of(7, 1, 14, 2, 9, 0);

        Map<String, Integer> collected = new HashMap<>();
        cache.readScoreDatas((s, score) -> collected.put(s.getSha256(), score.getMode()),
                new SongData[] { a7, b14, c7, d9 }, s -> lnmodeByMode.get(s.getMode()));

        assertEquals(Map.of("a", 1, "b", 2, "c", 1, "d", 0), requested);
        assertEquals(Map.of("a", 1, "b", 2, "c", 1, "d", 0), collected);
        // LN MODE 毎に 1 回ずつまとめて取得する
        assertEquals(List.of(0, 1, 2), batches);

        // 取得結果は LN MODE 毎のキャッシュに格納される
        assertTrue(cache.existsScoreDataCache(a7, 1));
        assertFalse(cache.existsScoreDataCache(a7, 2));
        assertEquals(2, cache.readScoreData(b14, 2).getMode());
    }
}
