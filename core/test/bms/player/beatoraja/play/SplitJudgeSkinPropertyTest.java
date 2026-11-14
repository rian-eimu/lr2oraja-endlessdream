package bms.player.beatoraja.play;

import bms.model.BMSModel;
import bms.model.Mode;
import bms.model.NormalNote;
import bms.model.TimeLine;
import bms.player.beatoraja.AudioConfig;
import bms.player.beatoraja.BMSPlayerMode;
import bms.player.beatoraja.Config;
import bms.player.beatoraja.MainController;
import bms.player.beatoraja.MainState;
import bms.player.beatoraja.PlayerConfig;
import bms.player.beatoraja.PlayerResource;
import bms.player.beatoraja.Resolution;
import bms.player.beatoraja.TimerManager;
import bms.player.beatoraja.audio.AudioDriver;
import bms.player.beatoraja.input.BMSPlayerInputProcessor;
import bms.player.beatoraja.skin.SkinHeader;
import bms.player.beatoraja.skin.SkinProperty;
import bms.player.beatoraja.skin.property.BooleanPropertyFactory;
import bms.player.beatoraja.skin.property.IntegerPropertyFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 鍵盤・スクラッチ分離 FAST/SLOW スキンプロパティおよび JudgeManager 判定状態管理の単体テスト
 */
class SplitJudgeSkinPropertyTest {

    @Test
    @DisplayName("鍵盤・皿別 FAST/SLOW の Boolean / Integer プロパティが ID と名前の双方で解決できること")
    void testSplitJudgePropertyResolution() {
        int[] earlyIds = {
                SkinProperty.OPTION_1P_KEYS_EARLY,
                SkinProperty.OPTION_2P_KEYS_EARLY,
                SkinProperty.OPTION_3P_KEYS_EARLY,
                SkinProperty.OPTION_1P_SCRATCH_EARLY,
                SkinProperty.OPTION_2P_SCRATCH_EARLY,
                SkinProperty.OPTION_3P_SCRATCH_EARLY
        };
        int[] durationIds = {
                SkinProperty.VALUE_JUDGE_1P_KEYS_DURATION,
                SkinProperty.VALUE_JUDGE_2P_KEYS_DURATION,
                SkinProperty.VALUE_JUDGE_3P_KEYS_DURATION,
                SkinProperty.VALUE_JUDGE_1P_SCRATCH_DURATION,
                SkinProperty.VALUE_JUDGE_2P_SCRATCH_DURATION,
                SkinProperty.VALUE_JUDGE_3P_SCRATCH_DURATION
        };
        for (int slot = 0; slot < earlyIds.length; slot++) {
            String channel = (slot < 3 ? "k" : "s") + (slot % 3 + 1);
            int earlyId = earlyIds[slot];
            assertNotNull(BooleanPropertyFactory.getBooleanProperty(earlyId));
            assertSame(BooleanPropertyFactory.getBooleanProperty(earlyId),
                    BooleanPropertyFactory.getBooleanProperty("judge_" + channel + "_early"));
            assertNotNull(BooleanPropertyFactory.getBooleanProperty(earlyId + 1));
            assertSame(BooleanPropertyFactory.getBooleanProperty(earlyId + 1),
                    BooleanPropertyFactory.getBooleanProperty("judge_" + channel + "_late"));

            int durationId = durationIds[slot];
            assertNotNull(IntegerPropertyFactory.getIntegerProperty(durationId));
            assertSame(IntegerPropertyFactory.getIntegerProperty(durationId),
                    IntegerPropertyFactory.getIntegerProperty("judge_duration_" + channel));
        }
    }

    @Test
    @DisplayName("スクラッチ判定と鍵盤判定が互いのFAST/SLOW・ズレ時間・タイマーを上書きせず独立して動作すること")
    void testSplitJudgeDisplayIndependence() throws Exception {
        BMSModel model = new BMSModel();
        model.setMode(Mode.BEAT_7K);
        model.setBpm(120);
        model.setJudgerank(100);
        model.setTotal(300);

        TimeLine scratchLine = new TimeLine(0, 1_000_000, 8);
        scratchLine.setBPM(120);
        scratchLine.setNote(7, new NormalNote(0));

        TimeLine keyLine = new TimeLine(1, 2_000_000, 8);
        keyLine.setBPM(120);
        keyLine.setNote(0, new NormalNote(1));

        TimeLine keyPgLine = new TimeLine(2, 3_000_000, 8);
        keyPgLine.setBPM(120);
        keyPgLine.setNote(1, new NormalNote(2));

        model.setAllTimeLine(new TimeLine[]{scratchLine, keyLine, keyPgLine});

        HeadlessJudgeFixture f = new HeadlessJudgeFixture(model);
        var player = f.player;
        ((PlaySkin) player.getSkin()).setJudgeregion(1);
        f.judge.init(model, player.resource);

        var scratchDuration = IntegerPropertyFactory.getIntegerProperty("judge_duration_s1");
        var keyDuration = IntegerPropertyFactory.getIntegerProperty(SkinProperty.VALUE_JUDGE_1P_KEYS_DURATION);
        var scratchFast = BooleanPropertyFactory.getBooleanProperty(SkinProperty.OPTION_1P_SCRATCH_EARLY);
        var scratchSlow = BooleanPropertyFactory.getBooleanProperty(SkinProperty.OPTION_1P_SCRATCH_LATE);
        var keyFast = BooleanPropertyFactory.getBooleanProperty(SkinProperty.OPTION_1P_KEYS_EARLY);
        var keySlow = BooleanPropertyFactory.getBooleanProperty(SkinProperty.OPTION_1P_KEYS_LATE);
        var legacySlow = BooleanPropertyFactory.getBooleanProperty(SkinProperty.OPTION_1P_LATE);

        // 初回判定前はタイマーOFF・未計測状態であること
        assertFalse(player.timer.isTimerOn(SkinProperty.TIMER_JUDGE_1P_KEYS));
        assertFalse(player.timer.isTimerOn(SkinProperty.TIMER_JUDGE_1P_SCRATCH));
        assertEquals(Integer.MIN_VALUE, scratchDuration.get(player));
        assertEquals(Integer.MIN_VALUE, keyDuration.get(player));
        assertFalse(scratchFast.get(player));
        assertFalse(keySlow.get(player));

        // 1. スクラッチを 40ms 早押し (FAST GREAT) -> スクラッチ側のみ更新されること
        f.pressKey(7, 960_000);
        f.judge.update(960_000);
        f.releaseKey(7, 970_000);

        assertTrue(scratchFast.get(player));
        assertFalse(scratchSlow.get(player));
        assertEquals(40, scratchDuration.get(player));
        assertTrue(player.timer.isTimerOn(SkinProperty.TIMER_JUDGE_1P_SCRATCH));
        assertFalse(player.timer.isTimerOn(SkinProperty.TIMER_JUDGE_1P_KEYS));
        player.timer.setMicroTimer(SkinProperty.TIMER_JUDGE_1P_SCRATCH, 123L);

        // 2. 鍵盤を 30ms 遅押し (SLOW GREAT) -> 鍵盤側が更新され、スクラッチ側は保持されること
        f.pressKey(0, 2_030_000);
        f.judge.update(2_030_000);
        f.releaseKey(0, 2_040_000);

        assertTrue(keySlow.get(player));
        assertFalse(keyFast.get(player));
        assertEquals(-30, keyDuration.get(player));
        assertTrue(player.timer.isTimerOn(SkinProperty.TIMER_JUDGE_1P_KEYS));

        // スクラッチ側の表示状態・タイマー開始時刻は上書きされないこと
        assertTrue(scratchFast.get(player));
        assertEquals(40, scratchDuration.get(player));
        assertEquals(123L, player.timer.getMicroTimer(SkinProperty.TIMER_JUDGE_1P_SCRATCH));

        // 従来の統合 FAST/SLOW プロパティは直近判定（鍵盤 SLOW）に追従すること
        assertTrue(legacySlow.get(player));
        assertEquals(-30, IntegerPropertyFactory.getIntegerProperty(SkinProperty.VALUE_JUDGE_1P_DURATION).get(player));
        assertTrue(player.timer.isTimerOn(SkinProperty.TIMER_JUDGE_1P));

        // 3. 鍵盤 PGREAT (rank == 1) 時は early / late ともに false になりズレ時間は更新されること
        f.pressKey(1, 2_995_000);
        f.judge.update(2_995_000);
        assertFalse(keyFast.get(player));
        assertFalse(keySlow.get(player));
        assertEquals(5, keyDuration.get(player));

        // 4. JudgeManager 再初期化時は分離表示状態とタイマーがすべてリセットされること
        f.judge.init(model, player.resource);
        assertFalse(player.timer.isTimerOn(SkinProperty.TIMER_JUDGE_1P_KEYS));
        assertFalse(player.timer.isTimerOn(SkinProperty.TIMER_JUDGE_1P_SCRATCH));
        assertEquals(Integer.MIN_VALUE, keyDuration.get(player));
        assertEquals(Integer.MIN_VALUE, scratchDuration.get(player));
        assertFalse(scratchFast.get(player));
    }

    /**
     * OpenGL・オーディオ・MIDIデバイスを一切使用しないヘッドレス JudgeManager テストハーネス
     */
    private static final class HeadlessJudgeFixture {
        final BMSPlayerInputProcessor input;
        final JudgeManager judge;
        final DummyBMSPlayer player;

        HeadlessJudgeFixture(BMSModel model) throws Exception {
            Config config = new Config();
            config.setAudioConfig(new AudioConfig());
            PlayerConfig playerConfig = new PlayerConfig();

            input = allocate(BMSPlayerInputProcessor.class);
            boolean[] keystate = new boolean[256];
            long[] time = new long[256];
            Arrays.fill(time, Long.MIN_VALUE);
            setField(input, BMSPlayerInputProcessor.class, "keystate", keystate);
            setField(input, BMSPlayerInputProcessor.class, "time", time);

            AudioDriver silentAudio = (AudioDriver) Proxy.newProxyInstance(
                    AudioDriver.class.getClassLoader(),
                    new Class<?>[]{AudioDriver.class},
                    (proxy, method, args) -> {
                        Class<?> rt = method.getReturnType();
                        if (rt == boolean.class) return false;
                        if (rt == float.class) return 0f;
                        if (rt == int.class) return 0;
                        return null;
                    });

            MainController controller = allocate(MainController.class);
            setField(controller, MainController.class, "config", config);
            setField(controller, MainController.class, "player", playerConfig);
            setField(controller, MainController.class, "input", input);
            setField(controller, MainController.class, "audio", silentAudio);

            PlayerResource resource = allocate(PlayerResource.class);
            setField(resource, PlayerResource.class, "config", config);
            setField(resource, PlayerResource.class, "pconfig", playerConfig);
            setField(resource, PlayerResource.class, "orgmode", Mode.BEAT_7K);
            setField(resource, PlayerResource.class, "mode", BMSPlayerMode.PLAY);

            player = allocate(DummyBMSPlayer.class);
            setField(player, MainState.class, "main", controller);
            setField(player, MainState.class, "timer", new TimerManager());
            setField(player, MainState.class, "resource", resource);
            SkinHeader header = new SkinHeader();
            header.setSourceResolution(Resolution.HD);
            header.setDestinationResolution(Resolution.HD);
            setField(player, MainState.class, "skin", new PlaySkin(header));
            LaneProperty laneProperty = new LaneProperty(Mode.BEAT_7K);
            setField(player, BMSPlayer.class, "model", model);
            setField(player, BMSPlayer.class, "laneProperty", laneProperty);
            setField(player, BMSPlayer.class, "keyinput", new KeyInputProccessor(player, laneProperty));

            judge = new JudgeManager(player);
            setField(player, BMSPlayer.class, "judge", judge);
        }

        void pressKey(int key, long microTime) {
            input.resetAllKeyChangedTime();
            input.setKeyState(key, true, microTime);
        }

        void releaseKey(int key, long microTime) {
            input.setKeyState(key, false, microTime);
            input.resetAllKeyChangedTime();
        }
    }

    private static final class DummyBMSPlayer extends BMSPlayer {
        private DummyBMSPlayer() {
            super(null, null);
        }

        @Override
        public void update(int judge, long time) {
            // ヘッドレステスト用のためBGA・ゲージ更新は行わない
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T allocate(Class<T> type) throws Exception {
        Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        return (T) ((Unsafe) unsafeField.get(null)).allocateInstance(type);
    }

    private static void setField(Object target, Class<?> owner, String name, Object value) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
