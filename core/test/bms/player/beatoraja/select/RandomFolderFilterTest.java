package bms.player.beatoraja.select;

import bms.player.beatoraja.ScoreData;
import bms.player.beatoraja.select.BarManager.RandomFolder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ランダムフォルダの複合フィルタ評価に関する単体テスト
 */
class RandomFolderFilterTest {

    @Test
    @DisplayName("整数条件が先頭にあり一致した場合でも後続のフィルタ条件がすべて評価されること")
    void testIntegerConditionDoesNotShortCircuitSubsequentConditions() {
        RandomFolder folder = new RandomFolder();
        Map<String, Object> filter = new LinkedHashMap<>();
        // 先頭の整数条件 (clear == 5) の後に文字列比較条件 (epg >= 100) と整数条件 (minbp == 0) を配置
        filter.put("clear", 5);
        filter.put("epg", ">=100");
        filter.put("minbp", 0);
        folder.setFilter(filter);

        // clear のみ一致し、後続の epg 条件を満たさないスコアは false になること
        ScoreData partialMatch = new ScoreData();
        partialMatch.setClear(5);
        partialMatch.setEpg(50);
        partialMatch.setMinbp(0);
        assertFalse(folder.filterSong(partialMatch));

        // clear と epg は一致するが、最後の整数条件 minbp を満たさないスコアは false になること
        ScoreData secondIntMismatch = new ScoreData();
        secondIntMismatch.setClear(5);
        secondIntMismatch.setEpg(120);
        secondIntMismatch.setMinbp(3);
        assertFalse(folder.filterSong(secondIntMismatch));

        // すべての条件を満たすスコアのみ true になること
        ScoreData fullMatch = new ScoreData();
        fullMatch.setClear(5);
        fullMatch.setEpg(120);
        fullMatch.setMinbp(0);
        assertTrue(folder.filterSong(fullMatch));
    }

    @Test
    @DisplayName("未プレイ譜面 (scoreData == null) で先頭の整数条件 0 に一致した場合でも後続条件が評価されること")
    void testNullScoreDataEvaluatesAllConditions() {
        RandomFolder folder = new RandomFolder();
        Map<String, Object> filter = new LinkedHashMap<>();
        filter.put("clear", 0);
        filter.put("playcount", ">=1");
        folder.setFilter(filter);

        // clear == 0 は未プレイでも満たすが、playcount >= 1 を満たさないため false になること
        assertFalse(folder.filterSong(null));

        Map<String, Object> unplayedFilter = new LinkedHashMap<>();
        unplayedFilter.put("clear", 0);
        unplayedFilter.put("playcount", "<1");
        folder.setFilter(unplayedFilter);
        assertTrue(folder.filterSong(null));
    }
}
