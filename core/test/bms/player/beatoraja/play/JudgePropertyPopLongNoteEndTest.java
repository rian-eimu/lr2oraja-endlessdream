package bms.player.beatoraja.play;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class JudgePropertyPopLongNoteEndTest {

    private static final int[] RATE_100 = {100, 100, 100};

    @Test
    @DisplayName("POP判定のLN終端はEARLY側のみ250msまで広く、LATE側は通常の判定幅のまま")
    void popLongNoteEndWindowIsWideOnlyOnEarlySide() {
        final long[][] judge = JudgeProperty.POP.getJudge(JudgeProperty.NoteType.LONGNOTE_END, 100, RATE_100);

        final long[][] expected = {
                {-116667, 281667},
                {-116667, 281667},
                {-116667, 281667},
                {-183333, 366667},
        };
        for (int i = 0; i < expected.length; i++) {
            assertArrayEquals(expected[i], judge[i], "judge index " + i);
        }
    }

    @Test
    @DisplayName("POP判定のLN終端の判定幅は#RANKによらず固定")
    void popLongNoteEndWindowIgnoresJudgeRank() {
        final long[][] normal = JudgeProperty.POP.getJudge(JudgeProperty.NoteType.LONGNOTE_END, 100, RATE_100);
        for (int rank : new int[]{25, 50, 75, 125}) {
            final long[][] judge = JudgeProperty.POP.getJudge(JudgeProperty.NoteType.LONGNOTE_END, rank, RATE_100);
            for (int i = 0; i < normal.length; i++) {
                assertArrayEquals(normal[i], judge[i], "rank " + rank + " judge index " + i);
            }
        }
    }
}
