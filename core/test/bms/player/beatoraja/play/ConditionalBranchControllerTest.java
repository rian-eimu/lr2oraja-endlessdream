package bms.player.beatoraja.play;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import bms.model.BMSDecoder;
import bms.model.BMSModel;
import bms.model.ConditionalBlock.Variable;
import bms.model.LongNote;
import bms.model.NormalNote;
import bms.model.Note;
import bms.model.TimeLine;
import bms.player.beatoraja.ScoreData;
import bms.player.beatoraja.ScoreDataProperty;

public class ConditionalBranchControllerTest {

    @Test
    public void testDynamicBranchEvaluationAndTotalPreservation() {
        String bms = String.join("\r\n",
                "#PLAYER 1",
                "#BPM 120",
                "#TOTAL 350",
                "#WAV01 a.wav",
                "#00111:0101",
                "#CONDITIONAL 2.0",
                "#WHEN MISS >= 5",
                "#00211:01",
                "#ELSEWHEN GAUGE >= 80",
                "#00211:01010101",
                "#DEFAULT",
                "#00211:0101",
                "#ENDCONDITIONAL",
                "");

        BMSDecoder decoder = new BMSDecoder();
        BMSModel model = decoder.decode(bms.getBytes(StandardCharsets.UTF_8), false, null);
        BMSPlayerRule.validate(model);
        assertEquals(4, model.getTotalNotes());
        assertEquals(350.0, model.getTotal(), 1e-6);

        ConditionalBranchController controller = new ConditionalBranchController(
                null, model, null,
                model.getTotalNotes(), model.getTotal(),
                0, false, 0, 100, null);

        Map<Variable, Double> live = new EnumMap<>(Variable.class);
        live.put(Variable.MISS, 1.0);
        live.put(Variable.GAUGE, 84.0);
        controller.setCustomVariableProvider(v -> live.getOrDefault(v, 0.0));

        long evalTime = controller.getNextEvalMicroTime();
        assertTrue(evalTime > 0 && evalTime < Long.MAX_VALUE);

        // 評価時刻より前ではまだ分岐しない
        controller.evaluateUpTo(evalTime - 1);
        assertEquals(4, model.getTotalNotes());
        assertArrayEquals(new int[] { 2 }, controller.getSelectedBranches());

        // 評価時刻到達で ELSEWHEN (GAUGE >= 80, index 1) に分岐し、総ノーツ数が 2 + 4 = 6 になる
        controller.evaluateUpTo(evalTime);
        assertArrayEquals(new int[] { 1 }, controller.getSelectedBranches());
        assertEquals(6, model.getTotalNotes());
        assertEquals(350.0, model.getTotal(), 1e-6);
        assertEquals(Long.MAX_VALUE, controller.getNextEvalMicroTime());
    }

    @Test
    public void testReplayBranchesPriorityAndDefaultFallback() {
        String bms = String.join("\r\n",
                "#PLAYER 1",
                "#BPM 120",
                "#WAV01 a.wav",
                "#CONDITIONAL 1.0",
                "#WHEN SCORE >= 9999",
                "#00111:010101",
                "#DEFAULT",
                "#00111:01",
                "#ENDCONDITIONAL",
                "#CONDITIONAL 2.0",
                "#WHEN SCORE >= 0",
                "#00211:010101",
                "#DEFAULT",
                "#00211:01",
                "#ENDCONDITIONAL",
                "");

        BMSDecoder decoder = new BMSDecoder();
        BMSModel model = decoder.decode(bms.getBytes(StandardCharsets.UTF_8), false, null);
        BMSPlayerRule.validate(model);
        assertEquals(2, model.getTotalNotes());

        // 1つ目のブロックのみリプレイ記録あり(index 0)、2つ目は未記録(長さ1の配列 -> DEFAULTのindex 1へフォールバック)
        ConditionalBranchController controller = new ConditionalBranchController(
                null, model, new int[] { 0 },
                model.getTotalNotes(), model.getTotal(),
                0, false, 0, 100, null);
        controller.setCustomVariableProvider(v -> 0.0);

        controller.evaluateUpTo(Long.MAX_VALUE / 2);
        assertArrayEquals(new int[] { 0, 1 }, controller.getSelectedBranches());
        // 1つ目(3ノーツ) + 2つ目DEFAULT(1ノーツ) = 4ノーツ
        assertEquals(4, model.getTotalNotes());
    }

    @Test
    public void testBoundaryLongNoteAndEarlyJudgedNoteProtection() {
        String bms = String.join("\r\n",
                "#PLAYER 1",
                "#BPM 120",
                "#WAV01 a.wav",
                "#00151:0100",
                "#00251:0001",
                "#CONDITIONAL 2.0",
                "#WHEN AUTO == 1",
                "#00211:01010101",
                "#00212:01000000",
                "#DEFAULT",
                "#00212:01000000",
                "#ENDCONDITIONAL",
                "");

        BMSDecoder decoder = new BMSDecoder();
        BMSModel model = decoder.decode(bms.getBytes(StandardCharsets.UTF_8), false, null);
        BMSPlayerRule.validate(model);

        // 小節1.0で始まり小節2.5で終わるLN(lane 0)を取得
        LongNote originalLnStart = null;
        NormalNote earlyJudgedNote = null;
        for (TimeLine tl : model.getAllTimeLines()) {
            if (Math.abs(tl.getSection() - 1.0) < 1e-6 && tl.getNote(0) instanceof LongNote ln && !ln.isEnd()) {
                originalLnStart = ln;
            }
            if (Math.abs(tl.getSection() - 2.0) < 1e-6 && tl.getNote(1) instanceof NormalNote nn) {
                earlyJudgedNote = nn;
            }
        }
        assertNotNull(originalLnStart);
        assertNotNull(earlyJudgedNote);
        LongNote originalLnEnd = originalLnStart.getPair();
        assertNotNull(originalLnEnd);
        assertEquals(2.5, originalLnEnd.getSection(), 1e-6);

        // 評価直前に小節2.0のlane 1のノーツが早押しで判定済み(state=1)になったと仮定
        earlyJudgedNote.setState(1);

        ConditionalBranchController controller = new ConditionalBranchController(
                null, model, null,
                model.getTotalNotes(), model.getTotal(),
                0, true, 0, 100, null);
        controller.setCustomVariableProvider(v -> v == Variable.AUTO ? 1.0 : 0.0);

        controller.evaluateUpTo(controller.getNextEvalMicroTime());
        assertArrayEquals(new int[] { 0 }, controller.getSelectedBranches());

        // 分岐後の譜面を検証:
        // 1) lane 0 の 2.0, 2.25, 2.5 にあった新分岐の NormalNote は LN区間 [1.0, 2.5] と衝突するため除去され、
        //    2.5 には元の LN終端 (originalLnEnd) が保持され、2.75 の NormalNote のみ残ること
        // 2) lane 1 の 2.0 のノーツは既に判定済みだった earlyJudgedNote インスタンス(state == 1)が保護されていること
        Note noteAt20Lane0 = null;
        Note noteAt225Lane0 = null;
        Note noteAt25Lane0 = null;
        Note noteAt275Lane0 = null;
        Note noteAt20Lane1 = null;
        for (TimeLine tl : model.getAllTimeLines()) {
            if (Math.abs(tl.getSection() - 2.0) < 1e-6) {
                noteAt20Lane0 = tl.getNote(0);
                noteAt20Lane1 = tl.getNote(1);
            } else if (Math.abs(tl.getSection() - 2.25) < 1e-6) {
                noteAt225Lane0 = tl.getNote(0);
            } else if (Math.abs(tl.getSection() - 2.5) < 1e-6) {
                noteAt25Lane0 = tl.getNote(0);
            } else if (Math.abs(tl.getSection() - 2.75) < 1e-6) {
                noteAt275Lane0 = tl.getNote(0);
            }
        }
        assertNull(noteAt20Lane0);
        assertNull(noteAt225Lane0);
        assertSame(originalLnEnd, noteAt25Lane0);
        assertTrue(noteAt275Lane0 instanceof NormalNote);
        assertSame(earlyJudgedNote, noteAt20Lane1);
        assertEquals(1, noteAt20Lane1.getState());
    }

    @Test
    public void testScoreDataPropertyDisablesGhostWhenNotesChange() {
        ScoreDataProperty prop = new ScoreDataProperty();
        prop.setTargetScore(100, new int[] { 0, 0, 0, 0 }, 80, new int[] { 0, 0, 1, 1 }, 4);

        ScoreData score = new ScoreData();
        score.setNotes(4);
        score.setEpg(1);
        prop.update(score, 1);
        assertEquals(2, prop.getNowBestScore());
        assertEquals(2, prop.getNowRivalScore());

        // 動的分岐により総ノーツ数が 4 -> 6 に変化した場合、ゴースト配列参照が安全に無効化され比例計算へフォールバックすること
        score.setNotes(6);
        assertDoesNotThrow(() -> prop.update(score, 5));
    }
}

