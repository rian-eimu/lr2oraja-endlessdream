package bms.player.beatoraja;

import bms.model.BMSModel;
import bms.model.ChartInformation;
import bms.model.Mode;
import com.badlogic.gdx.utils.Json;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LN MODE を KEY モード毎に保持する PlayerConfig / PlayConfig の単体テスト
 */
class PlayerConfigLnmodeTest {

    private static final Mode[] STORED_MODES = {
            Mode.BEAT_5K, Mode.BEAT_7K, Mode.BEAT_10K, Mode.BEAT_14K,
            Mode.POPN_9K, Mode.KEYBOARD_24K, Mode.KEYBOARD_24K_DOUBLE };

    /** PlayerConfig.loadPlayerConfig + validatePlayerConfig と同じ手順で JSON を読み込む */
    private static PlayerConfig read(String json) {
        Json reader = new Json();
        reader.setIgnoreUnknownFields(true);
        PlayerConfig config = reader.fromJson(PlayerConfig.class, json);
        config.validate();
        return config;
    }

    @Test
    void defaultIsLongNoteForAllModes() {
        PlayerConfig config = new PlayerConfig();
        config.validate();
        for (Mode mode : Mode.values()) {
            assertEquals(BMSModel.LNTYPE_LONGNOTE, config.getLnmode(mode), mode.name());
        }
    }

    @Test
    void legacyLnmodeIsMigratedToAllModes() {
        PlayerConfig config = read("{\"lnmode\":2}");
        for (Mode mode : Mode.values()) {
            assertEquals(2, config.getLnmode(mode), mode.name());
        }
    }

    @Test
    void legacyLnmodeIsMigratedWhenModeConfigsExistWithoutLnmode() {
        // 旧バージョンが書き出した config_player.json は modeX/playconfig を持つが lnmode は持たない
        PlayerConfig config = read("{\"lnmode\":1,\"mode7\":{\"playconfig\":{\"hispeed\":2.5}},"
                + "\"mode14\":{\"playconfig\":{\"hispeed\":3.0}}}");
        for (Mode mode : Mode.values()) {
            assertEquals(1, config.getLnmode(mode), mode.name());
        }
        assertEquals(2.5f, config.getPlayConfig(Mode.BEAT_7K).getPlayconfig().getHispeed());
    }

    @Test
    void perModeValueTakesPrecedenceOverLegacyValue() {
        PlayerConfig config = read("{\"lnmode\":2,\"mode7\":{\"playconfig\":{\"lnmode\":1}}}");
        assertEquals(1, config.getLnmode(Mode.BEAT_7K));
        assertEquals(2, config.getLnmode(Mode.BEAT_14K));
        assertEquals(2, config.getLnmode(Mode.POPN_9K));
    }

    @Test
    void validateClampsLegacyAndPerModeValues() {
        PlayerConfig config = read("{\"lnmode\":9,\"mode5\":{\"playconfig\":{\"lnmode\":7}}}");
        assertEquals(3, config.getLnmode(Mode.BEAT_5K));
        assertEquals(3, config.getLnmode(Mode.BEAT_7K));

        config = read("{\"lnmode\":-4}");
        assertEquals(0, config.getLnmode(Mode.BEAT_7K));

        // 不正な負数は未設定扱いになり、旧 lnmode を引き継ぐ
        config = read("{\"lnmode\":2,\"mode10\":{\"playconfig\":{\"lnmode\":-5}}}");
        assertEquals(2, config.getLnmode(Mode.BEAT_10K));
    }

    @Test
    void modesAreIndependent() {
        PlayerConfig config = new PlayerConfig();
        config.validate();
        config.setLnmode(Mode.BEAT_7K, BMSModel.LNTYPE_CHARGENOTE);
        config.setLnmode(Mode.KEYBOARD_24K, BMSModel.LNTYPE_HELLLONGNOTE);

        assertEquals(BMSModel.LNTYPE_CHARGENOTE, config.getLnmode(Mode.BEAT_7K));
        assertEquals(BMSModel.LNTYPE_HELLLONGNOTE, config.getLnmode(Mode.KEYBOARD_24K));
        for (Mode mode : new Mode[] { Mode.BEAT_5K, Mode.BEAT_10K, Mode.BEAT_14K, Mode.POPN_9K, Mode.KEYBOARD_24K_DOUBLE }) {
            assertEquals(BMSModel.LNTYPE_LONGNOTE, config.getLnmode(mode), mode.name());
        }
    }

    @Test
    void setLnmodeClamps() {
        PlayerConfig config = new PlayerConfig();
        config.validate();
        config.setLnmode(Mode.BEAT_7K, 10);
        assertEquals(3, config.getLnmode(Mode.BEAT_7K));
        config.setLnmode(Mode.BEAT_7K, -1);
        assertEquals(0, config.getLnmode(Mode.BEAT_7K));
    }

    @Test
    void modeIdLookupMatchesModeLookup() {
        PlayerConfig config = new PlayerConfig();
        config.validate();
        int value = 0;
        for (Mode mode : STORED_MODES) {
            config.setLnmode(mode, value % 4);
            value++;
        }
        for (Mode mode : STORED_MODES) {
            assertEquals(config.getLnmode(mode), config.getLnmode(mode.id), mode.name());
        }
        // 未知のモードIDは BEAT_7K の値
        assertEquals(config.getLnmode(Mode.BEAT_7K), config.getLnmode(0));
        assertEquals(config.getLnmode(Mode.BEAT_7K), config.getLnmode((Mode) null));
    }

    @Test
    void popn5kSharesLnmodeWithPopn9k() {
        // POPN_5K と POPN_9K はモードID (9) が同じで SongData から区別できないため共有する
        PlayerConfig config = new PlayerConfig();
        config.validate();
        config.setLnmode(Mode.POPN_5K, BMSModel.LNTYPE_HELLCHARGENOTE);
        assertEquals(BMSModel.LNTYPE_HELLCHARGENOTE, config.getLnmode(Mode.POPN_9K));
        assertEquals(BMSModel.LNTYPE_HELLCHARGENOTE, config.getLnmode(9));
        assertEquals(BMSModel.LNTYPE_LONGNOTE, config.getLnmode(Mode.BEAT_7K));
    }

    @Test
    void perModeValuesSurviveJsonRoundTrip() {
        PlayerConfig config = read("{\"lnmode\":2}");
        config.setLnmode(Mode.BEAT_7K, BMSModel.LNTYPE_LONGNOTE);
        config.setLnmode(Mode.BEAT_14K, BMSModel.LNTYPE_HELLLONGNOTE);

        PlayerConfig reloaded = read(PlayerConfig.getConfigJson(config));
        assertEquals(BMSModel.LNTYPE_LONGNOTE, reloaded.getLnmode(Mode.BEAT_7K));
        assertEquals(BMSModel.LNTYPE_HELLLONGNOTE, reloaded.getLnmode(Mode.BEAT_14K));
        assertEquals(BMSModel.LNTYPE_HELLCHARGENOTE, reloaded.getLnmode(Mode.BEAT_5K));
        assertEquals(BMSModel.LNTYPE_HELLCHARGENOTE, reloaded.getLnmode(Mode.POPN_9K));
    }

    @Test
    void courseLnmodeUsesFirstSongMode() {
        PlayerConfig config = new PlayerConfig();
        config.validate();
        config.setLnmode(Mode.BEAT_7K, BMSModel.LNTYPE_CHARGENOTE);
        config.setLnmode(Mode.BEAT_14K, BMSModel.LNTYPE_HELLCHARGENOTE);

        var song7 = new bms.player.beatoraja.song.SongData();
        song7.setMode(Mode.BEAT_7K.id);
        var song14 = new bms.player.beatoraja.song.SongData();
        song14.setMode(Mode.BEAT_14K.id);

        assertEquals(BMSModel.LNTYPE_CHARGENOTE, config.getCourseLnmode(new bms.player.beatoraja.song.SongData[] { song7, song14 }));
        assertEquals(BMSModel.LNTYPE_HELLCHARGENOTE, config.getCourseLnmode(new bms.player.beatoraja.song.SongData[] { song14, song7 }));
        assertEquals(BMSModel.LNTYPE_CHARGENOTE, config.getCourseLnmode(new bms.player.beatoraja.song.SongData[0]));
    }

    @Test
    void applyLnmodeReplacesChartInformationKeepingOtherFields() {
        BMSModel model = new BMSModel();
        Path path = Path.of("dummy.bms");
        int[] randoms = { 1, 2 };
        model.setChartInformation(new ChartInformation(path, BMSModel.LNTYPE_LONGNOTE, randoms));

        PlayerResource.applyLnmode(model, BMSModel.LNTYPE_HELLCHARGENOTE);

        assertEquals(BMSModel.LNTYPE_HELLCHARGENOTE, model.getLntype());
        assertEquals(path, model.getChartInformation().path);
        assertArrayEquals(randoms, model.getChartInformation().selectedRandoms);
    }
}
