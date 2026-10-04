package bms.model;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class BMSDecoderTest {

    @Test
    void testBMP00DefaultMissLayer() {
        // #BMP00 のみ定義され、チャンネル06が未定義のBMS
        String bmsContent = String.join("\n",
                "#PLAYER 1",
                "#GENRE TEST",
                "#TITLE Test BMP00",
                "#ARTIST Tester",
                "#BPM 120",
                "#BMP00 miss.bmp",
                "#BMP01 bga.bmp",
                "#00111:01"
        );

        BMSDecoder decoder = new BMSDecoder();
        BMSModel model = decoder.decode(bmsContent.getBytes(StandardCharsets.US_ASCII), false, null);

        assertNotNull(model);
        TimeLine[] timelines = model.getAllTimeLines();
        assertTrue(timelines.length > 0);

        // タイムライン0（開始時点）を取得
        TimeLine basetl = timelines[0];
        assertEquals(0, basetl.getTime());

        // ミスレイヤーが設定されていることを確認
        Layer[] eventlayers = basetl.getEventlayer();
        assertNotNull(eventlayers);
        assertTrue(eventlayers.length > 0);

        Layer missLayer = null;
        for (Layer layer : eventlayers) {
            if (layer.event.type == Layer.EventType.MISS) {
                missLayer = layer;
                break;
            }
        }
        assertNotNull(missLayer, "タイムライン0にMISSイベントレイヤーが設定されている必要があります");

        // #BMP00（miss.bmp）のIDが設定されていることを確認
        int bmp00Index = -1;
        String[] bgaList = model.getBgaList();
        for (int i = 0; i < bgaList.length; i++) {
            if ("miss.bmp".equals(bgaList[i])) {
                bmp00Index = i;
                break;
            }
        }
        assertTrue(bmp00Index >= 0);
        assertEquals(bmp00Index, missLayer.sequence[0][0].id);
    }

    @Test
    void testWithoutBMP00NoMissLayer() {
        // #BMP00 もチャンネル06も未定義のBMS
        String bmsContent = String.join("\n",
                "#PLAYER 1",
                "#GENRE TEST",
                "#TITLE Test No BMP00",
                "#ARTIST Tester",
                "#BPM 120",
                "#BMP01 bga.bmp",
                "#00111:01"
        );

        BMSDecoder decoder = new BMSDecoder();
        BMSModel model = decoder.decode(bmsContent.getBytes(StandardCharsets.US_ASCII), false, null);

        assertNotNull(model);
        TimeLine[] timelines = model.getAllTimeLines();
        assertTrue(timelines.length > 0);

        TimeLine basetl = timelines[0];
        Layer[] eventlayers = basetl.getEventlayer();
        boolean hasMissLayer = false;
        if (eventlayers != null) {
            for (Layer layer : eventlayers) {
                if (layer.event.type == Layer.EventType.MISS) {
                    hasMissLayer = true;
                    break;
                }
            }
        }
        assertFalse(hasMissLayer, "#BMP00が未定義の場合はMISSイベントレイヤーが設定されないこと");
    }

    @Test
    void testBMP00OverwrittenByChannel06InSection0() {
        // #BMP00 と #BMP01 が定義され、小節000で #00006:01 が明示指定されているBMS
        String bmsContent = String.join("\n",
                "#PLAYER 1",
                "#GENRE TEST",
                "#TITLE Test BMP00 Overwrite",
                "#ARTIST Tester",
                "#BPM 120",
                "#BMP00 miss.bmp",
                "#BMP01 custom_miss.bmp",
                "#00006:01",
                "#00111:01"
        );

        BMSDecoder decoder = new BMSDecoder();
        BMSModel model = decoder.decode(bmsContent.getBytes(StandardCharsets.US_ASCII), false, null);

        assertNotNull(model);
        TimeLine[] timelines = model.getAllTimeLines();
        assertTrue(timelines.length > 0);

        TimeLine basetl = timelines[0];
        Layer missLayer = null;
        for (Layer layer : basetl.getEventlayer()) {
            if (layer.event.type == Layer.EventType.MISS) {
                missLayer = layer;
                break;
            }
        }
        assertNotNull(missLayer);

        // custom_miss.bmp のIDで上書きされていることを確認
        int customMissIndex = -1;
        String[] bgaList = model.getBgaList();
        for (int i = 0; i < bgaList.length; i++) {
            if ("custom_miss.bmp".equals(bgaList[i])) {
                customMissIndex = i;
                break;
            }
        }
        assertTrue(customMissIndex >= 0);
        assertEquals(customMissIndex, missLayer.sequence[0][0].id);
    }

    @Test
    void testBMP00MaintainedUntilChannel06InLaterSection() {
        // #BMP00 と #BMP02 が定義され、小節000はチャンネル06なし、小節001で #00106:02 が指定されているBMS
        String bmsContent = String.join("\n",
                "#PLAYER 1",
                "#GENRE TEST",
                "#TITLE Test BMP00 Later Channel06",
                "#ARTIST Tester",
                "#BPM 120",
                "#BMP00 default_miss.bmp",
                "#BMP01 bga.bmp",
                "#BMP02 later_miss.bmp",
                "#00011:01",
                "#00106:02",
                "#00111:01"
        );

        BMSDecoder decoder = new BMSDecoder();
        BMSModel model = decoder.decode(bmsContent.getBytes(StandardCharsets.US_ASCII), false, null);

        assertNotNull(model);
        TimeLine[] timelines = model.getAllTimeLines();

        // 小節000（時間0）のミスレイヤーが default_miss.bmp であること
        TimeLine basetl = timelines[0];
        Layer initialMissLayer = null;
        for (Layer layer : basetl.getEventlayer()) {
            if (layer.event.type == Layer.EventType.MISS) {
                initialMissLayer = layer;
                break;
            }
        }
        assertNotNull(initialMissLayer);

        int defaultMissIndex = -1;
        int laterMissIndex = -1;
        String[] bgaList = model.getBgaList();
        for (int i = 0; i < bgaList.length; i++) {
            if ("default_miss.bmp".equals(bgaList[i])) {
                defaultMissIndex = i;
            } else if ("later_miss.bmp".equals(bgaList[i])) {
                laterMissIndex = i;
            }
        }
        assertEquals(defaultMissIndex, initialMissLayer.sequence[0][0].id);

        // 小節001のミスレイヤーが later_miss.bmp であること
        Layer laterMissLayer = null;
        for (TimeLine tl : timelines) {
            if (tl.getSection() >= 1.0) {
                for (Layer layer : tl.getEventlayer()) {
                    if (layer.event.type == Layer.EventType.MISS) {
                        laterMissLayer = layer;
                        break;
                    }
                }
                if (laterMissLayer != null) {
                    break;
                }
            }
        }
        assertNotNull(laterMissLayer);
        assertEquals(laterMissIndex, laterMissLayer.sequence[0][0].id);
    }

    @Test
    void testSpaceSeparatedChannelData() {
        // 小節データ行がコロンではなく半角スペース区切り（#xxxyy data）となっているBMS
        String bmsContent = String.join("\n",
                "#PLAYER 1",
                "#GENRE TEST",
                "#TITLE Test Space Separated",
                "#ARTIST Tester",
                "#BPM 120",
                "#WAV01 01.wav",
                "#WAV02 02.wav",
                "#WAV08 08.wav",
                "#BMP01 bga.bmp",
                "#00002 0.5",
                "#00004 01",
                "#00101 01",
                "#00111 02020202",
                "#00115 00080008"
        );

        BMSDecoder decoder = new BMSDecoder();
        BMSModel model = decoder.decode(bmsContent.getBytes(StandardCharsets.US_ASCII), false, null);

        assertNotNull(model);
        assertEquals(120.0, model.getBpm());
        // 1鍵: 4個 (02, 02, 02, 02), 5鍵: 2個 (00, 08, 00, 08 -> 08が2個)
        assertEquals(6, model.getTotalNotes());

        TimeLine[] timelines = model.getAllTimeLines();
        assertTrue(timelines.length > 0);

        // 小節000のBGAと小節長変更の反映確認
        boolean bgaFound = false;
        for (TimeLine tl : timelines) {
            if (tl.getBGA() >= 0) {
                bgaFound = true;
                break;
            }
        }
        assertTrue(bgaFound, "BGAが正常に配置されている必要があります");

        // 小節000の小節長が 0.5 になっているため、小節001のタイムラインsectionは 0.5 になる
        boolean section05Found = false;
        for (TimeLine tl : timelines) {
            if (Math.abs(tl.getSection() - 0.5) < 1e-6 && tl.getSectionLine()) {
                section05Found = true;
                break;
            }
        }
        assertTrue(section05Found, "小節拡大率0.5が反映され、次の小節線がsection=0.5に存在する必要があります");
    }

    @Test
    void testMixedColonAndSpaceSeparatedData() {
        // コロン区切りとスペース区切りが混在しているBMS
        String bmsContent = String.join("\n",
                "#PLAYER 1",
                "#GENRE TEST",
                "#TITLE Test Mixed Separator",
                "#ARTIST Tester",
                "#BPM 120",
                "#WAV01 01.wav",
                "#WAV02 02.wav",
                "#00111:0101",
                "#00112 0202"
        );

        BMSDecoder decoder = new BMSDecoder();
        BMSModel model = decoder.decode(bmsContent.getBytes(StandardCharsets.US_ASCII), false, null);

        assertNotNull(model);
        // 1鍵: 2個, 2鍵: 2個 = 計4個
        assertEquals(4, model.getTotalNotes());
    }
}

