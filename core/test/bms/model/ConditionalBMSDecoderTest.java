package bms.model;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import bms.model.ConditionalBlock.Condition;
import bms.model.ConditionalBlock.Operator;
import bms.model.ConditionalBlock.Variable;

public class ConditionalBMSDecoderTest {

    @Test
    public void testBasicConditionalParsingAndDefaultBranch() {
        String bms = String.join("\r\n",
                "#PLAYER 1",
                "#TITLE Conditional Test",
                "#BPM 120",
                "#WAV01 a.wav",
                "#WAV02 b.wav",
                "#WAV03 c.wav",
                "#00111:0101",
                "#CONDITIONAL 2.5",
                "#WHEN MISS>=10",
                "#00211:0002",
                "#ELSEWHEN GAUGE<30",
                "#00211:00000303",
                "#DEFAULT",
                "#00211:000000010101",
                "#ENDCONDITIONAL",
                "");

        BMSDecoder decoder = new BMSDecoder();
        BMSModel model = decoder.decode(bms.getBytes(StandardCharsets.UTF_8), false, null);
        assertNotNull(model);
        assertTrue(model.hasConditional());
        assertEquals(1, model.getConditionalBlocks().length);

        ConditionalBlock block = model.getConditionalBlocks()[0];
        assertEquals(2.5, block.getEvalPosition(), 1e-9);
        assertEquals(3, block.getBranches().size());
        assertEquals(2, block.getDefaultBranchIndex());

        // 初期譜面は DEFAULT 分岐（#00111 に2ノーツ + #00211 の後半に3ノーツ = 計5ノーツ）
        assertEquals(5, model.getTotalNotes());

        // 全分岐のWAV内部ID(0, 1, 2)が収集されていること
        int[] wavIds = model.getConditionalWavIds();
        assertArrayEquals(new int[] { 0, 1, 2 }, wavIds);
    }

    @Test
    public void testPreEvaluationNotesInBranchAreSanitized() {
        String bms = String.join("\r\n",
                "#PLAYER 1",
                "#BPM 120",
                "#WAV01 a.wav",
                "#WAV02 b.wav",
                "#CONDITIONAL 2.5",
                "#WHEN SCORE >= 100",
                "#00111:0202",
                "#00202:0.5",
                "#00211:02020202",
                "#DEFAULT",
                "#00211:00000100",
                "#ENDCONDITIONAL",
                "");

        BMSDecoder decoder = new BMSDecoder();
        BMSModel model = decoder.decode(bms.getBytes(StandardCharsets.UTF_8), false, null);
        assertNotNull(model);
        assertEquals(1, model.getTotalNotes());

        // WHEN 分岐(index 0)を選択して再構築した場合:
        // - 小節1(#00111)は評価位置(2.5)より前なので無効化される
        // - 小節2の小節長変更(#00202)も評価位置(2.5)が小節途中なので無効化される
        // - #00211:02020202 (0.0, 0.25, 0.5, 0.75) のうち < 2.5 (0.0, 0.25) はマスクされ、>= 2.5 (0.5, 0.75) の2ノーツのみ残る
        BMSModel branched = model.getConditionalChartBuilder().createBranchedModel(model, new int[] { 0 });
        assertEquals(2, branched.getTotalNotes());
    }

    @Test
    public void testAllVariablesAndOperatorsEvaluation() {
        Map<Variable, Double> values = new EnumMap<>(Variable.class);
        values.put(Variable.SCORE, 1200.0);
        values.put(Variable.RATE, 85.5);
        values.put(Variable.COMBO, 250.0);
        values.put(Variable.MAXCOMBO, 300.0);
        values.put(Variable.PGREAT, 500.0);
        values.put(Variable.GREAT, 200.0);
        values.put(Variable.GOOD, 10.0);
        values.put(Variable.BAD, 2.0);
        values.put(Variable.POOR, 3.0);
        values.put(Variable.MISS, 7.0);
        values.put(Variable.GAUGE, 78.0);
        values.put(Variable.LAMP, 5.0);
        values.put(Variable.AUTO, 0.0);

        assertTrue(Condition.parse("SCORE > 1000").evaluate(values::get));
        assertTrue(Condition.parse("SCORE>=1200").evaluate(values::get));
        assertTrue(Condition.parse("RATE < 90.0").evaluate(values::get));
        assertTrue(Condition.parse("RATE <= 85.5").evaluate(values::get));
        assertTrue(Condition.parse("COMBO == 250").evaluate(values::get));
        assertTrue(Condition.parse("MAXCOMBO != 250").evaluate(values::get));
        assertTrue(Condition.parse("PGREAT >= 500").evaluate(values::get));
        assertTrue(Condition.parse("GREAT == 200").evaluate(values::get));
        assertTrue(Condition.parse("GOOD <= 10").evaluate(values::get));
        assertTrue(Condition.parse("BAD == 2").evaluate(values::get));
        assertTrue(Condition.parse("POOR == 3").evaluate(values::get));
        assertTrue(Condition.parse("MISS == 7").evaluate(values::get));
        assertTrue(Condition.parse("GAUGE >= 78").evaluate(values::get));
        assertTrue(Condition.parse("LAMP == 5").evaluate(values::get));
        assertTrue(Condition.parse("AUTO == 0").evaluate(values::get));
        assertEquals(Operator.GE, Condition.parse("SCORE >= 100").getOperator());
    }

    @Test
    public void testMineNoteDetectionInBranches() {
        String bms = String.join("\r\n",
                "#PLAYER 1",
                "#BPM 120",
                "#WAV01 a.wav",
                "#CONDITIONAL 1.0",
                "#WHEN AUTO == 1",
                "#001D1:01",
                "#DEFAULT",
                "#00111:01",
                "#ENDCONDITIONAL",
                "");

        BMSDecoder decoder = new BMSDecoder();
        BMSModel model = decoder.decode(bms.getBytes(StandardCharsets.UTF_8), false, null);
        assertNotNull(model);
        assertFalse(model.containsMineNote());
        assertTrue(model.isConditionalContainsMineNote());
    }

    @Test
    public void testBmcExtensionDecoder() {
        assertTrue(ChartDecoder.getDecoder("test.bmc") instanceof BMSDecoder);
        assertTrue(ChartDecoder.getDecoder("TEST.BMC") instanceof BMSDecoder);
        assertTrue(ChartDecoder.getDecoder(java.nio.file.Path.of("sample.bmc")) instanceof BMSDecoder);
    }
}
