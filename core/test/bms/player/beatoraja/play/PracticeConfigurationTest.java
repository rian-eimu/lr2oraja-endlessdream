package bms.player.beatoraja.play;

import bms.model.BMSModel;
import bms.model.Mode;
import bms.model.NormalNote;
import bms.model.TimeLine;
import bms.player.beatoraja.Config;
import bms.player.beatoraja.PlayerConfig;
import bms.player.beatoraja.input.KeyBoardInputProcesseor.ControlKeys;
import bms.player.beatoraja.pattern.ExtraNoteModifier;
import bms.player.beatoraja.pattern.PracticeModifier;
import bms.player.beatoraja.play.PracticeConfiguration.PracticeElement;
import bms.player.beatoraja.play.PracticeConfiguration.PracticeInput;
import bms.player.beatoraja.play.PracticeConfiguration.PracticeProperty;
import com.badlogic.gdx.utils.Json;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PracticeConfiguration の項目操作・DP 2Pコントローラー入力・設定ファイル互換性の単体テスト
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

    @Test
    @DisplayName("破損・空の練習設定ファイルを読み込んだ場合は null を返すこと")
    void testLoadCorruptPracticeProperty() throws IOException {
        Path directory = Files.createTempDirectory("practice-configuration-test");
        try {
            Path nullProperty = directory.resolve("null.json");
            Files.writeString(nullProperty, "null");
            assertNull(PracticeConfiguration.loadPracticeProperty(nullProperty));

            Path invalidProperty = directory.resolve("invalid.json");
            Files.writeString(invalidProperty, "{");
            assertNull(PracticeConfiguration.loadPracticeProperty(invalidProperty));

            assertNull(PracticeConfiguration.loadPracticeProperty(directory.resolve("directory.json")));
        } finally {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    @Test
    @DisplayName("EXTRA NOTE が通常操作で1刻み・TURBO操作で5刻みに増減し範囲内にクランプされること")
    void testExtraNoteAdjustmentAndClamping() {
        assertEquals("EXTRA NOTE : OFF", PracticeElement.EXTRANOTE.text.apply(practice));

        PracticeElement.EXTRANOTE.action.run(practice, true, false, false);
        assertEquals(1, practice.getPracticeProperty().extranotedepth);
        assertEquals("EXTRA NOTE : 1", PracticeElement.EXTRANOTE.text.apply(practice));

        PracticeElement.EXTRANOTE.action.run(practice, true, false, true);
        assertEquals(6, practice.getPracticeProperty().extranotedepth);

        practice.getPracticeProperty().extranotedepth = PlayerConfig.EXTRA_NOTE_DEPTH_MAX;
        PracticeElement.EXTRANOTE.action.run(practice, true, false, true);
        assertEquals(PlayerConfig.EXTRA_NOTE_DEPTH_MAX, practice.getPracticeProperty().extranotedepth);

        practice.getPracticeProperty().extranotedepth = 0;
        PracticeElement.EXTRANOTE.action.run(practice, false, false, false);
        assertEquals(PlayerConfig.EXTRA_NOTE_DEPTH_MIN, practice.getPracticeProperty().extranotedepth);
    }

    @Test
    @DisplayName("EXTRA NOTE が OFF(0) のとき既存 JSON と互換性を保つこと")
    void testExtraNoteJsonCompatibility() {
        String saved = "{\nstarttime: 10000\nendtime: 60000\n}";
        PracticeProperty loaded = new Json().fromJson(PracticeProperty.class, saved);
        assertEquals(0, loaded.extranotedepth);

        PracticeProperty property = new PracticeProperty();
        assertFalse(new Json().prettyPrint(property).contains("extranotedepth"));
        property.extranotedepth = 2;
        assertTrue(new Json().prettyPrint(property).contains("extranotedepth"));
    }

    @Test
    @DisplayName("練習開始順序で ExtraNoteModifier 適用後に PracticeModifier を適用すると区間外に追加ノーツが残らないこと")
    void testExtraNoteBeforePracticeModifierKeepsOutsideRangeInBackground() {
        BMSModel model = createPracticeTestModel(Mode.BEAT_7K);
        new ExtraNoteModifier(0, 1, false).modify(model);
        new PracticeModifier(4000, 7000, 2).modify(model);

        TimeLine[] tls = model.getAllTimeLines();
        assertFalse(tls[0].existNote(), "開始時間より前の追加ノーツはBGへ移動されること");
        assertEquals(2, countPlayableNotes(tls[1], Mode.BEAT_7K.key), "練習区間内には元ノーツ+ExtraNoteが配置されること");
        assertFalse(tls[2].existNote(), "終了時間以降の追加ノーツはBGへ移動されること");
    }

    @Test
    @DisplayName("DP 14K での 2P 側コントローラー操作が説明どおり SCR=上下, 2+SCR=左右, 4=TURBO, 1KEY=開始 となること")
    void testDp2PControllerKeyAssignInBeat14K() {
        BMSModel model = createPracticeTestModel(Mode.BEAT_14K);
        practice.create(model, new Config());
        FakePracticeInput input = new FakePracticeInput();

        // 1P 1KEY(0) と 2P 1KEY(9) のみがプレイ開始キーとして判定されること
        assertTrue(PracticeConfiguration.isPlayKeyPressed(k -> k == 0, Mode.BEAT_14K));
        assertTrue(PracticeConfiguration.isPlayKeyPressed(k -> k == 9, Mode.BEAT_14K));
        assertFalse(PracticeConfiguration.isPlayKeyPressed(k -> k == 10, Mode.BEAT_14K));
        assertFalse(PracticeConfiguration.isPlayKeyPressed(k -> k == 15, Mode.BEAT_14K));

        // 2P の 1KEY(9), 3KEY(11), 5KEY(13), 6KEY(14), 7KEY(15) 単体ではカーソル移動や値変更が起きないこと
        for (int key : new int[]{9, 11, 13, 14, 15}) {
            input.clear();
            input.keyStates[key] = true;
            practice.processInput(input);
            assertEquals(0, practice.getCursorPosition(), "key=" + key + " でカーソルが動かないこと");
            assertEquals(0, practice.getPracticeProperty().starttime, "key=" + key + " で値が変わらないこと");
        }

        // 2P 皿単体: 下回転(16)でカーソル下移動、上回転(17)でカーソル上移動
        input.clear();
        input.keyStates[16] = true;
        practice.processInput(input);
        assertEquals(1, practice.getCursorPosition());

        input.clear();
        input.keyStates[17] = true;
        practice.processInput(input);
        assertEquals(0, practice.getCursorPosition());

        // 2P 2KEY(10) + 2P 皿上(17): 横入力モードになり START TIME が +100ms 増加すること（カーソル位置は維持）
        input.clear();
        input.keyStates[10] = true;
        input.keyStates[17] = true;
        practice.processInput(input);
        assertTrue(practice.isHorizontalInputMode());
        assertEquals(0, practice.getCursorPosition());
        assertEquals(100, practice.getPracticeProperty().starttime);

        // 2P 2KEY(10) + アナログ2P皿(17): アナログ入力でも値が増減すること
        input.clear();
        input.keyStates[10] = true;
        input.analog[17] = true;
        input.analogDiff[17] = 2;
        practice.processInput(input);
        assertEquals(300, practice.getPracticeProperty().starttime);

        // 2P 4KEY(12) + 2P 皿上(17): TURBO 有効かつ横入力モードになり +2500ms 増加すること
        input.clear();
        input.keyStates[12] = true;
        input.keyStates[17] = true;
        practice.processInput(input);
        assertTrue(practice.isInputTurbo());
        assertTrue(practice.isHorizontalInputMode());
        assertEquals(2800, practice.getPracticeProperty().starttime);
    }

    @Test
    @DisplayName("DP 10K でも 2P 側コントローラーが SCR=上下, 2+SCR=左右, 4=TURBO, 1KEY=開始 となること")
    void testDp2PControllerKeyAssignInBeat10K() {
        BMSModel model = createPracticeTestModel(Mode.BEAT_10K);
        practice.create(model, new Config());
        FakePracticeInput input = new FakePracticeInput();

        assertTrue(PracticeConfiguration.isPlayKeyPressed(k -> k == 0, Mode.BEAT_10K));
        assertTrue(PracticeConfiguration.isPlayKeyPressed(k -> k == 7, Mode.BEAT_10K));
        assertFalse(PracticeConfiguration.isPlayKeyPressed(k -> k == 8, Mode.BEAT_10K));

        // 2P 1KEY(7), 3KEY(9), 5KEY(11) 単体ではカーソル移動や値変更が起きないこと
        for (int key : new int[]{7, 9, 11}) {
            input.clear();
            input.keyStates[key] = true;
            practice.processInput(input);
            assertEquals(0, practice.getCursorPosition());
            assertEquals(0, practice.getPracticeProperty().starttime);
        }

        // 2P 皿下(12) -> カーソル下移動、2P 皿上(13) -> カーソル上移動
        input.clear();
        input.keyStates[12] = true;
        practice.processInput(input);
        assertEquals(1, practice.getCursorPosition());

        input.clear();
        input.keyStates[13] = true;
        practice.processInput(input);
        assertEquals(0, practice.getCursorPosition());

        // 2P 2KEY(8) + 2P 皿上(13) -> +100ms, 2P 4KEY(10) + 2P 皿上(13) -> TURBO +2500ms
        input.clear();
        input.keyStates[8] = true;
        input.keyStates[13] = true;
        practice.processInput(input);
        assertEquals(100, practice.getPracticeProperty().starttime);

        input.clear();
        practice.processInput(input);
        input.keyStates[10] = true;
        input.keyStates[13] = true;
        practice.processInput(input);
        assertTrue(practice.isInputTurbo());
        assertEquals(2600, practice.getPracticeProperty().starttime);
    }

    private static BMSModel createPracticeTestModel(Mode mode) {
        BMSModel model = new BMSModel();
        model.setMode(mode);
        model.setJudgerank(100);
        model.setTotal(300);

        TimeLine tl1 = new TimeLine(0, 1_000_000L, mode.key);
        tl1.setNote(0, new NormalNote(1));
        TimeLine tl2 = new TimeLine(1, 5_000_000L, mode.key);
        tl2.setNote(0, new NormalNote(1));
        TimeLine tl3 = new TimeLine(2, 12_000_000L, mode.key);
        tl3.setNote(0, new NormalNote(1));
        model.setAllTimeLine(new TimeLine[]{tl1, tl2, tl3});
        return model;
    }

    private static int countPlayableNotes(TimeLine tl, int keyCount) {
        int count = 0;
        for (int i = 0; i < keyCount; i++) {
            if (tl.getNote(i) != null) {
                count++;
            }
        }
        return count;
    }

    private static final class FakePracticeInput implements PracticeInput {
        final boolean[] keyStates = new boolean[64];
        final boolean[] analog = new boolean[64];
        final int[] analogDiff = new int[64];

        void clear() {
            java.util.Arrays.fill(keyStates, false);
            java.util.Arrays.fill(analog, false);
            java.util.Arrays.fill(analogDiff, 0);
        }

        @Override
        public boolean getControlKeyState(ControlKeys key) {
            return false;
        }

        @Override
        public boolean getKeyState(int index) {
            return index >= 0 && index < keyStates.length && keyStates[index];
        }

        @Override
        public boolean isAnalogInput(int index) {
            return index >= 0 && index < analog.length && analog[index];
        }

        @Override
        public void resetAnalogInput(int index) {
            if (index >= 0 && index < analogDiff.length) {
                analogDiff[index] = 0;
            }
        }

        @Override
        public int getAnalogDiffAndReset(int index, int msTolerance) {
            if (index < 0 || index >= analogDiff.length) {
                return 0;
            }
            int diff = analogDiff[index];
            analogDiff[index] = 0;
            return diff;
        }
    }
}
