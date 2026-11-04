package bms.model;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import bms.model.ConditionalBlock.Variable;
import bms.player.beatoraja.ir.IRChartData;
import bms.player.beatoraja.play.BMSPlayerRule;
import bms.player.beatoraja.play.ConditionalBranchController;
import bms.player.beatoraja.song.SongData;

public class BMSONDecoderTest {

    private static BMSModel decodeBmson(String json) {
        BMSONDecoder decoder = new BMSONDecoder(BMSModel.LNTYPE_LONGNOTE);
        ChartSource source = new ByteArrayChartSource("test.bmson", json.getBytes(StandardCharsets.UTF_8));
        return decoder.decode(new ChartInformation(source, BMSModel.LNTYPE_LONGNOTE, null));
    }

    @Test
    public void testSampleFormatExtensionsAndConditionalBlocks() {
        String json = """
                {
                  "version": "1.0.0",
                  "info": {
                    "title": "BMSON Extension Sample",
                    "artist": "Test Artist",
                    "mode_hint": "beat-7k",
                    "init_bpm": 120.0,
                    "resolution": 240,
                    "ln_type": 1,
                    "total": 300.0
                  },
                  "lines": [{ "y": 0 }, { "y": 360 }, { "y": 960 }],
                  "sound_channels": [
                    { "name": "scratch_inner.wav", "notes": [{ "x": 8, "y": 1200, "l": 0, "c": false }] }
                  ],
                  "free_zone_channels": [{ "name": "fz.wav", "notes": [{ "x": 8, "y": 960, "l": 960 }] }],
                  "potion_notes":       [{ "x": 2, "y": 480, "recovery": 10 }],
                  "dummy_notes":        [{ "x": 4, "y": 480, "l": 0 }, { "x": 5, "y": 480, "l": 240, "t": 3 }],
                  "dummy_mine_notes":   [{ "x": 6, "y": 720 }],
                  "dummy_potion_notes": [{ "x": 7, "y": 720 }],
                  "conditional_blocks": [{
                    "y": 3840,
                    "branches": [
                      { "when": "MISS>=100",
                        "sound_channels": [{ "name": "hard.wav", "notes": [
                          { "x": 1, "y": 3840, "l": 0, "c": false },
                          { "x": 2, "y": 4080, "l": 0, "c": true },
                          { "x": 3, "y": 4320, "l": 0, "c": true }
                        ] }] },
                      { "when": "GAUGE<30",
                        "sound_channels": [{ "name": "easy.wav", "notes": [{ "x": 2, "y": 3840, "l": 0, "c": false }] }] },
                      { "default": true,
                        "sound_channels": [{ "name": "base.wav", "notes": [{ "x": 1, "y": 3840, "l": 0, "c": false }] }],
                        "text_events": [{ "y": 3840, "text": "DEFAULT" }] }
                    ]
                  }]
                }
                """;

        BMSModel model = decodeBmson(json);
        assertNotNull(model);
        assertEquals(Mode.BEAT_7K, model.getMode());

        // 1. 総ノーツ数は DEFAULT 分岐基準:
        //    フリーゾーン1個(通常皿1個として計上、y=1200の内包皿は非計上) + DEFAULT分岐の通常ノーツ1個 = 計2ノーツ
        //    ポーション・ダミー4種はすべて総ノーツ数から除外される
        assertEquals(2, model.getTotalNotes());
        assertEquals(1, BMSModelUtils.getTotalNotes(model, BMSModelUtils.TOTALNOTES_SCRATCH));
        assertEquals(1, BMSModelUtils.getTotalNotes(model, BMSModelUtils.TOTALNOTES_KEY));
        assertTrue(model.containsFreeZone());
        assertFalse(model.containsLongNote());
        assertFalse(model.containsMineNote());

        // 2. 任意位置の小節線 (lines y=360 -> section=0.375)
        TimeLine tlLine = findTimeLineAtSection(model, 360.0 / 960.0);
        assertNotNull(tlLine);
        assertTrue(tlLine.getSectionLine());

        // 3. y=480 (section=0.5): ポーション(x=2 -> lane 1), ダミー通常(x=4 -> lane 3), ダミーHCN始点(x=5 -> lane 4)
        TimeLine tl480 = findTimeLineAtSection(model, 480.0 / 960.0);
        assertNotNull(tl480);
        Note potion = tl480.getNote(1);
        assertTrue(potion instanceof PotionNote);
        assertFalse(potion.isDummy());
        assertEquals(10.0, ((PotionNote) potion).getRecovery(), 1e-6);

        Note dummyNormal = tl480.getNote(3);
        assertTrue(dummyNormal instanceof NormalNote);
        assertTrue(dummyNormal.isDummy());

        Note dummyLn = tl480.getNote(4);
        assertTrue(dummyLn instanceof LongNote);
        assertTrue(dummyLn.isDummy());
        assertFalse(((LongNote) dummyLn).isEnd());
        assertEquals(LongNote.TYPE_HELLCHARGENOTE, ((LongNote) dummyLn).getType());

        // 4. y=720 (section=0.75): ダミーHCN終点(lane 4), ダミー地雷(x=6 -> lane 5), ダミーポーション(x=7 -> lane 6)
        TimeLine tl720 = findTimeLineAtSection(model, 720.0 / 960.0);
        assertNotNull(tl720);
        assertSame(((LongNote) dummyLn).getPair(), tl720.getNote(4));
        assertTrue(((LongNote) dummyLn).getPair().isEnd());

        Note dummyMine = tl720.getNote(5);
        assertTrue(dummyMine instanceof MineNote);
        assertTrue(dummyMine.isDummy());

        Note dummyPotion = tl720.getNote(6);
        assertTrue(dummyPotion instanceof PotionNote);
        assertTrue(dummyPotion.isDummy());

        // 5. y=960..1920 (section=1.0..2.0): フリーゾーン(x=8 -> lane 7) と開始音 BGM自動再生、内包皿ノーツ(y=1200)
        TimeLine tl960 = findTimeLineAtSection(model, 1.0);
        TimeLine tl1200 = findTimeLineAtSection(model, 1200.0 / 960.0);
        TimeLine tl1920 = findTimeLineAtSection(model, 2.0);
        assertNotNull(tl960);
        assertNotNull(tl1200);
        assertNotNull(tl1920);
        assertNotNull(tl960.getFreeZone(7));
        assertFalse(tl960.getFreeZone(7).isEnd());
        assertNotNull(tl1920.getFreeZone(7));
        assertTrue(tl1920.getFreeZone(7).isEnd());
        assertTrue(tl960.getBackGroundNotes().length > 0);
        assertEquals(1, tl960.getFreeZone(7).getInnerNotes().length);
        assertTrue(tl1200.getNote(7).isFreeZoneInner());

        // 6. conditional_blocks の検証と全分岐音源・スライス事前ロードデータの確認
        assertTrue(model.hasConditional());
        assertEquals(1, model.getConditionalBlocks().length);
        assertEquals(4.0, model.getConditionalBlocks()[0].getEvalPosition(), 1e-6);
        assertTrue(model.getConditionalPreloadNotes().length >= 3);

        // SongData / IRChartData のフラグ検証
        SongData songData = new SongData(model, false);
        assertTrue(songData.hasConditionalSequence());
        assertTrue(songData.hasFreeZone());
        IRChartData irChart = new IRChartData(songData);
        assertTrue(irChart.hasConditional);
        assertTrue(irChart.hasFreeZone);

        // 7. ConditionalBranchController による動的譜面差し替え検証 (MISS >= 100 -> branch 0 採用)
        BMSPlayerRule.validate(model);
        ConditionalBranchController controller = new ConditionalBranchController(
                null, model, null,
                model.getTotalNotes(), model.getTotal(),
                0, false, 0, 100, null);
        Map<Variable, Double> live = new EnumMap<>(Variable.class);
        live.put(Variable.MISS, 120.0);
        live.put(Variable.GAUGE, 50.0);
        controller.setCustomVariableProvider(v -> live.getOrDefault(v, 0.0));

        controller.evaluateUpTo(controller.getNextEvalMicroTime());
        assertArrayEquals(new int[] { 0 }, controller.getSelectedBranches());
        // フリーゾーン1個 + branch 0 の通常ノーツ3個 = 4ノーツ
        assertEquals(4, model.getTotalNotes());
    }

    @Test
    public void testFreeZoneDefaultQuarterLengthAndMerging() {
        String json = """
                {
                  "version": "1.0.0",
                  "info": {
                    "title": "FZ Merge Test",
                    "mode_hint": "beat-5k",
                    "init_bpm": 120.0,
                    "resolution": 240
                  },
                  "free_zone_channels": [{
                    "notes": [
                      { "x": 8, "y": 240 },
                      { "x": 8, "y": 480, "l": 480 }
                    ]
                  }]
                }
                """;

        BMSModel model = decodeBmson(json);
        assertNotNull(model);
        assertEquals(Mode.BEAT_5K, model.getMode());
        // beat-5kの皿レーン(x=8 -> lane 5)で [240, 480] (l省略=240) と [480, 960] が結合され [240, 960] の1個になる
        assertEquals(1, model.getTotalNotes());
        TimeLine tlStart = findTimeLineAtSection(model, 240.0 / 960.0);
        TimeLine tlEnd = findTimeLineAtSection(model, 960.0 / 960.0);
        assertNotNull(tlStart);
        assertNotNull(tlEnd);
        assertNotNull(tlStart.getFreeZone(5));
        assertFalse(tlStart.getFreeZone(5).isEnd());
        assertSame(tlEnd.getFreeZone(5), tlStart.getFreeZone(5).getPair());
    }

    @Test
    public void testNotePriorityAndPreEvaluationSanitizationInBranch() {
        String json = """
                {
                  "version": "1.0.0",
                  "info": {
                    "title": "Priority and Sanitization Test",
                    "mode_hint": "beat-7k",
                    "init_bpm": 120.0,
                    "resolution": 240,
                    "ln_type": 2
                  },
                  "sound_channels": [
                    { "name": "n.wav", "notes": [{ "x": 1, "y": 480, "l": 0, "c": false }] }
                  ],
                  "potion_notes": [
                    { "x": 1, "y": 480, "recovery": 20 },
                    { "x": 2, "y": 480, "recovery": 15 }
                  ],
                  "dummy_notes": [
                    { "x": 2, "y": 480, "l": 0 },
                    { "x": 3, "y": 480, "l": 240 }
                  ],
                  "dummy_mine_notes": [
                    { "x": 3, "y": 480 }
                  ],
                  "conditional_blocks": [{
                    "y": 960,
                    "branches": [
                      { "when": "AUTO==1",
                        "sound_channels": [{ "name": "b.wav", "notes": [
                          { "x": 1, "y": 480, "l": 0, "c": false },
                          { "x": 1, "y": 960, "l": 0, "c": false }
                        ] }] },
                      { "default": true }
                    ]
                  }]
                }
                """;

        BMSModel model = decodeBmson(json);
        assertNotNull(model);
        TimeLine tl480 = findTimeLineAtSection(model, 0.5);
        assertNotNull(tl480);

        // x=1(lane 0): 通常ノーツがポーションより優先される
        assertTrue(tl480.getNote(0) instanceof NormalNote);
        assertFalse(tl480.getNote(0).isDummy());

        // x=2(lane 1): ポーションがダミー通常ノーツより優先される
        assertTrue(tl480.getNote(1) instanceof PotionNote);
        assertFalse(tl480.getNote(1).isDummy());

        // x=3(lane 2): ダミーLN(l>0, t省略時はinfo.ln_type=2=CN)がダミー地雷より優先される
        assertTrue(tl480.getNote(2) instanceof LongNote);
        assertTrue(tl480.getNote(2).isDummy());
        assertEquals(LongNote.TYPE_CHARGENOTE, ((LongNote) tl480.getNote(2)).getType());

        // WHEN分岐(index 0)を選択した場合、評価位置(y=960)より前の y=480 の分岐内ノーツはサニタイズされ、y=960 の1個のみ追加される
        BMSModel branched = model.getConditionalChartBuilder().createBranchedModel(model, new int[] { 0 });
        assertEquals(2, branched.getTotalNotes());
    }

    @Test
    public void testBranchSoundSlicingPreloadAndSongDataFlags() {
        String json = """
                {
                  "version": "1.0.0",
                  "info": {
                    "title": "Slice and Flags Test",
                    "mode_hint": "beat-14k",
                    "init_bpm": 120.0,
                    "resolution": 240
                  },
                  "conditional_blocks": [{
                    "y": 960,
                    "branches": [
                      { "when": "COMBO>=50",
                        "sound_channels": [{
                          "name": "sliced_branch.wav",
                          "notes": [
                            { "x": 1, "y": 960, "l": 0, "c": false },
                            { "x": 2, "y": 1440, "l": 0, "c": true }
                          ]
                        }],
                        "free_zone_channels": [{
                          "notes": [{ "x": 16, "y": 960, "l": 480 }]
                        }]
                      },
                      { "default": true,
                        "sound_channels": [{
                          "name": "default_branch.wav",
                          "notes": [{ "x": 1, "y": 960, "l": 0, "c": false }]
                        }]
                      }
                    ]
                  }]
                }
                """;

        BMSModel model = decodeBmson(json);
        assertNotNull(model);
        assertEquals(Mode.BEAT_14K, model.getMode());
        assertTrue(model.hasConditional());
        assertTrue(model.isConditionalContainsFreeZone());

        // SongData / IRChartData のフラグ検証
        bms.player.beatoraja.song.SongData songData = new bms.player.beatoraja.song.SongData(model, false);
        assertTrue(songData.hasConditionalSequence());
        assertTrue(songData.hasFreeZone());
        bms.player.beatoraja.ir.IRChartData irChart = new bms.player.beatoraja.ir.IRChartData(songData);
        assertTrue(irChart.hasConditional);

        // 分岐内 sound_channels の c: true スライスノーツが conditionalPreloadNotes に含まれていること
        Note[] preloadNotes = model.getConditionalPreloadNotes();
        assertNotNull(preloadNotes);
        assertTrue(preloadNotes.length >= 2);
        boolean foundSlicedSecondNote = false;
        for (Note n : preloadNotes) {
            if (n.getMilliStarttime() > 0) {
                foundSlicedSecondNote = true;
                // BPM 120 で 480 pulse (2拍 = 1000ms) 後なので starttime == 1000ms
                assertEquals(1000L, n.getMilliStarttime());
            }
        }
        assertTrue(foundSlicedSecondNote);
    }

    @Test
    public void testSpeedAndScrollEvents() {
        String json = """
                {
                  "version": "1.0.0",
                  "info": {
                    "title": "Speed Events Test",
                    "mode_hint": "beat-7k",
                    "init_bpm": 120.0,
                    "resolution": 240
                  },
                  "scroll_events": [
                    { "y": 240, "rate": 2.0 },
                    { "y": 960, "rate": 1.0 }
                  ],
                  "speed_events": [
                    { "y": 0, "rate": 1.0 },
                    { "y": 480, "rate": 0.5 },
                    { "y": 960, "rate": 2.0 }
                  ],
                  "lines": [
                    { "y": 360 },
                    { "y": 720 }
                  ],
                  "sound_channels": [{
                    "name": "n.wav",
                    "notes": [{ "x": 1, "y": 720, "l": 0, "c": false }]
                  }]
                }
                """;

        BMSModel model = decodeBmson(json);
        assertNotNull(model);

        TimeLine tl0 = findTimeLineAtSection(model, 0.0);
        assertNotNull(tl0);
        assertTrue(tl0.hasSpeedObj());
        assertEquals(1.0, tl0.getSpeed(), 1e-6);

        // y=240 (0.25): scroll=2.0
        TimeLine tl240 = findTimeLineAtSection(model, 240.0 / 960.0);
        assertNotNull(tl240);
        assertEquals(2.0, tl240.getScroll(), 1e-6);
        assertFalse(tl240.hasSpeedObj());
        assertEquals(1.0, tl240.getSpeed(), 1e-6);

        // y=360 (0.375): 後続TimeLineにも scroll=2.0, speed=1.0 が引き継がれる
        TimeLine tl360 = findTimeLineAtSection(model, 360.0 / 960.0);
        assertNotNull(tl360);
        assertEquals(2.0, tl360.getScroll(), 1e-6);
        assertFalse(tl360.hasSpeedObj());
        assertEquals(1.0, tl360.getSpeed(), 1e-6);

        // y=480 (0.5): speedObj=true, speed=0.5, scroll=2.0
        TimeLine tl480 = findTimeLineAtSection(model, 480.0 / 960.0);
        assertNotNull(tl480);
        assertTrue(tl480.hasSpeedObj());
        assertEquals(0.5, tl480.getSpeed(), 1e-6);
        assertEquals(2.0, tl480.getScroll(), 1e-6);

        // y=720 (0.75): 後続のノーツTimeLineにも scroll=2.0, speed=0.5 が引き継がれる（hasSpeedObjはfalse）
        TimeLine tl720 = findTimeLineAtSection(model, 720.0 / 960.0);
        assertNotNull(tl720);
        assertFalse(tl720.hasSpeedObj());
        assertEquals(0.5, tl720.getSpeed(), 1e-6);
        assertEquals(2.0, tl720.getScroll(), 1e-6);

        // y=960 (1.0): speedObj=true, speed=2.0, scroll=1.0
        TimeLine tl960 = findTimeLineAtSection(model, 960.0 / 960.0);
        assertNotNull(tl960);
        assertTrue(tl960.hasSpeedObj());
        assertEquals(2.0, tl960.getSpeed(), 1e-6);
        assertEquals(1.0, tl960.getScroll(), 1e-6);
    }

    private static TimeLine findTimeLineAtSection(BMSModel model, double section) {
        for (TimeLine tl : model.getAllTimeLines()) {
            if (Math.abs(tl.getSection() - section) < 1e-6) {
                return tl;
            }
        }
        return null;
    }
}

