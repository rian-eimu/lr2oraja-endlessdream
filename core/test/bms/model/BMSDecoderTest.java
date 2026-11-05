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

    @Test
    void testLnMode4AndTotalNotes() {
        // #LNMODE 4 のパースと無効値の後置き上書き抑止、総ノーツ数（1本1ノーツ）およびSongData特徴フラグの確認
        String bmsContent = String.join("\n",
                "#PLAYER 1",
                "#GENRE TEST",
                "#TITLE Test LNMODE 4",
                "#ARTIST Tester",
                "#BPM 120",
                "#LNMODE 4",
                "#LNMODE 5",
                "#LNMODE 0",
                "#WAV01 01.wav",
                "#WAV02 02.wav",
                "#00151:0102"
        );

        BMSDecoder decoder = new BMSDecoder();
        BMSModel model = decoder.decode(bmsContent.getBytes(StandardCharsets.US_ASCII), false, null);

        assertNotNull(model);
        assertEquals(4, model.getLnmode());
        // HLNは終端をノーツ数に含めないため1ノーツ
        assertEquals(1, model.getTotalNotes());

        TimeLine[] timelines = model.getAllTimeLines();
        LongNote startNote = null;
        LongNote endNote = null;
        for (TimeLine tl : timelines) {
            Note n = tl.getNote(0);
            if (n instanceof LongNote ln) {
                if (!ln.isEnd()) {
                    startNote = ln;
                } else {
                    endNote = ln;
                }
            }
        }
        assertNotNull(startNote);
        assertNotNull(endNote);
        assertEquals(LongNote.TYPE_HELLLONGNOTE, startNote.getType());
        assertEquals(LongNote.TYPE_HELLLONGNOTE, endNote.getType());

        bms.player.beatoraja.song.SongData songData = new bms.player.beatoraja.song.SongData(model, false);
        assertTrue(songData.hasHellLongNote());
        assertTrue(songData.hasAnyLongNote());

        bms.player.beatoraja.ir.IRChartData chartData = new bms.player.beatoraja.ir.IRChartData(songData);
        assertTrue(chartData.hasHLN);
    }

    @Test
    void testObjHeadersSameIdPriority() {
        // 同一IDが複数指定された場合の優先順位 HLNOBJ > HCNOBJ > CNOBJ > LNOBJ の確認
        String bmsHlnWins = String.join("\n",
                "#PLAYER 1",
                "#BPM 120",
                "#WAV01 01.wav",
                "#WAV02 02.wav",
                "#HLNOBJ 02",
                "#HCNOBJ 02",
                "#CNOBJ 02",
                "#LNOBJ 02",
                "#00111:0102"
        );
        BMSDecoder decoder = new BMSDecoder();
        BMSModel modelHln = decoder.decode(bmsHlnWins.getBytes(StandardCharsets.US_ASCII), false, null);
        assertNotNull(modelHln);
        Note nHln = null;
        for (TimeLine tl : modelHln.getAllTimeLines()) {
            if (tl.getNote(0) != null) {
                nHln = tl.getNote(0);
                break;
            }
        }
        assertTrue(nHln instanceof LongNote);
        assertEquals(LongNote.TYPE_HELLLONGNOTE, ((LongNote) nHln).getType());
        assertEquals(1, modelHln.getTotalNotes());

        String bmsHcnWins = String.join("\n",
                "#PLAYER 1",
                "#BPM 120",
                "#WAV01 01.wav",
                "#WAV02 02.wav",
                "#LNOBJ 02",
                "#CNOBJ 02",
                "#HCNOBJ 02",
                "#00111:0102"
        );
        BMSModel modelHcn = decoder.decode(bmsHcnWins.getBytes(StandardCharsets.US_ASCII), false, null);
        assertNotNull(modelHcn);
        Note nHcn = null;
        for (TimeLine tl : modelHcn.getAllTimeLines()) {
            if (tl.getNote(0) != null) {
                nHcn = tl.getNote(0);
                break;
            }
        }
        assertTrue(nHcn instanceof LongNote);
        assertEquals(LongNote.TYPE_HELLCHARGENOTE, ((LongNote) nHcn).getType());
        // HCNは終端もカウントされるため2ノーツ
        assertEquals(2, modelHcn.getTotalNotes());
    }

    @Test
    void testObjHeadersSamePositionConflictAndWavPreservation() {
        // 同一タイムライン・同一レーンで異なるOBJ指定ノーツが重複配置された場合の優先順位と、マーカー自身の無音化・通常ノーツ終端音保持の確認
        String bmsConflict = String.join("\n",
                "#PLAYER 1",
                "#BPM 120",
                "#WAV01 start.wav",
                "#WAV02 ln_marker.wav",
                "#WAV03 hln_marker.wav",
                "#WAV04 end_a.wav",
                "#WAV05 end_b.wav",
                "#WAV06 end_b.wav",
                "#LNOBJ 02 ; trailing comment",
                "#HLNOBJ 03 extra_token",
                "#HLNOBJ 00",
                "#HLNOBJ",
                "#00111:0003",
                "#00111:0102",
                "#00111:00000500",
                "#00111:00000400",
                "#00111:0004",
                "#00111:0006"
        );
        BMSDecoder decoder = new BMSDecoder();
        BMSModel model = decoder.decode(bmsConflict.getBytes(StandardCharsets.US_ASCII), false, null);
        assertNotNull(model);

        LongNote startNote = null;
        LongNote endNote = null;
        for (TimeLine tl : model.getAllTimeLines()) {
            Note n = tl.getNote(0);
            if (n instanceof LongNote ln) {
                if (!ln.isEnd()) {
                    startNote = ln;
                } else {
                    endNote = ln;
                }
            }
        }
        assertNotNull(startNote);
        assertNotNull(endNote);
        // HLNOBJ (03) が無効な再定義で上書きされず、LNOBJ (02) より優先されること
        assertEquals(LongNote.TYPE_HELLLONGNOTE, startNote.getType());
        assertEquals(LongNote.TYPE_HELLLONGNOTE, endNote.getType());
        // マーカー (02, 03) のWAVは鳴らさず、通常ノーツ (04, 05, 06) がID昇順・同一ID重複除外・別ID同一ファイル保持で登録されること
        assertEquals(3, endNote.getWav()); // #WAV04 -> index 3
        assertEquals(2, endNote.getLayeredNotes().length);
        assertEquals(4, endNote.getLayeredNotes()[0].getWav()); // #WAV05 -> index 4
        assertEquals(5, endNote.getLayeredNotes()[1].getWav()); // #WAV06 -> index 5
    }

    @Test
    void testObjMarkerSilentWithoutOverlappedNormalNoteAndOrphanMarker() {
        // マーカー単体ではWAVがあっても終端音が鳴らないこと、始点のないマーカーと重ね音が破棄されること、通常ノーツ重複は後勝ちであることの確認
        String bms = String.join("\n",
                "#PLAYER 1",
                "#BPM 120",
                "#WAV01 n1.wav",
                "#WAV02 n2.wav",
                "#WAV03 marker.wav",
                "#CNOBJ 03",
                // 0.0: 始点のない孤立マーカー03 + 重ね音01 -> 破棄される
                "#00111:03000000",
                "#00111:01000000",
                // 0.25: マーカーなしの通常ノーツ重複 (01 -> 02) -> 後勝ちで02のみ
                "#00111:00010000",
                "#00111:00020000",
                // 0.50: マーカー03のみ (重ね通常ノーツなし) -> 0.25を始点とするCN終端になり、マーカー自身のWAV03は鳴らない
                "#00111:00000300"
        );
        BMSDecoder decoder = new BMSDecoder();
        BMSModel model = decoder.decode(bms.getBytes(StandardCharsets.US_ASCII), false, null);
        assertNotNull(model);

        TimeLine[] tls = model.getAllTimeLines();
        // 0.0の位置にはノーツが存在しないこと（孤立マーカーと重ね音の破棄）
        assertNull(tls[1].getNote(0));
        // 0.25の位置は後勝ちのWAV02 (index 1) かつレイヤーなしの長押し始点になっていること
        Note start = tls[2].getNote(0);
        assertTrue(start instanceof LongNote);
        assertEquals(1, start.getWav());
        assertEquals(0, start.getLayeredNotes().length);
        // 0.50の位置はマーカー自身のWAVを持たない無音終端 (-2) であること
        Note end = tls[3].getNote(0);
        assertTrue(end instanceof LongNote);
        assertEquals(-2, end.getWav());
        assertEquals(0, end.getLayeredNotes().length);
    }

    @Test
    void testDedicatedLnChannelPriorityOverTypedObj() {
        // 専用LNチャネル (5x) が種別付きOBJより優先され、5x終点と同位置のマーカー重ね音が5x終点に付与されることの確認
        String bms = String.join("\n",
                "#PLAYER 1",
                "#BPM 120",
                "#LNMODE 1",
                "#WAV01 ln_body.wav",
                "#WAV02 end_release.wav",
                "#WAV03 cn_marker.wav",
                "#CNOBJ 03",
                "#00111:0003",
                "#00111:0002",
                "#00151:0101"
        );
        BMSDecoder decoder = new BMSDecoder();
        BMSModel model = decoder.decode(bms.getBytes(StandardCharsets.US_ASCII), false, null);
        assertNotNull(model);

        LongNote startNote = null;
        LongNote endNote = null;
        for (TimeLine tl : model.getAllTimeLines()) {
            Note n = tl.getNote(0);
            if (n instanceof LongNote ln) {
                if (!ln.isEnd()) {
                    startNote = ln;
                } else {
                    endNote = ln;
                }
            }
        }
        assertNotNull(startNote);
        assertNotNull(endNote);
        // 専用LNチャネルのペア (#LNMODE 1 -> TYPE_LONGNOTE) が優先されること
        assertEquals(LongNote.TYPE_LONGNOTE, startNote.getType());
        // 5x終点と同位置のマーカーに重ねた通常ノーツ (02 -> index 1) が終端音として付与されること
        assertEquals(1, endNote.getWav());
    }

    @Test
    void testLegacyFreeZoneBeforeAndAfterCutoff() {
        // 旧形式 (#xxx17 / #xxx27) の更新日時によるフリーゾーン判定と無視時のモード昇格抑止の確認
        String bms = String.join("\n",
                "#PLAYER 1",
                "#BPM 120",
                "#WAV01 fz.wav",
                "#00117:01000000",
                "#00127:00000100"
        );
        byte[] bytes = bms.getBytes(StandardCharsets.US_ASCII);

        // 2000-12-31 以前: フリーゾーンとして認識され、#00127 により BEAT_10K へ昇格
        BMSDecoder decoder = new BMSDecoder();
        BMSModel legacyModel = decoder.decode(new ChartInformation(new ByteArrayChartSource("test.bms", bytes, BMSDecoder.LEGACY_FREEZONE_CUTOFF_MILLIS), BMSModel.LNTYPE_LONGNOTE, null));
        assertNotNull(legacyModel);
        assertEquals(Mode.BEAT_10K, legacyModel.getMode());
        assertTrue(legacyModel.containsFreeZone());
        assertEquals(2, legacyModel.getTotalNotes());
        assertEquals(2, BMSModelUtils.getTotalNotes(legacyModel, BMSModelUtils.TOTALNOTES_SCRATCH));
        assertEquals(0, BMSModelUtils.getTotalNotes(legacyModel, BMSModelUtils.TOTALNOTES_KEY));

        bms.player.beatoraja.song.SongData songData = new bms.player.beatoraja.song.SongData(legacyModel, false);
        assertTrue(songData.hasFreeZone());
        bms.player.beatoraja.ir.IRChartData irChartData = new bms.player.beatoraja.ir.IRChartData(songData);
        assertTrue(irChartData.hasFreeZone);

        // 2001-01-01 以降: 無視され、モードも BEAT_5K のまま
        BMSModel modernModel = decoder.decode(new ChartInformation(new ByteArrayChartSource("test.bms", bytes, BMSDecoder.LEGACY_FREEZONE_CUTOFF_MILLIS + 1000L), BMSModel.LNTYPE_LONGNOTE, null));
        assertNotNull(modernModel);
        assertEquals(Mode.BEAT_5K, modernModel.getMode());
        assertFalse(modernModel.containsFreeZone());
        assertEquals(0, modernModel.getTotalNotes());
    }

    @Test
    void testExplicitFreeZoneMergeInnerNotesAndCompetingBssMine() {
        // 明示形式 (#xxxSF / #xxxSG)、区間結合、内部皿ノーツの非計上、および競合BSS・地雷ノーツの共存確認
        String bms = String.join("\n",
                "#PLAYER 1",
                "#BPM 120",
                "#LNMODE 1",
                "#WAV01 scr.wav",
                "#WAV02 fz_marker.wav",
                // 小節001: 0.0からSF(4分=0.25長) + 0.25から0.50までSGペア -> [1.0, 1.5] の1本のフリーゾーンに結合
                "#001SF:02000000",
                "#001SG:00020200",
                // 始点(1.0)、中間(1.25)、終点(1.5)の皿ノーツ(16)と、区間外(1.75)の通常皿ノーツ
                "#00116:01010101",
                // 小節002: SGペア [2.0, 2.75] 内にBSS (#00256: [2.0, 2.25]) と地雷 (#002D6: 2.50) が共存
                "#002SG:02000002",
                "#00256:01010000",
                "#002D6:00000100"
        );
        BMSDecoder decoder = new BMSDecoder();
        BMSModel model = decoder.decode(bms.getBytes(StandardCharsets.US_ASCII), false, null);
        assertNotNull(model);
        assertEquals(Mode.BEAT_5K, model.getMode());
        assertTrue(model.containsFreeZone());

        int scratchLane = model.getMode().scratchKey[0];
        FreeZoneNote fz1Start = null;
        FreeZoneNote fz2Start = null;
        int innerCount = 0;
        int outerNormalScratchCount = 0;
        LongNote bssStart = null;
        MineNote mineInFz = null;

        for (TimeLine tl : model.getAllTimeLines()) {
            FreeZoneNote fz = tl.getFreeZone(scratchLane);
            if (fz != null && !fz.isEnd()) {
                if (Math.abs(tl.getSection() - 1.0) < 1e-6) {
                    fz1Start = fz;
                } else if (Math.abs(tl.getSection() - 2.0) < 1e-6) {
                    fz2Start = fz;
                }
            }
            Note n = tl.getNote(scratchLane);
            if (n instanceof NormalNote nn) {
                if (nn.isFreeZoneInner()) {
                    innerCount++;
                } else {
                    outerNormalScratchCount++;
                }
            } else if (n instanceof LongNote ln && !ln.isEnd()) {
                bssStart = ln;
            } else if (n instanceof MineNote mn) {
                mineInFz = mn;
            }
        }

        assertNotNull(fz1Start, "小節001のフリーゾーン始点が存在すること");
        assertEquals(1.5, fz1Start.getPair().getSection(), 1e-6, "SFとSGが結合され終点が1.5になっていること");
        assertEquals(-2, fz1Start.getWav(), "フリーゾーンマーカーは無音(-2)であること");
        assertEquals(3, fz1Start.getInnerNotes().length, "始点・中間・終点の3つの皿ノーツが内部ノーツとして紐付くこと");
        assertEquals(3, innerCount);
        assertEquals(1, outerNormalScratchCount, "区間外(1.75)の1個のみが通常皿ノーツであること");

        assertNotNull(fz2Start, "小節002のフリーゾーン始点が存在すること");
        assertNotNull(bssStart, "フリーゾーン内の競合BSSが維持されていること");
        assertTrue(bssStart.isFreeZoneInner(), "フリーゾーン内のBSS始点が内包ノーツとしてマークされていること");
        assertTrue(bssStart.getPair().isFreeZoneInner(), "フリーゾーン内のBSS終点が内包ノーツとしてマークされていること");
        assertEquals(1, fz2Start.getInnerNotes().length, "小節002のフリーゾーンにBSSが内部ノーツとして紐付くこと");
        assertSame(bssStart, fz2Start.getInnerNotes()[0]);
        assertNotNull(mineInFz, "フリーゾーン内の地雷ノーツが維持されていること");

        // 総ノーツ数: 小節001のFZ(1) + 小節001区間外の通常皿(1) + 小節002のFZ(1) = 3（内部BSSは単体ノーツ数に加算しない）
        assertEquals(3, model.getTotalNotes());
        assertEquals(3, BMSModelUtils.getTotalNotes(model, BMSModelUtils.TOTALNOTES_SCRATCH));
        assertEquals(0, BMSModelUtils.getTotalNotes(model, BMSModelUtils.TOTALNOTES_LONG_SCRATCH));
    }

    @Test
    void testPmsChannel17TreatedAsNormalNote() {
        // PMS形式では年代にかかわらず ch17 をフリーゾーンではなく9鍵目の通常ノーツとして扱うことの確認
        String pms = String.join("\n",
                "#PLAYER 1",
                "#BPM 120",
                "#WAV01 note.wav",
                "#00117:01000000"
        );
        byte[] bytes = pms.getBytes(StandardCharsets.US_ASCII);
        BMSDecoder decoder = new BMSDecoder();
        BMSModel model = decoder.decode(new ChartInformation(new ByteArrayChartSource("test.pms", bytes, BMSDecoder.LEGACY_FREEZONE_CUTOFF_MILLIS), BMSModel.LNTYPE_LONGNOTE, null));
        assertNotNull(model);
        assertEquals(Mode.POPN_9K, model.getMode());
        assertFalse(model.containsFreeZone(), "PMS形式ではch17がフリーゾーンにならないこと");
        assertEquals(1, model.getTotalNotes());

        Note lane8Note = null;
        for (TimeLine tl : model.getAllTimeLines()) {
            if (tl.getNote(8) != null) {
                lane8Note = tl.getNote(8);
                break;
            }
        }
        assertTrue(lane8Note instanceof NormalNote, "PMSのch17はレーン8の通常ノーツとして配置されること");
        assertFalse(((NormalNote) lane8Note).isFreeZoneInner());
    }

    @Test
    void testPotionNoteDecodingAndModeUpgradeAndCollisionAndTotalExclusion() {
        // F1-F9 (1P), G1-G9 (2P) のポーションノーツ解析・モード昇格・衝突優先順位・総ノーツ数除外の検証
        String bms = String.join("\n",
                "#PLAYER 1",
                "#BPM 120",
                "#WAV01 note.wav",
                // 小節001: 1P 1鍵(F1)に回復量0A(10%), 6鍵(F8)に1E(50%), 2P 1鍵(G1)に05(5%) -> 14KEYSへ昇格
                "#001F1:0A00",
                "#001F8:1E00",
                "#001G1:0500",
                // 小節002: 同一座標での衝突検証 (1鍵で通常ノーツ11とF1が衝突、2鍵で地雷D2とF2が衝突)
                "#00211:01",
                "#002F1:0A",
                "#002D2:0F",
                "#002F2:0A"
        );
        BMSDecoder decoder = new BMSDecoder();
        BMSModel model = decoder.decode(bms.getBytes(StandardCharsets.US_ASCII), false, null);
        assertNotNull(model);
        assertEquals(Mode.BEAT_14K, model.getMode(), "F8およびG1定義によりBEAT_14Kへ昇格すること");

        // 総ノーツ数にはポーションノーツも地雷ノーツも含まれず、小節002の通常ノーツ1個のみであること
        assertEquals(1, model.getTotalNotes(), "ポーションノーツが総ノーツ数に含まれないこと");

        PotionNote p1Key1 = null;
        PotionNote p1Key6 = null;
        PotionNote p2Key1 = null;
        Note bar2Key1 = null;
        Note bar2Key2 = null;

        for (TimeLine tl : model.getAllTimeLines()) {
            if (Math.abs(tl.getSection() - 1.0) < 1e-6) {
                if (tl.getNote(0) instanceof PotionNote pn) p1Key1 = pn;
                if (tl.getNote(5) instanceof PotionNote pn) p1Key6 = pn;
                if (tl.getNote(8) instanceof PotionNote pn) p2Key1 = pn;
            } else if (Math.abs(tl.getSection() - 2.0) < 1e-6) {
                bar2Key1 = tl.getNote(0);
                bar2Key2 = tl.getNote(1);
            }
        }

        assertNotNull(p1Key1, "F1が1P 1鍵(lane 0)に配置されること");
        assertEquals(10.0, p1Key1.getRecovery(), 1e-6);
        assertEquals(-1, p1Key1.getWav(), "ポーションノーツは無音(-1)であること");

        assertNotNull(p1Key6, "F8が1P 6鍵(lane 5)に配置されること");
        assertEquals(50.0, p1Key6.getRecovery(), 1e-6);

        assertNotNull(p2Key1, "G1が2P 1鍵(lane 8)に配置されること");
        assertEquals(5.0, p2Key1.getRecovery(), 1e-6);

        assertTrue(bar2Key1 instanceof NormalNote, "通常ノーツとポーションノーツの衝突時は通常ノーツが優先されること");
        assertTrue(bar2Key2 instanceof MineNote, "地雷ノーツとポーションノーツの衝突時は地雷ノーツが優先されること");

        // SongInformation のノーツ分布データ(index 7)への計上および文字列シリアライズ往復の検証
        bms.player.beatoraja.song.SongInformation info = new bms.player.beatoraja.song.SongInformation(model);
        int[][] dist = info.getDistributionValues();
        int totalPotionInDist = 0;
        for (int[] sec : dist) {
            assertEquals(8, sec.length);
            totalPotionInDist += sec[7];
        }
        assertEquals(3, totalPotionInDist, "小節001の3個のポーションノーツが分布データ(index 7)に記録されること");
        assertTrue(info.getDistribution().startsWith("$"), "ポーションノーツを含む分布文字列は$プレフィックスで保存されること");

        bms.player.beatoraja.song.SongInformation restored = new bms.player.beatoraja.song.SongInformation();
        restored.setDistribution(info.getDistribution());
        int totalRestoredPotion = 0;
        for (int[] sec : restored.getDistributionValues()) {
            totalRestoredPotion += sec[7];
        }
        assertEquals(3, totalRestoredPotion, "シリアライズ文字列からポーションノーツ数が復元されること");
    }

    @Test
    public void testSectionLineChannelST() {
        String bmsContent = String.join("\n",
                "#PLAYER 1",
                "#TITLE Section Line ST Test",
                "#BPM 120",
                "#SCROLL01 2.0",
                "#STOP01 48",
                "#WAV01 a.wav",
                // 小節001: 4分位置(0.25, 0.5, 0.75)にST定義 (01, FF, 0A)、0.5でSCROLL変化、0.25でSTOP
                "#00109:00010000",
                "#001SC:0001",
                "#001ST:0001FF0A",
                // 小節002: 小節倍率1.5倍、中間(pos=0.5 -> section=2.75)にST定義、ノーツは1個のみ
                "#00202:1.5",
                "#00211:01",
                "#002ST:0001"
        );
        BMSDecoder decoder = new BMSDecoder();
        BMSModel model = decoder.decode(bmsContent.getBytes(StandardCharsets.US_ASCII), false, null);
        assertNotNull(model);
        assertEquals(Mode.BEAT_5K, model.getMode(), "STチャンネルが鍵盤モード判別に影響しないこと");
        assertEquals(1, model.getTotalNotes(), "STチャンネルが総ノーツ数に計上されないこと");

        TimeLine tlBar0 = null;
        TimeLine tlBar1 = null;
        TimeLine tlBar1Pos25 = null;
        TimeLine tlBar1Pos50 = null;
        TimeLine tlBar1Pos75 = null;
        TimeLine tlBar2 = null;
        TimeLine tlBar2Pos50 = null;

        for (TimeLine tl : model.getAllTimeLines()) {
            if (Math.abs(tl.getSection() - 0.0) < 1e-6) tlBar0 = tl;
            else if (Math.abs(tl.getSection() - 1.0) < 1e-6) tlBar1 = tl;
            else if (Math.abs(tl.getSection() - 1.25) < 1e-6) tlBar1Pos25 = tl;
            else if (Math.abs(tl.getSection() - 1.5) < 1e-6) tlBar1Pos50 = tl;
            else if (Math.abs(tl.getSection() - 1.75) < 1e-6) tlBar1Pos75 = tl;
            else if (Math.abs(tl.getSection() - 2.0) < 1e-6) tlBar2 = tl;
            else if (Math.abs(tl.getSection() - 2.75) < 1e-6) tlBar2Pos50 = tl;
        }

        assertNotNull(tlBar0);
        assertTrue(tlBar0.getSectionLine(), "小節000先頭の自動小節線が維持されること");
        assertNotNull(tlBar1);
        assertTrue(tlBar1.getSectionLine(), "小節001先頭の自動小節線が維持されること");

        assertNotNull(tlBar1Pos25, "001STのpos=0.25にTimeLineが生成されること");
        assertTrue(tlBar1Pos25.getSectionLine(), "001STのpos=0.25に小節線が設定されること");
        assertTrue(tlBar1Pos25.getStop() > 0, "同位置のSTOP定義と共存できること");

        assertNotNull(tlBar1Pos50, "001STのpos=0.50にTimeLineが生成されること");
        assertTrue(tlBar1Pos50.getSectionLine(), "001STのpos=0.50に小節線が設定されること");
        assertEquals(2.0, tlBar1Pos50.getScroll(), 1e-6, "同位置の#SCROLL定義が反映されること");

        assertNotNull(tlBar1Pos75, "001STのpos=0.75にTimeLineが生成されること");
        assertTrue(tlBar1Pos75.getSectionLine(), "001STのpos=0.75に小節線が設定されること");
        assertEquals(2.0, tlBar1Pos75.getScroll(), 1e-6, "直前の#SCROLL定義を引き継いでいること");

        assertNotNull(tlBar2);
        assertTrue(tlBar2.getSectionLine(), "小節002先頭の自動小節線が維持されること");

        assertNotNull(tlBar2Pos50, "小節倍率1.5倍の小節002のpos=0.5(section=2.75)にTimeLineが生成されること");
        assertTrue(tlBar2Pos50.getSectionLine(), "002STのpos=0.5に小節線が設定されること");
    }

    @Test
    public void testDummyNotesDecodeAndModifiers() throws Exception {
        String bmsContent = String.join("\n",
                "#PLAYER 1",
                "#TITLE Dummy Note Test",
                "#BPM 120",
                "#LNMODE 2",
                "#WAV01 a.wav",
                "#001H1:01",
                "#001J2:0101",
                "#001L3:10",
                "#001N4:10",
                "#001O8:05",
                "#00211:01",
                "#002H1:01"
        );
        BMSDecoder decoder = new BMSDecoder();
        BMSModel model = decoder.decode(bmsContent.getBytes(StandardCharsets.US_ASCII), false, null);
        assertNotNull(model);
        // O8 (2P 6鍵のダミーポーション) により BEAT_14K に昇格すること
        assertEquals(Mode.BEAT_14K, model.getMode());
        // 総ノーツ数は小節002の1P 1鍵の本物通常ノーツ1個のみであること
        assertEquals(1, model.getTotalNotes());
        assertEquals(0, BMSModelUtils.getTotalNotes(model, BMSModelUtils.TOTALNOTES_LONG_KEY));
        assertEquals(0, BMSModelUtils.getTotalNotes(model, BMSModelUtils.TOTALNOTES_MINE));
        assertFalse(model.containsLongNote(), "ダミーLNのみの場合はcontainsLongNoteがfalseであること");
        assertFalse(model.containsMineNote(), "ダミー地雷のみの場合はcontainsMineNoteがfalseであること");

        Note dummyNormal = null;
        LongNote dummyLnStart = null;
        LongNote dummyLnEnd = null;
        MineNote dummyMine = null;
        PotionNote dummyPotion1P = null;
        PotionNote dummyPotion2P = null;
        Note bar2Key1 = null;

        for (TimeLine tl : model.getAllTimeLines()) {
            if (Math.abs(tl.getSection() - 1.0) < 1e-6) {
                dummyNormal = tl.getNote(0);
                if (tl.getNote(1) instanceof LongNote ln) dummyLnStart = ln;
                if (tl.getNote(2) instanceof MineNote mn) dummyMine = mn;
                if (tl.getNote(3) instanceof PotionNote pn) dummyPotion1P = pn;
                if (tl.getNote(13) instanceof PotionNote pn2) dummyPotion2P = pn2;
            } else if (Math.abs(tl.getSection() - 1.5) < 1e-6) {
                if (tl.getNote(1) instanceof LongNote ln) dummyLnEnd = ln;
            } else if (Math.abs(tl.getSection() - 2.0) < 1e-6) {
                bar2Key1 = tl.getNote(0);
            }
        }

        assertTrue(dummyNormal instanceof NormalNote && dummyNormal.isDummy(), "H1がダミー通常ノーツとして配置されること");
        assertEquals(-1, dummyNormal.getWav(), "ダミー通常ノーツは無音(-1)であること");

        assertNotNull(dummyLnStart, "J2始点がダミーLNとして配置されること");
        assertTrue(dummyLnStart.isDummy());
        assertEquals(LongNote.TYPE_CHARGENOTE, dummyLnStart.getType(), "#LNMODE 2がダミーLNに反映されること");
        assertNotNull(dummyLnEnd, "J2終点がダミーLNとして配置されること");
        assertTrue(dummyLnEnd.isDummy() && dummyLnEnd.isEnd());
        assertSame(dummyLnEnd, dummyLnStart.getPair());

        assertNotNull(dummyMine, "L3がダミー地雷として配置されること");
        assertTrue(dummyMine.isDummy());
        assertEquals(0.0, dummyMine.getDamage(), 1e-6);
        assertEquals(-1, dummyMine.getWav());

        assertNotNull(dummyPotion1P, "N4がダミーポーションとして配置されること");
        assertTrue(dummyPotion1P.isDummy());
        assertEquals(0.0, dummyPotion1P.getRecovery(), 1e-6);

        assertNotNull(dummyPotion2P, "O8が2P 6鍵(lane 13)にダミーポーションとして配置されること");
        assertTrue(dummyPotion2P.isDummy());

        assertTrue(bar2Key1 instanceof NormalNote && !bar2Key1.isDummy(), "本物ノーツとダミーノーツの衝突時は本物が優先されること");

        // SongInformation の集計からダミーノーツが除外されていること
        bms.player.beatoraja.song.SongInformation info = new bms.player.beatoraja.song.SongInformation(model);
        assertEquals(1, info.getN());
        assertEquals(0, info.getLn());
        int totalDistCount = 0;
        for (int[] sec : info.getDistributionValues()) {
            for (int v : sec) totalDistCount += v;
        }
        assertEquals(1, totalDistCount, "分布グラフ配列にダミーノーツが一切計上されないこと");

        // LongNoteModifier(REMOVE) でダミーLNがダミー通常ノーツに変換され、ASSISTにならないこと
        bms.player.beatoraja.pattern.LongNoteModifier lnMod = new bms.player.beatoraja.pattern.LongNoteModifier(0, 1.0);
        lnMod.modify(model);
        assertEquals(bms.player.beatoraja.pattern.PatternModifier.AssistLevel.NONE, lnMod.getAssistLevel());
        for (TimeLine tl : model.getAllTimeLines()) {
            if (Math.abs(tl.getSection() - 1.0) < 1e-6) {
                assertTrue(tl.getNote(1) instanceof NormalNote && tl.getNote(1).isDummy(), "REMOVE時にダミーLN始点がダミー通常ノーツになること");
            } else if (Math.abs(tl.getSection() - 1.5) < 1e-6) {
                assertNull(tl.getNote(1), "REMOVE時にダミーLN終点が消去されること");
            }
        }

        // MineNoteModifier(REMOVE) でダミー地雷が消去され、LIGHT_ASSISTにならないこと
        bms.player.beatoraja.pattern.MineNoteModifier mineMod = new bms.player.beatoraja.pattern.MineNoteModifier(0);
        mineMod.modify(model);
        assertEquals(bms.player.beatoraja.pattern.PatternModifier.AssistLevel.NONE, mineMod.getAssistLevel());
        for (TimeLine tl : model.getAllTimeLines()) {
            if (Math.abs(tl.getSection() - 1.0) < 1e-6) {
                assertNull(tl.getNote(2), "mineMode REMOVE時にダミー地雷も消去されること");
            }
        }

        // ExtraNoteModifier で残りの全ダミーノーツおよび通常の地雷・ポーションノートが除去されること
        for (TimeLine tl : model.getAllTimeLines()) {
            if (Math.abs(tl.getSection() - 2.0) < 1e-6) {
                tl.setNote(4, new MineNote(-1, 10.0));
                tl.setNote(5, new PotionNote(-1, 15.0));
            }
        }
        bms.player.beatoraja.pattern.ExtraNoteModifier extraMod = new bms.player.beatoraja.pattern.ExtraNoteModifier(0, 1, false);
        extraMod.modify(model);
        for (TimeLine tl : model.getAllTimeLines()) {
            for (int lane = 0; lane < model.getMode().key; lane++) {
                if (tl.existNote(lane)) {
                    Note n = tl.getNote(lane);
                    assertFalse(n.isDummy(), "EXTRAモード適用後にダミーノーツが残っていないこと");
                    assertFalse(n instanceof MineNote, "EXTRAモード適用後に地雷ノーツが残っていないこと");
                    assertFalse(n instanceof PotionNote, "EXTRAモード適用後にポーションノーツが残っていないこと");
                }
            }
        }
    }

    @Test
    void testUtf8EncodingAndBase62PreScan() {
        // UTF-8タイトルと、#WAV定義より後ろに記述された#BASE 62が正しく解釈されることを確認
        String bmsContent = String.join("\r\n",
                "#PLAYER 1",
                "#TITLE テスト楽曲🔥",
                "#BPM 120",
                "#WAV0a base62.wav",
                "#00111:0a",
                "#BASE 62"
        );
        BMSDecoder decoder = new BMSDecoder();
        BMSModel model = decoder.decode(bmsContent.getBytes(StandardCharsets.UTF_8), false, null);
        assertNotNull(model);
        assertEquals("テスト楽曲🔥", model.getTitle());
        assertEquals(62, model.getBase());
        assertEquals(1, model.getTotalNotes());
        assertEquals(1, model.getWavList().length);
        assertEquals("base62.wav", model.getWavList()[0]);
    }
}

