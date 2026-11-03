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
}

