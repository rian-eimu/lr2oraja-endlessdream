package bms.player.beatoraja.play;

import bms.player.beatoraja.play.PracticeConfiguration.PracticeElement;
import bms.player.beatoraja.play.PracticeConfiguration.PracticeProperty;
import com.badlogic.gdx.utils.Json;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PracticeConfiguration の SCROLL MODIFY MODE 項目の単体テスト
 */
class PracticeConfigurationTest {

    private PracticeConfiguration practice;

    @BeforeEach
    void setUp() {
        practice = new PracticeConfiguration();
    }

    @Test
    @DisplayName("順送り時に 0(OFF) -> 1(REMOVE) -> 2(ADD) -> 0(OFF) と循環すること")
    void testScrollModeForwardCycle() {
        int[] expected = {1, 2, 0};
        for (int exp : expected) {
            PracticeElement.SCROLLMODE.action.accept(practice, true);
            assertEquals(exp, practice.getPracticeProperty().scrollmode);
        }
    }

    @Test
    @DisplayName("逆送り時に 0(OFF) -> 2(ADD) -> 1(REMOVE) -> 0(OFF) と逆順循環すること")
    void testScrollModeBackwardCycle() {
        int[] expected = {2, 1, 0};
        for (int exp : expected) {
            PracticeElement.SCROLLMODE.action.accept(practice, false);
            assertEquals(exp, practice.getPracticeProperty().scrollmode);
        }
    }

    @Test
    @DisplayName("設定値に対応した表示文字列になること")
    void testScrollModeText() {
        String[] expected = {"SCROLL MODIFY MODE : OFF", "SCROLL MODIFY MODE : REMOVE", "SCROLL MODIFY MODE : ADD"};
        for (int i = 0; i < expected.length; i++) {
            practice.getPracticeProperty().scrollmode = i;
            assertEquals(expected[i], PracticeElement.SCROLLMODE.text.apply(practice));
        }
    }

    @Test
    @DisplayName("scrollmode を含まない既存の練習設定ファイルを読み込むと OFF になること")
    void testLoadPropertyWithoutScrollMode() {
        String saved = "{\nstarttime: 30000\nendtime: 90000\ngaugecategory: LR2\ngaugetype: 3\njudgerank: 100\ntotal: 300\n}";
        PracticeProperty property = new Json().fromJson(PracticeProperty.class, saved);
        assertEquals(0, property.scrollmode);
        assertEquals(30000, property.starttime);
    }

    @Test
    @DisplayName("OFF のときは保存内容に scrollmode が出力されないこと")
    void testScrollModeOffIsNotWritten() {
        PracticeProperty property = new PracticeProperty();
        assertFalse(new Json().prettyPrint(property).contains("scrollmode"));
        property.scrollmode = 1;
        assertTrue(new Json().prettyPrint(property).contains("scrollmode"));
    }
}
