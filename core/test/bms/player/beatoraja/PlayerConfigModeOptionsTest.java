package bms.player.beatoraja;

import bms.model.Mode;
import bms.player.beatoraja.play.GrooveGauge;
import com.badlogic.gdx.utils.Json;
import com.badlogic.gdx.utils.JsonWriter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ターゲットスコア・ゲージオートシフト・ゲージオートシフトの下限を KEY モード毎に保持する
 * PlayerConfig / PlayConfig の単体テスト
 */
class PlayerConfigModeOptionsTest {

    /** PlayerConfig.loadPlayerConfig + validatePlayerConfig と同じ手順で JSON を読み込む */
    private static PlayerConfig read(String json) {
        Json reader = new Json();
        reader.setIgnoreUnknownFields(true);
        PlayerConfig config = reader.fromJson(PlayerConfig.class, json);
        config.validate();
        return config;
    }

    @Test
    void defaultsForAllModes() {
        PlayerConfig config = new PlayerConfig();
        config.validate();
        for (Mode mode : Mode.values()) {
            assertEquals("MAX", config.getTargetid(mode), mode.name());
            assertEquals(PlayerConfig.GAUGEAUTOSHIFT_NONE, config.getGaugeAutoShift(mode), mode.name());
            assertEquals(GrooveGauge.ASSISTEASY, config.getBottomShiftableGauge(mode), mode.name());
        }
    }

    @Test
    void legacyValuesAreMigratedToAllModes() {
        PlayerConfig config = read("{\"targetid\":\"RATE_AA\",\"gaugeAutoShift\":3,\"bottomShiftableGauge\":2,"
                + "\"mode7\":{\"playconfig\":{\"hispeed\":2.5}}}");
        for (Mode mode : Mode.values()) {
            assertEquals("RATE_AA", config.getTargetid(mode), mode.name());
            assertEquals(PlayerConfig.GAUGEAUTOSHIFT_BESTCLEAR, config.getGaugeAutoShift(mode), mode.name());
            assertEquals(GrooveGauge.NORMAL, config.getBottomShiftableGauge(mode), mode.name());
        }
        assertEquals(2.5f, config.getPlayConfig(Mode.BEAT_7K).getPlayconfig().getHispeed());
    }

    @Test
    void perModeValueTakesPrecedenceOverLegacyValue() {
        PlayerConfig config = read("{\"targetid\":\"RATE_AA\",\"gaugeAutoShift\":1,\"bottomShiftableGauge\":0,"
                + "\"mode14\":{\"playconfig\":{\"targetid\":\"RATE_AAA\",\"gaugeAutoShift\":4,\"bottomShiftableGauge\":1}}}");
        assertEquals("RATE_AAA", config.getTargetid(Mode.BEAT_14K));
        assertEquals(PlayerConfig.GAUGEAUTOSHIFT_SELECT_TO_UNDER, config.getGaugeAutoShift(Mode.BEAT_14K));
        assertEquals(GrooveGauge.EASY, config.getBottomShiftableGauge(Mode.BEAT_14K));
        assertEquals("RATE_AA", config.getTargetid(Mode.BEAT_7K));
        assertEquals(PlayerConfig.GAUGEAUTOSHIFT_CONTINUE, config.getGaugeAutoShift(Mode.BEAT_7K));
        assertEquals(GrooveGauge.ASSISTEASY, config.getBottomShiftableGauge(Mode.BEAT_7K));
    }

    @Test
    void setterChangesOnlyTargetMode() {
        PlayerConfig config = read("{}");
        config.setTargetid(Mode.BEAT_7K, "RATE_A");
        config.setGaugeAutoShift(Mode.BEAT_7K, PlayerConfig.GAUGEAUTOSHIFT_SURVIVAL_TO_GROOVE);
        config.setBottomShiftableGauge(Mode.BEAT_7K, GrooveGauge.EASY);

        assertEquals("RATE_A", config.getTargetid(Mode.BEAT_7K));
        assertEquals(PlayerConfig.GAUGEAUTOSHIFT_SURVIVAL_TO_GROOVE, config.getGaugeAutoShift(Mode.BEAT_7K));
        assertEquals(GrooveGauge.EASY, config.getBottomShiftableGauge(Mode.BEAT_7K));
        for (Mode mode : new Mode[] { Mode.BEAT_5K, Mode.BEAT_14K, Mode.POPN_9K, Mode.KEYBOARD_24K }) {
            assertEquals("MAX", config.getTargetid(mode), mode.name());
            assertEquals(PlayerConfig.GAUGEAUTOSHIFT_NONE, config.getGaugeAutoShift(mode), mode.name());
            assertEquals(GrooveGauge.ASSISTEASY, config.getBottomShiftableGauge(mode), mode.name());
        }
    }

    @Test
    void popn5kSharesPopn9kValue() {
        PlayerConfig config = read("{}");
        config.setGaugeAutoShift(Mode.POPN_5K, PlayerConfig.GAUGEAUTOSHIFT_CONTINUE);
        assertEquals(PlayerConfig.GAUGEAUTOSHIFT_CONTINUE, config.getGaugeAutoShift(Mode.POPN_9K));
    }

    @Test
    void validateClampsInvalidValues() {
        PlayerConfig config = read("{\"gaugeAutoShift\":2,\"bottomShiftableGauge\":1,"
                + "\"mode5\":{\"playconfig\":{\"gaugeAutoShift\":9,\"bottomShiftableGauge\":9,\"targetid\":\"\"}},"
                + "\"mode10\":{\"playconfig\":{\"gaugeAutoShift\":-5,\"bottomShiftableGauge\":-5}}}");
        assertEquals(PlayerConfig.GAUGEAUTOSHIFT_SELECT_TO_UNDER, config.getGaugeAutoShift(Mode.BEAT_5K));
        assertEquals(GrooveGauge.NORMAL, config.getBottomShiftableGauge(Mode.BEAT_5K));
        assertEquals("MAX", config.getTargetid(Mode.BEAT_5K));
        // 不正な負数は未設定扱いになり、旧値を引き継ぐ
        assertEquals(PlayerConfig.GAUGEAUTOSHIFT_SURVIVAL_TO_GROOVE, config.getGaugeAutoShift(Mode.BEAT_10K));
        assertEquals(GrooveGauge.EASY, config.getBottomShiftableGauge(Mode.BEAT_10K));
    }

    @Test
    void perModeValuesSurviveSaveAndLoad() {
        PlayerConfig config = read("{}");
        config.setTargetid(Mode.BEAT_14K, "RATE_AAA+");
        config.setGaugeAutoShift(Mode.BEAT_14K, PlayerConfig.GAUGEAUTOSHIFT_BESTCLEAR);
        config.setBottomShiftableGauge(Mode.BEAT_14K, GrooveGauge.NORMAL);

        Json json = new Json();
        json.setOutputType(JsonWriter.OutputType.json);
        PlayerConfig loaded = read(json.toJson(config, PlayerConfig.class));
        assertEquals("RATE_AAA+", loaded.getTargetid(Mode.BEAT_14K));
        assertEquals(PlayerConfig.GAUGEAUTOSHIFT_BESTCLEAR, loaded.getGaugeAutoShift(Mode.BEAT_14K));
        assertEquals(GrooveGauge.NORMAL, loaded.getBottomShiftableGauge(Mode.BEAT_14K));
        assertEquals("MAX", loaded.getTargetid(Mode.BEAT_7K));
    }

    @Test
    void copyModeOptionsKeepsCurrentValuesOnHsReplay() {
        PlayConfig current = new PlayConfig();
        current.setLnmode(2);
        current.setTargetid("RATE_A");
        current.setGaugeAutoShift(PlayerConfig.GAUGEAUTOSHIFT_CONTINUE);
        current.setBottomShiftableGauge(GrooveGauge.EASY);
        PlayConfig replay = new PlayConfig();
        replay.setHispeed(3.0f);
        replay.setTargetid("MAX");
        replay.setLnmode(0);

        replay.copyModeOptions(current);
        assertEquals(3.0f, replay.getHispeed());
        assertEquals(2, replay.getLnmode());
        assertEquals("RATE_A", replay.getTargetid());
        assertEquals(PlayerConfig.GAUGEAUTOSHIFT_CONTINUE, replay.getGaugeAutoShift());
        assertEquals(GrooveGauge.EASY, replay.getBottomShiftableGauge());
    }

    @Test
    void legacyJudgetimingIsMigratedToAllModesOnce() {
        PlayerConfig config = read("{\"judgetiming\":-30,\"mode7\":{\"playconfig\":{\"judgetiming\":12}}}");
        for (Mode mode : Mode.values()) {
            assertEquals(-30, config.getPlayConfig(mode).getPlayconfig().getJudgetiming(), mode.name());
        }
        // 移行済みの設定はモード毎の値を維持する
        config.getPlayConfig(Mode.BEAT_7K).getPlayconfig().setJudgetiming(25);
        config.validate();
        assertEquals(25, config.getPlayConfig(Mode.BEAT_7K).getPlayconfig().getJudgetiming());
        assertEquals(-30, config.getPlayConfig(Mode.BEAT_14K).getPlayconfig().getJudgetiming());
    }

    @Test
    void migratedJudgetimingIsNotOverwritten() {
        PlayerConfig config = read("{\"judgetiming\":-30,\"migrationJudgetiming\":true,"
                + "\"mode7\":{\"playconfig\":{\"judgetiming\":12}}}");
        assertEquals(12, config.getPlayConfig(Mode.BEAT_7K).getPlayconfig().getJudgetiming());
        assertEquals(0, config.getPlayConfig(Mode.BEAT_14K).getPlayconfig().getJudgetiming());
    }

    @Test
    void migratedJudgetimingSurvivesSaveAndLoad() {
        PlayerConfig config = read("{\"judgetiming\":40}");
        config.getPlayConfig(Mode.BEAT_7K).getPlayconfig().setJudgetiming(0);

        Json json = new Json();
        json.setOutputType(JsonWriter.OutputType.json);
        PlayerConfig loaded = read(json.toJson(config, PlayerConfig.class));
        assertEquals(0, loaded.getPlayConfig(Mode.BEAT_7K).getPlayconfig().getJudgetiming());
        assertEquals(40, loaded.getPlayConfig(Mode.BEAT_14K).getPlayconfig().getJudgetiming());
    }
}
