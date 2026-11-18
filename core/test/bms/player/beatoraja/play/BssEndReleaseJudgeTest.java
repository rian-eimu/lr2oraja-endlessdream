package bms.player.beatoraja.play;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import bms.model.BMSDecoder;
import bms.model.BMSModel;
import bms.model.LongNote;
import bms.model.Mode;
import bms.model.Note;
import bms.model.TimeLine;
import bms.player.beatoraja.BMSPlayerMode;
import bms.player.beatoraja.Config;
import bms.player.beatoraja.MainController;
import bms.player.beatoraja.PlayerConfig;
import bms.player.beatoraja.PlayerResource;
import bms.player.beatoraja.TimerManager;
import bms.player.beatoraja.audio.AudioDriver;
import bms.player.beatoraja.input.BMSPlayerInputProcessor;
import sun.misc.Unsafe;

/**
 * CN / HCN における BSS（スクラッチロングノート）終端の離し判定および切り返し共存の単体テスト
 */
class BssEndReleaseJudgeTest {

    private static final int SCRATCH_LANE = 7;
    private static final int SCRATCH_KEY_A = 7;
    private static final int SCRATCH_KEY_B = 8;

    private static String slots16(int... setSlots) {
        String[] s = new String[16];
        Arrays.fill(s, "00");
        for (int i = 0; i < setSlots.length; i += 2) {
            s[setSlots[i]] = String.format("%02d", setSlots[i + 1]);
        }
        return String.join("", s);
    }

    private static BMSModel decode7k(int lntype, String... lines) {
        List<String> all = new ArrayList<>(List.of(
                "#PLAYER 1",
                "#TITLE bss release test",
                "#BPM 120",
                "#TOTAL 300",
                "#WAV01 start.wav",
                "#WAV02 end.wav",
                "#WAV03 next.wav",
                "#WAV04 key.wav",
                // 7KEY モード確定用の鍵盤ノーツ（小節 4、チャンネル 18 = 6鍵）
                "#00418:" + slots16(0, 4)));
        all.addAll(List.of(lines));
        BMSModel model = new BMSDecoder(lntype).decode(
                String.join("\n", all).getBytes(StandardCharsets.US_ASCII), false, null);
        assertNotNull(model);
        assertEquals(Mode.BEAT_7K, model.getMode());
        BMSPlayerRule.validate(model);
        return model;
    }

    private static List<Note> scratchNotes(BMSModel model) {
        List<Note> notes = new ArrayList<>();
        for (TimeLine tl : model.getAllTimeLines()) {
            if (tl.getNote(SCRATCH_LANE) != null) {
                notes.add(tl.getNote(SCRATCH_LANE));
            }
        }
        return notes;
    }

    /**
     * LibGDX / OpenGL / PortAudio / SQLite に依存せず JudgeManager を直接駆動するテストハーネス
     */
    private static final class Harness {
        final BMSModel model;
        final JudgeManager judge;
        final BMSPlayerInputProcessor input;
        final List<Note> playedNotes = new ArrayList<>();

        Harness(BMSModel model, boolean pseudoBssAsBss) throws Exception {
            this.model = model;

            Unsafe unsafe = getUnsafe();
            Config config = new Config();
            config.validate();
            PlayerConfig playerConfig = new PlayerConfig();
            playerConfig.validate();
            playerConfig.setPseudoBssAsBss(pseudoBssAsBss);

            AudioDriver audio = (AudioDriver) Proxy.newProxyInstance(
                    AudioDriver.class.getClassLoader(),
                    new Class<?>[] { AudioDriver.class },
                    (proxy, method, args) -> {
                        if ("play".equals(method.getName()) && args != null && args.length == 3 && args[0] instanceof Note n) {
                            playedNotes.add(n);
                        }
                        Class<?> rt = method.getReturnType();
                        if (rt == boolean.class) return false;
                        if (rt == float.class) return 0f;
                        if (rt == int.class) return 0;
                        return null;
                    });

            BMSPlayerInputProcessor rawInput = (BMSPlayerInputProcessor) unsafe.allocateInstance(BMSPlayerInputProcessor.class);
            setField(BMSPlayerInputProcessor.class, rawInput, "keystate", new boolean[BMSPlayerInputProcessor.KEYSTATE_SIZE]);
            long[] times = new long[BMSPlayerInputProcessor.KEYSTATE_SIZE];
            Arrays.fill(times, Long.MIN_VALUE);
            setField(BMSPlayerInputProcessor.class, rawInput, "time", times);
            this.input = rawInput;

            PlayerResource resource = (PlayerResource) unsafe.allocateInstance(PlayerResource.class);
            setField(PlayerResource.class, resource, "config", config);
            setField(PlayerResource.class, resource, "pconfig", playerConfig);
            setField(PlayerResource.class, resource, "model", model);
            setField(PlayerResource.class, resource, "orgmode", model.getMode());
            setField(PlayerResource.class, resource, "mode", BMSPlayerMode.PLAY);

            TimerManager timer = new TimerManager();
            MainController mc = (MainController) unsafe.allocateInstance(MainController.class);
            setField(MainController.class, mc, "audio", audio);
            setField(MainController.class, mc, "config", config);
            setField(MainController.class, mc, "player", playerConfig);
            setField(MainController.class, mc, "resource", resource);
            setField(MainController.class, mc, "timer", timer);
            setField(MainController.class, mc, "input", rawInput);

            LaneProperty laneProperty = new LaneProperty(model.getMode());
            GrooveGauge gauge = GrooveGauge.create(model, GrooveGauge.NORMAL, resource);
            TestBMSPlayer player = (TestBMSPlayer) unsafe.allocateInstance(TestBMSPlayer.class);
            setField(bms.player.beatoraja.MainState.class, player, "main", mc);
            setField(bms.player.beatoraja.MainState.class, player, "timer", timer);
            setField(bms.player.beatoraja.MainState.class, player, "resource", resource);
            player.testModel = model;
            player.testLaneProperty = laneProperty;
            player.testGauge = gauge;

            this.judge = new JudgeManager(player);
            player.testJudge = this.judge;
            this.judge.init(model, resource);
        }

        void press(int key, long pmtime, long mtime) {
            input.setKeyState(key, true, pmtime);
            judge.update(mtime);
        }

        void release(int key, long pmtime, long mtime) {
            input.setKeyState(key, false, pmtime);
            judge.update(mtime);
        }

        void tick(long mtime) {
            judge.update(mtime);
        }

        private static Unsafe getUnsafe() throws Exception {
            Field f = Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            return (Unsafe) f.get(null);
        }

        private static void setField(Class<?> clazz, Object target, String name, Object value) throws Exception {
            Field f = clazz.getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        }
    }

    private static final class TestBMSPlayer extends BMSPlayer {
        BMSModel testModel;
        LaneProperty testLaneProperty;
        GrooveGauge testGauge;
        JudgeManager testJudge;

        private TestBMSPlayer() {
            super(null, null);
        }

        @Override
        public LaneProperty getLaneProperty() {
            return testLaneProperty;
        }

        @Override
        public GrooveGauge getGauge() {
            return testGauge;
        }

        @Override
        public JudgeManager getJudgeManager() {
            return testJudge;
        }

        @Override
        public Mode getMode() {
            return testModel.getMode();
        }

        @Override
        public float getAdjustedVolume() {
            return 1.0f;
        }

        @Override
        public KeyInputProccessor getKeyinput() {
            return null;
        }

        @Override
        public void update(int judge, long time) {
            testGauge.update(judge);
        }
    }

    @Test
    void releasesAtExactBssEndJudgesPgreatInCnAndHcn() throws Exception {
        for (int lntype : new int[] { BMSModel.LNTYPE_CHARGENOTE, BMSModel.LNTYPE_HELLCHARGENOTE }) {
            BMSModel model = decode7k(lntype,
                    "#00156:" + slots16(0, 1, 8, 2));
            List<Note> scNotes = scratchNotes(model);
            LongNote start = (LongNote) scNotes.get(0);
            LongNote end = (LongNote) scNotes.get(1);

            Harness h = new Harness(model, false);
            h.press(SCRATCH_KEY_A, start.getMicroTime(), start.getMicroTime());
            assertEquals(1, h.judge.getJudgeCount(0));
            assertSame(end, h.judge.getProcessingLongNote(SCRATCH_LANE));

            // 終端ちょうどでキーを離すと即座に PGREAT 確定する
            h.release(SCRATCH_KEY_A, end.getMicroTime(), end.getMicroTime());
            assertNull(h.judge.getProcessingLongNote(SCRATCH_LANE));
            assertEquals(2, h.judge.getJudgeCount(0));
            assertEquals(2, h.judge.getCombo());
            assertEquals(1, end.getState());
            assertEquals(0L, end.getMicroPlayTime());
            assertTrue(h.playedNotes.contains(end));
        }
    }

    @Test
    void releasesSlightlyLateWithinWindowJudgesImmediately() throws Exception {
        BMSModel model = decode7k(BMSModel.LNTYPE_CHARGENOTE,
                "#00156:" + slots16(0, 1, 8, 2));
        List<Note> scNotes = scratchNotes(model);
        LongNote start = (LongNote) scNotes.get(0);
        LongNote end = (LongNote) scNotes.get(1);

        Harness h = new Harness(model, false);
        h.press(SCRATCH_KEY_A, start.getMicroTime(), start.getMicroTime());

        // 終端から 25ms 遅れで離す -> 即座に SLOW PGREAT として確定する
        long lateRelease = end.getMicroTime() + 25_000L;
        h.release(SCRATCH_KEY_A, lateRelease, lateRelease);
        assertNull(h.judge.getProcessingLongNote(SCRATCH_LANE));
        assertEquals(1, h.judge.getJudgeCount(0, false));
        assertEquals(-25_000L, end.getMicroPlayTime());
        assertEquals(2, h.judge.getCombo());
    }

    @Test
    void earlyReleaseWithinWindowWaitsUntilEndTimeThenSettles() throws Exception {
        BMSModel model = decode7k(BMSModel.LNTYPE_CHARGENOTE,
                "#00156:" + slots16(0, 1, 8, 2));
        List<Note> scNotes = scratchNotes(model);
        LongNote start = (LongNote) scNotes.get(0);
        LongNote end = (LongNote) scNotes.get(1);

        Harness h = new Harness(model, false);
        h.press(SCRATCH_KEY_A, start.getMicroTime(), start.getMicroTime());

        // 終端の 40ms 手前で早離し -> 終端時刻前は未確定のまま待機する
        long earlyRelease = end.getMicroTime() - 40_000L;
        h.release(SCRATCH_KEY_A, earlyRelease, earlyRelease);
        assertSame(end, h.judge.getProcessingLongNote(SCRATCH_LANE));
        assertEquals(0, end.getState());
        assertEquals(1, h.judge.getCombo());

        h.tick(end.getMicroTime() - 1L);
        assertSame(end, h.judge.getProcessingLongNote(SCRATCH_LANE));
        assertEquals(0, end.getState());

        // 終端時刻に到達した瞬間に FAST PGREAT (+40ms) として確定する
        h.tick(end.getMicroTime());
        assertNull(h.judge.getProcessingLongNote(SCRATCH_LANE));
        assertEquals(1, end.getState());
        assertEquals(40_000L, end.getMicroPlayTime());
        assertEquals(2, h.judge.getJudgeCount(0, true));
        assertEquals(2, h.judge.getCombo());
        assertTrue(h.playedNotes.contains(end));
    }

    @Test
    void turnaroundBeforeEndTimeOverridesPendingEarlyRelease() throws Exception {
        // SEVENKEYS ルールで早 BAD 幅内の早離し後に逆回転入力を行った場合、切り返しタイミングが優先されることを検証するため
        // LR2 ルールの早 BAD 幅 (150ms 手前 -> BAD 判定) から終端直前 (-5ms -> PGREAT) で切り返すケースを検証
        BMSModel model = decode7k(BMSModel.LNTYPE_CHARGENOTE,
                "#00156:" + slots16(0, 1, 8, 2));
        List<Note> scNotes = scratchNotes(model);
        LongNote start = (LongNote) scNotes.get(0);
        LongNote end = (LongNote) scNotes.get(1);

        Harness h = new Harness(model, false);
        h.press(SCRATCH_KEY_A, start.getMicroTime(), start.getMicroTime());

        // 終端の 160ms 手前（早 BAD 幅内）で一旦離す
        long earlyBadRelease = end.getMicroTime() - 160_000L;
        h.release(SCRATCH_KEY_A, earlyBadRelease, earlyBadRelease);
        assertSame(end, h.judge.getProcessingLongNote(SCRATCH_LANE));

        // 終端の 5ms 手前で逆回転キーを押下（切り返し）-> 早 BAD 保留は破棄され PGREAT (+5ms) で確定する
        long turnaround = end.getMicroTime() - 5_000L;
        h.press(SCRATCH_KEY_B, turnaround, turnaround);
        assertNull(h.judge.getProcessingLongNote(SCRATCH_LANE));
        assertEquals(1, end.getState());
        assertEquals(5_000L, end.getMicroPlayTime());
        assertEquals(2, h.judge.getJudgeCount(0));
        assertEquals(0, h.judge.getJudgeCount(3));
        assertEquals(2, h.judge.getCombo());
    }

    @Test
    void repressSameScratchKeyCancelsPendingEarlyRelease() throws Exception {
        BMSModel model = decode7k(BMSModel.LNTYPE_CHARGENOTE,
                "#00156:" + slots16(0, 1, 8, 2));
        List<Note> scNotes = scratchNotes(model);
        LongNote start = (LongNote) scNotes.get(0);
        LongNote end = (LongNote) scNotes.get(1);

        Harness h = new Harness(model, false);
        h.press(SCRATCH_KEY_A, start.getMicroTime(), start.getMicroTime());

        // 160ms 手前（早 BAD 幅内）で離した後、100ms 手前で同じキーを押し直す
        h.release(SCRATCH_KEY_A, end.getMicroTime() - 160_000L, end.getMicroTime() - 160_000L);
        h.press(SCRATCH_KEY_A, end.getMicroTime() - 100_000L, end.getMicroTime() - 100_000L);

        // 終端時刻を通過しても早離し保留はキャンセル済みのため確定しない
        h.tick(end.getMicroTime() + 5_000L);
        assertSame(end, h.judge.getProcessingLongNote(SCRATCH_LANE));
        assertEquals(0, end.getState());

        // 終端から 10ms 後に離すと PGREAT (-10ms) として確定する
        h.release(SCRATCH_KEY_A, end.getMicroTime() + 10_000L, end.getMicroTime() + 10_000L);
        assertNull(h.judge.getProcessingLongNote(SCRATCH_LANE));
        assertEquals(1, end.getState());
        assertEquals(-10_000L, end.getMicroPlayTime());
        assertEquals(2, h.judge.getCombo());
    }

    @Test
    void sameFrameReleaseAndTurnaroundPrefersTurnaroundRegardlessOfKeyOrder() throws Exception {
        for (boolean startWithKeyA : new boolean[] { true, false }) {
            int k1 = startWithKeyA ? SCRATCH_KEY_A : SCRATCH_KEY_B;
            int k2 = startWithKeyA ? SCRATCH_KEY_B : SCRATCH_KEY_A;

            BMSModel model = decode7k(BMSModel.LNTYPE_CHARGENOTE,
                    "#00156:" + slots16(0, 1, 7, 1, 8, 2, 15, 2),
                    "#00216:" + slots16(0, 3));
            PseudoBssConverter.convert(model);
            List<Note> scNotes = scratchNotes(model);
            LongNote start1 = (LongNote) scNotes.get(0);
            LongNote end1 = (LongNote) scNotes.get(1);
            LongNote start2 = (LongNote) scNotes.get(2);
            LongNote end2 = (LongNote) scNotes.get(3);

            Harness h = new Harness(model, true);
            h.press(k1, start1.getMicroTime(), start1.getMicroTime());

            // 同一フレーム内で k1 の離し (end1 + 5ms) と k2 の押下 (end1 + 8ms) が同時に発生
            long releaseTime = end1.getMicroTime() + 5_000L;
            long pressTime = end1.getMicroTime() + 8_000L;
            h.input.setKeyState(k1, false, releaseTime);
            h.input.setKeyState(k2, true, pressTime);
            h.tick(pressTime);

            // キー番号順によらず k2 の切り返しで end1 と MSS 連結 start2 の双方が判定され、次区間保持に移行する
            assertEquals(1, end1.getState());
            assertEquals(-8_000L, end1.getMicroPlayTime());
            assertEquals(1, start2.getState());
            assertSame(end2, h.judge.getProcessingLongNote(SCRATCH_LANE));
            assertEquals(3, h.judge.getCombo());
        }
    }

    @Test
    void tooEarlyReleaseOutsideWindowSettlesPoorImmediately() throws Exception {
        BMSModel model = decode7k(BMSModel.LNTYPE_CHARGENOTE,
                "#00156:" + slots16(0, 1, 8, 2));
        List<Note> scNotes = scratchNotes(model);
        LongNote start = (LongNote) scNotes.get(0);
        LongNote end = (LongNote) scNotes.get(1);

        Harness h = new Harness(model, false);
        h.press(SCRATCH_KEY_A, start.getMicroTime(), start.getMicroTime());

        // 終端の 400ms 手前（BAD 判定幅外）で離す -> 即座に途中離し POOR で確定する
        long tooEarly = end.getMicroTime() - 400_000L;
        h.release(SCRATCH_KEY_A, tooEarly, tooEarly);
        assertNull(h.judge.getProcessingLongNote(SCRATCH_LANE));
        assertEquals(5, end.getState());
        assertEquals(1, h.judge.getJudgeCount(4));
        assertEquals(0, h.judge.getCombo());
    }

    @Test
    void holdingPastLateBadWindowWithoutReleaseResultsInMissPoor() throws Exception {
        BMSModel model = decode7k(BMSModel.LNTYPE_CHARGENOTE,
                "#00156:" + slots16(0, 1, 8, 2));
        List<Note> scNotes = scratchNotes(model);
        LongNote start = (LongNote) scNotes.get(0);
        LongNote end = (LongNote) scNotes.get(1);

        Harness h = new Harness(model, false);
        h.press(SCRATCH_KEY_A, start.getMicroTime(), start.getMicroTime());

        // キーを離さず終端の LATE BAD 幅 (200ms) を超えて押しっぱなし -> 見逃し POOR になる
        h.tick(end.getMicroTime() + 250_000L);
        assertNull(h.judge.getProcessingLongNote(SCRATCH_LANE));
        assertEquals(5, end.getState());
        assertEquals(1, h.judge.getJudgeCount(4));
        assertEquals(0, h.judge.getCombo());
    }

    @Test
    void lateAnalogTurnaroundFollowThroughDoesNotCauseEmptyPoorEarlyBadOrDoubleSound() throws Exception {
        // 小節 1: slot 0-8 の BSS (1.0s - 2.0s @ BPM120 -> 実際は小節1=2.0s〜3.0s) + slot 11 (375ms後) の通常皿ノーツ
        BMSModel model = decode7k(BMSModel.LNTYPE_CHARGENOTE,
                "#00156:" + slots16(0, 1, 8, 2),
                "#00116:" + slots16(11, 3));
        List<Note> scNotes = scratchNotes(model);
        LongNote start = (LongNote) scNotes.get(0);
        LongNote end = (LongNote) scNotes.get(1);
        Note nextScratch = scNotes.get(2);

        Harness h = new Harness(model, false);
        h.press(SCRATCH_KEY_A, start.getMicroTime(), start.getMicroTime());

        // 専コン Analog Ver.2 の遅め切り返し:
        // Poll 1 (end + 6ms): 順回転キー OFF -> BSS 終端が PGREAT で確定し、終端キー音が 1 回鳴る
        long poll1 = end.getMicroTime() + 6_000L;
        h.release(SCRATCH_KEY_A, poll1, poll1);
        assertEquals(1, end.getState());
        long endSoundCountAfterRelease = h.playedNotes.stream().filter(n -> n == end).count();
        assertEquals(1L, endSoundCountAfterRelease);

        // Poll 2 (end + 14ms): 逆回転キー ON が届く -> フォロー入力として吸収され、空 POOR も二重発音も起きない
        long poll2 = end.getMicroTime() + 14_000L;
        h.press(SCRATCH_KEY_B, poll2, poll2);
        assertEquals(0, h.judge.getJudgeCount(3));
        assertEquals(0, h.judge.getJudgeCount(4));
        assertEquals(0, h.judge.getJudgeCount(5));
        assertEquals(0, nextScratch.getState());
        long endSoundCountAfterFollow = h.playedNotes.stream().filter(n -> n == end).count();
        assertEquals(1L, endSoundCountAfterFollow);

        // 後続の通常皿ノーツを本来のタイミングで叩くと PGREAT になる
        h.release(SCRATCH_KEY_B, poll2 + 30_000L, poll2 + 30_000L);
        h.press(SCRATCH_KEY_A, nextScratch.getMicroTime(), nextScratch.getMicroTime());
        assertEquals(1, nextScratch.getState());
        assertEquals(3, h.judge.getJudgeCount(0));
        assertEquals(3, h.judge.getCombo());
    }

    @Test
    void adjacentScratchNoteHitWithinGoodWindowIsNotAbsorbed() throws Exception {
        // BSS 終端の 16 分後 (125ms 後) に通常皿ノーツがある譜面（疑似BSS変換 OFF）
        BMSModel model = decode7k(BMSModel.LNTYPE_CHARGENOTE,
                "#00156:" + slots16(0, 1, 7, 2),
                "#00116:" + slots16(8, 3));
        List<Note> scNotes = scratchNotes(model);
        LongNote start = (LongNote) scNotes.get(0);
        LongNote end = (LongNote) scNotes.get(1);
        Note adjacentScratch = scNotes.get(2);

        Harness h = new Harness(model, false);
        h.press(SCRATCH_KEY_A, start.getMicroTime(), start.getMicroTime());
        h.release(SCRATCH_KEY_A, end.getMicroTime(), end.getMicroTime());
        assertEquals(1, end.getState());

        // 125ms 後の実ノーツを逆キーでジャスト押下 -> 吸収されず PGREAT として判定される
        h.press(SCRATCH_KEY_B, adjacentScratch.getMicroTime(), adjacentScratch.getMicroTime());
        assertEquals(1, adjacentScratch.getState());
        assertEquals(3, h.judge.getJudgeCount(0));
        assertEquals(3, h.judge.getCombo());
    }

    @Test
    void mssIntermediateReleaseFollowedByTurnaroundOrRepressJudgesLinkedStart() throws Exception {
        // 1) 専コン Analog Ver.2（Poll 1 で離し確定 -> Poll 2 で遅 BAD 幅内の逆回転 ON）
        {
            BMSModel model = decode7k(BMSModel.LNTYPE_CHARGENOTE,
                    "#00156:" + slots16(0, 1, 7, 1, 8, 2, 15, 2),
                    "#00216:" + slots16(0, 3));
            PseudoBssConverter.convert(model);
            List<Note> scNotes = scratchNotes(model);
            LongNote start1 = (LongNote) scNotes.get(0);
            LongNote end1 = (LongNote) scNotes.get(1);
            LongNote start2 = (LongNote) scNotes.get(2);
            LongNote end2 = (LongNote) scNotes.get(3);

            Harness h = new Harness(model, true);
            h.press(SCRATCH_KEY_A, start1.getMicroTime(), start1.getMicroTime());

            // 中間ノート直後 (+130ms, start2 に対しては通常押下では弾かれる遅 BAD 領域) で離し -> 8ms 後に逆回転 ON
            long poll1 = end1.getMicroTime() + 130_000L;
            long poll2 = end1.getMicroTime() + 138_000L;
            h.release(SCRATCH_KEY_A, poll1, poll1);
            assertEquals(4, end1.getState()); // BAD (state = judge + 1 = 4)
            h.press(SCRATCH_KEY_B, poll2, poll2);
            assertEquals(4, start2.getState()); // 遅 BAD 救済が機能して start2 も捕捉される
            assertSame(end2, h.judge.getProcessingLongNote(SCRATCH_LANE));

            // 2 区間目の終端を離しで確定
            h.release(SCRATCH_KEY_B, end2.getMicroTime(), end2.getMicroTime());
            assertEquals(1, end2.getState());
            assertNull(h.judge.getProcessingLongNote(SCRATCH_LANE));
        }

        // 2) キーボード 1 キー操作（中間ノートで離し確定 -> 直後に同じキーを再押下して 2 区間目突入）
        {
            BMSModel model = decode7k(BMSModel.LNTYPE_CHARGENOTE,
                    "#00156:" + slots16(0, 1, 7, 1, 8, 2, 15, 2),
                    "#00216:" + slots16(0, 3));
            PseudoBssConverter.convert(model);
            List<Note> scNotes = scratchNotes(model);
            LongNote start1 = (LongNote) scNotes.get(0);
            LongNote end1 = (LongNote) scNotes.get(1);
            LongNote start2 = (LongNote) scNotes.get(2);
            LongNote end2 = (LongNote) scNotes.get(3);

            Harness h = new Harness(model, true);
            h.press(SCRATCH_KEY_A, start1.getMicroTime(), start1.getMicroTime());
            h.release(SCRATCH_KEY_A, end1.getMicroTime(), end1.getMicroTime());
            assertEquals(1, end1.getState());

            h.press(SCRATCH_KEY_A, start2.getMicroTime() + 10_000L, start2.getMicroTime() + 10_000L);
            assertEquals(1, start2.getState());
            assertSame(end2, h.judge.getProcessingLongNote(SCRATCH_LANE));

            h.release(SCRATCH_KEY_A, end2.getMicroTime(), end2.getMicroTime());
            assertEquals(1, end2.getState());
            assertEquals(4, h.judge.getCombo());
        }
    }

    @Test
    void longNoteModeRemainsUnchanged() throws Exception {
        BMSModel model = decode7k(BMSModel.LNTYPE_LONGNOTE,
                "#00156:" + slots16(0, 1, 8, 2));
        List<Note> scNotes = scratchNotes(model);
        LongNote start = (LongNote) scNotes.get(0);
        LongNote end = (LongNote) scNotes.get(1);

        Harness h = new Harness(model, false);
        h.press(SCRATCH_KEY_A, start.getMicroTime(), start.getMicroTime());
        // LN モードでは終端通過時点で押しっぱなしのまま PGREAT 確定する
        h.tick(end.getMicroTime() + 1_000L);
        assertNull(h.judge.getProcessingLongNote(SCRATCH_LANE));
        assertEquals(1, h.judge.getJudgeCount(0));
        assertEquals(1, h.judge.getCombo());
    }
}
