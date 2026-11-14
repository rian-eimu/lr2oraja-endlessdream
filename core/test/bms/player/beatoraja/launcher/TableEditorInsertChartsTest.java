package bms.player.beatoraja.launcher;

import bms.player.beatoraja.song.SongData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * ローカル難易度表エディタにおける譜面挿入・重複制御ロジックの単体テスト
 */
class TableEditorInsertChartsTest {

    private static SongData song(String title, String md5, String sha256) {
        SongData data = new SongData();
        data.setTitle(title);
        data.setMd5(md5);
        data.setSha256(sha256);
        return data;
    }

    @Test
    @DisplayName("フォルダへの挿入時は既存譜面および追加リスト内の重複を除外して指定位置へ挿入すること")
    void testInsertIntoFolderDeduplicatesAtDropIndex() {
        SongData a = song("A", "md5-a", "sha-a");
        SongData b = song("B", "md5-b", "sha-b");
        SongData dupA = song("A-dup", "md5-other", "SHA-A");
        SongData c = song("C", "md5-c", "sha-c");
        SongData dupC = song("C-dup", "md5-x", "sha-c");

        List<SongData> target = new ArrayList<>(List.of(a, b));
        TableEditorView.insertCharts(target, List.of(dupA, c, dupC), 1, false);

        assertEquals(List.of(a, c, b), target);
    }

    @Test
    @DisplayName("コースへの挿入時は同一譜面の重複を許容して指定位置へ順番通り挿入すること")
    void testInsertIntoCourseAllowsDuplicates() {
        SongData a = song("A", "md5-a", "sha-a");
        SongData b = song("B", "md5-b", "sha-b");

        List<SongData> target = new ArrayList<>(List.of(a, b));
        TableEditorView.insertCharts(target, List.of(a, b), 1, true);

        assertEquals(List.of(a, a, b, b), target);
    }
}
