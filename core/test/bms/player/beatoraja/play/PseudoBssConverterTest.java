package bms.player.beatoraja.play;

import bms.model.*;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PseudoBssConverterTest {

    private static final int SCRATCH = 7;

    /**
     * 16 分割の 1 小節分のチャンネルデータを作る
     */
    private static String slots16(int... setSlots) {
        String[] s = new String[16];
        java.util.Arrays.fill(s, "00");
        for (int i = 0; i < setSlots.length; i += 2) {
            s[setSlots[i]] = String.format("%02d", setSlots[i + 1]);
        }
        return String.join("", s);
    }

    private static BMSModel decode(int lntype, String... lines) {
        List<String> all = new ArrayList<>(List.of(
                "#PLAYER 1", "#TITLE pseudo bss", "#BPM 120",
                "#WAV01 a.wav", "#WAV02 b.wav", "#WAV03 c.wav", "#WAV04 d.wav",
                // 7KEY 判定用
                "#00118:" + slots16(0, 4)));
        all.addAll(List.of(lines));
        BMSModel model = new BMSDecoder(lntype).decode(String.join("\n", all).getBytes(StandardCharsets.US_ASCII), false, null);
        assertNotNull(model);
        assertEquals(Mode.BEAT_7K, model.getMode());
        assertEquals(lntype, model.getLntype());
        return model;
    }

    private static List<Note> scratchNotes(BMSModel model) {
        List<Note> notes = new ArrayList<>();
        for (TimeLine tl : model.getAllTimeLines()) {
            if (tl.getNote(SCRATCH) != null) {
                notes.add(tl.getNote(SCRATCH));
            }
        }
        return notes;
    }

    /** スクラッチ LN (slot0-8) + 16 分後 (slot9) の通常スクラッチ */
    private static String[] pseudoBss() {
        return new String[] {
                "#00156:" + slots16(0, 1, 8, 1),
                "#00116:" + slots16(9, 3),
        };
    }

    @Test
    void convertsPseudoBssInChargeNoteMode() {
        BMSModel model = decode(BMSModel.LNTYPE_CHARGENOTE, pseudoBss());
        int before = model.getTotalNotes();
        assertEquals(3, scratchNotes(model).size());

        assertEquals(1, PseudoBssConverter.convert(model).bss());

        List<Note> notes = scratchNotes(model);
        assertEquals(2, notes.size());
        LongNote start = assertInstanceOf(LongNote.class, notes.get(0));
        LongNote end = assertInstanceOf(LongNote.class, notes.get(1));
        assertFalse(start.isEnd());
        assertTrue(end.isEnd());
        assertSame(end, start.getPair());
        assertSame(start, end.getPair());
        // 終端は通常ノーツの位置 (1 + 9/16 小節) に移動し、通常ノーツの音を引き継ぐ
        assertEquals(1 + 9.0 / 16, end.getSection(), 1e-9);
        assertTrue(end.getWav() >= 0);
        assertEquals(before - 1, model.getTotalNotes());
    }

    @Test
    void convertsInHellChargeNoteMode() {
        BMSModel model = decode(BMSModel.LNTYPE_HELLCHARGENOTE, pseudoBss());
        assertEquals(1, PseudoBssConverter.convert(model).bss());
        assertEquals(2, scratchNotes(model).size());
    }

    @Test
    void doesNothingInLongNoteMode() {
        BMSModel model = decode(BMSModel.LNTYPE_LONGNOTE, pseudoBss());
        assertEquals(0, PseudoBssConverter.convert(model).bss());
        assertEquals(3, scratchNotes(model).size());
    }

    @Test
    void ignoresNoteFartherThanSixteenth() {
        BMSModel model = decode(BMSModel.LNTYPE_CHARGENOTE,
                "#00156:" + slots16(0, 1, 8, 1),
                "#00116:" + slots16(10, 3));
        assertEquals(0, PseudoBssConverter.convert(model).bss());
        assertEquals(3, scratchNotes(model).size());
    }

    @Test
    void ignoresKeyLaneLongNote() {
        BMSModel model = decode(BMSModel.LNTYPE_CHARGENOTE,
                "#00151:" + slots16(0, 1, 8, 1),
                "#00111:" + slots16(9, 3));
        assertEquals(0, PseudoBssConverter.convert(model).bss());
    }

    @Test
    void ignoresExplicitLongNoteType() {
        // #LNMODE 1 で LN と明示された譜面は譜面作者の意図どおり扱う
        BMSModel model = decode(BMSModel.LNTYPE_CHARGENOTE, "#LNMODE 1",
                "#00156:" + slots16(0, 1, 8, 1),
                "#00116:" + slots16(9, 3));
        assertEquals(0, PseudoBssConverter.convert(model).bss());
    }

    @Test
    void ignoresMineNoteAfterLongNote() {
        BMSModel model = decode(BMSModel.LNTYPE_CHARGENOTE,
                "#00156:" + slots16(0, 1, 8, 1),
                "#001D6:" + slots16(9, 10));
        assertEquals(0, PseudoBssConverter.convert(model).bss());
    }

    @Test
    void convertsMultiplePseudoBssAcrossMeasures() {
        BMSModel model = decode(BMSModel.LNTYPE_CHARGENOTE,
                "#00156:" + slots16(0, 1, 15, 1),
                "#00216:" + slots16(0, 3, 9, 4),
                "#00256:" + slots16(4, 2, 8, 2));
        assertEquals(2, PseudoBssConverter.convert(model).bss());
        List<Note> notes = scratchNotes(model);
        assertEquals(4, notes.size());
        assertEquals(2.0, notes.get(1).getSection(), 1e-9);
        assertEquals(2 + 9.0 / 16, notes.get(3).getSection(), 1e-9);
    }

    // ---- 曲終了判定の回帰テスト ----
    // BMSPlayer は judge.getPastNotes() == songdata.getNotes() (= model.getTotalNotes()) で曲終了に移行する。
    // JudgeManager は model.getLanes() のノーツを順に処理し、LN は start → pair の順で処理中状態を解放する。
    // 変換後に (1) ペアの壊れた LN が残る、(2) 判定対象数と総ノーツ数がずれる、のどちらかが起きると曲終了しない。

    /**
     * レーンごとに JudgeManager が判定するノーツ数を数え、LN のペア整合性を検証する
     */
    private static int assertLanesJudgeable(BMSModel model) {
        final boolean cn = PseudoBssConverter.isTargetLntype(model.getLntype());
        int judged = 0;
        Lane[] lanes = model.getLanes();
        for (int lane = 0; lane < lanes.length; lane++) {
            LongNote open = null;
            for (Note n : lanes[lane].getNotes()) {
                if (n.isDummy() || n instanceof MineNote) {
                    continue;
                }
                if (n instanceof LongNote ln) {
                    if (!ln.isEnd()) {
                        assertNull(open, "LN 処理中に次の LN 始端がある lane=" + lane + " section=" + ln.getSection());
                        assertNotNull(ln.getPair(), "終端のない LN 始端 lane=" + lane);
                        open = ln;
                        if (!ln.isFreeZoneInner()) {
                            judged++;
                        }
                    } else {
                        assertNotNull(open, "始端のない LN 終端 lane=" + lane + " section=" + ln.getSection());
                        assertSame(open.getPair(), ln, "LN のペアが一致しない lane=" + lane);
                        assertSame(open, ln.getPair(), "LN 終端の逆ペアが一致しない lane=" + lane);
                        assertTrue(ln.getSection() > open.getSection());
                        open = null;
                        if (cn && !ln.isFreeZoneInner()) {
                            judged++;
                        }
                    }
                } else if (n instanceof NormalNote nn) {
                    assertNull(open, "LN 内に通常ノーツがある lane=" + lane + " section=" + n.getSection());
                    if (!nn.isFreeZoneInner()) {
                        judged++;
                    }
                }
            }
            assertNull(open, "閉じていない LN が残っている lane=" + lane);
        }
        return judged;
    }

    @Test
    void judgeableNoteCountMatchesTotalNotesAfterConversion() {
        BMSModel model = decode(BMSModel.LNTYPE_CHARGENOTE,
                // 小節 1: 疑似 BSS + 鍵盤 LN + 通常ノーツ
                "#00156:" + slots16(0, 1, 8, 1),
                "#00116:" + slots16(9, 3, 14, 4),
                "#00151:" + slots16(0, 1, 4, 1),
                "#00112:" + slots16(2, 2),
                // 小節 2-3: 小節をまたぐ疑似 BSS (LN 2.25-2.9375 + 3.0 のノーツ)
                "#00256:" + slots16(4, 2, 15, 2),
                "#00316:" + slots16(0, 3),
                // 小節 4: 変換対象外 (LN 終端から 4 分離れたノーツ)
                "#00456:" + slots16(4, 2, 8, 2),
                "#00416:" + slots16(12, 4));
        assertEquals(model.getTotalNotes(), assertLanesJudgeable(model));

        assertEquals(2, PseudoBssConverter.convert(model).bss());
        assertEquals(model.getTotalNotes(), assertLanesJudgeable(model));
        // 最終ノーツ時刻 (曲終了待ち時間の基準) が変換で前に縮まない
        assertTrue(model.getLastNoteTime() > 0);
    }

    @Test
    void conditionalBranchRebuildKeepsCountsConsistent() {
        String bms = String.join("\n",
                "#PLAYER 1", "#BPM 120", "#TOTAL 300",
                "#WAV01 a.wav", "#WAV02 b.wav", "#WAV03 c.wav",
                "#00118:" + slots16(0, 1),
                // 評価位置 (2.0) をまたぐ疑似 BSS: LN 1.0-1.9375 + 2.0 のノーツ
                "#00156:" + slots16(0, 1, 15, 1),
                "#00216:" + slots16(0, 3),
                "#CONDITIONAL 2.0",
                "#WHEN MISS >= 0",
                "#00356:" + slots16(0, 2, 8, 2),
                "#00316:" + slots16(9, 3),
                "#DEFAULT",
                "#00311:" + slots16(0, 1),
                "#ENDCONDITIONAL",
                "");
        BMSModel model = new BMSDecoder(BMSModel.LNTYPE_CHARGENOTE)
                .decode(bms.getBytes(StandardCharsets.US_ASCII), false, null);
        assertNotNull(model);
        assertEquals(BMSModel.LNTYPE_CHARGENOTE, model.getLntype());

        // 初回ロード時の変換 (PlayerResource.loadBMSModel 相当)
        assertEquals(1, PseudoBssConverter.convert(model).bss());
        BMSPlayerRule.validate(model);
        assertEquals(model.getTotalNotes(), assertLanesJudgeable(model));

        // BMSPlayer の conditionalModifierApplier と同じく分岐後モデルにも変換をかける
        ConditionalBranchController controller = new ConditionalBranchController(
                null, model, null, model.getTotalNotes(), model.getTotal(),
                0, false, 0, 100, PseudoBssConverter::convert);
        controller.setCustomVariableProvider(v -> 0.0);
        controller.evaluateUpTo(controller.getNextEvalMicroTime());
        assertArrayEquals(new int[] { 0 }, controller.getSelectedBranches());

        assertEquals(model.getTotalNotes(), assertLanesJudgeable(model));
        // 18ch 1 + 疑似BSS(評価前) 2 + 分岐側疑似BSS 2 = 5
        assertEquals(5, model.getTotalNotes());
        List<Note> notes = scratchNotes(model);
        assertEquals(4, notes.size());
        assertEquals(2.0, notes.get(1).getSection(), 1e-9);
        assertEquals(3 + 9.0 / 16, notes.get(3).getSection(), 1e-9);
    }

    // ---- 疑似 MSS (スクラッチ LN の連続) ----

    /** LN1 1.0-1.5、LN2 1.5625-2.0 (16 分後に開始)、2.0625 に通常ノーツ */
    private static String[] pseudoMss() {
        return new String[] {
                "#00156:" + slots16(0, 1, 8, 1, 9, 2),
                "#00256:" + slots16(0, 2),
                "#00216:" + slots16(1, 3),
        };
    }

    private static void assertTimeLinesOrdered(BMSModel model) {
        TimeLine[] tls = model.getAllTimeLines();
        for (int i = 1; i < tls.length; i++) {
            assertTrue(tls[i].getSection() > tls[i - 1].getSection(), "section が単調増加でない index=" + i);
            assertTrue(tls[i].getMicroTime() >= tls[i - 1].getMicroTime(), "time が単調増加でない index=" + i);
        }
    }

    @Test
    void convertsPseudoMssIntoLinkedSegments() {
        BMSModel model = decode(BMSModel.LNTYPE_CHARGENOTE, pseudoMss());
        // 変換前: LN1(2) + LN2(2) + 通常ノーツ(1) + 18ch(1)
        assertEquals(6, model.getTotalNotes());

        PseudoBssConverter.Result r = PseudoBssConverter.convert(model);
        assertEquals(1, r.bss());
        assertEquals(1, r.mssLinks());
        assertTimeLinesOrdered(model);

        List<Note> notes = scratchNotes(model);
        assertEquals(4, notes.size());
        LongNote s1 = (LongNote) notes.get(0);
        LongNote e1 = (LongNote) notes.get(1);
        LongNote s2 = (LongNote) notes.get(2);
        LongNote e2 = (LongNote) notes.get(3);
        assertSame(e1, s1.getPair());
        assertSame(e2, s2.getPair());
        // 中間ノート: 区間 1 の終端は区間 2 の始端の直前に移動する
        assertEquals(s2.getSection() - PseudoBssConverter.MSS_LINK_SECTION_OFFSET, e1.getSection(), 1e-12);
        assertEquals(s2.getMicroTime() - PseudoBssConverter.MSS_LINK_TIME_OFFSET, e1.getMicroTime());
        assertEquals(1 + 9.0 / 16, s2.getSection(), 1e-9);
        // 最後の区間は通常ノーツの位置で BSS 終端になる
        assertEquals(2 + 1.0 / 16, e2.getSection(), 1e-9);

        // 総ノーツ数 = (始点ノート + 中間ノート) * 2 + 18ch
        assertEquals((1 + 1) * 2 + 1, model.getTotalNotes());
        assertEquals(model.getTotalNotes(), assertLanesJudgeable(model));

        // 判定側の連結検出
        Note[] lane = model.getLanes()[SCRATCH].getNotes();
        assertSame(s2, JudgeManager.findMssLinkedStart(lane, e1));
        assertNull(JudgeManager.findMssLinkedStart(lane, e2));
    }

    @Test
    void linksLongChainWithoutTrailingNote() {
        // 3 本の LN が 16 分間隔で連続し、最後は通常ノーツなし
        BMSModel model = decode(BMSModel.LNTYPE_HELLCHARGENOTE,
                "#00156:" + slots16(0, 1, 4, 1, 5, 2, 8, 2, 9, 3, 12, 3));
        PseudoBssConverter.Result r = PseudoBssConverter.convert(model);
        assertEquals(0, r.bss());
        assertEquals(2, r.mssLinks());
        assertTimeLinesOrdered(model);
        assertEquals(3 * 2 + 1, model.getTotalNotes());
        assertEquals(model.getTotalNotes(), assertLanesJudgeable(model));
        Note[] lane = model.getLanes()[SCRATCH].getNotes();
        assertEquals(6, lane.length);
        assertSame(lane[2], JudgeManager.findMssLinkedStart(lane, (LongNote) lane[1]));
        assertSame(lane[4], JudgeManager.findMssLinkedStart(lane, (LongNote) lane[3]));
    }

    @Test
    void mssLinkLookupDoesNotDependOnObjectIdentity() {
        BMSModel model = decode(BMSModel.LNTYPE_CHARGENOTE, pseudoMss());
        PseudoBssConverter.convert(model);
        Note[] lane = model.getLanes()[SCRATCH].getNotes();
        // 譜面オプションの複製で別オブジェクトになった終端でも、時刻で次区間の始端を見つける
        LongNote copiedEnd = (LongNote) lane[1].clone();
        assertNotSame(lane[1], copiedEnd);
        assertSame(lane[2], JudgeManager.findMssLinkedStart(lane, copiedEnd));
        // 判定済みの始端は対象外 (二重判定しない)
        lane[2].setState(1);
        assertNull(JudgeManager.findMssLinkedStart(lane, (LongNote) lane[1]));
    }

    @Test
    void mssStartJudgeCountsLateBadWithinWindow() {
        // PG/GR/GD/BD/POOR の判定幅 (dmtime = ノーツ時刻 - 押下時刻、負が遅れ)
        long[][] w = { { -20, 20 }, { -40, 40 }, { -80, 80 }, { -120, 120 }, { -150, 500 } };
        assertEquals(0, JudgeManager.mssStartJudge(w, 0));
        assertEquals(1, JudgeManager.mssStartJudge(w, 30));
        assertEquals(2, JudgeManager.mssStartJudge(w, -60));
        // 通常の LN 始端では判定しない「遅い BAD」も、MSS の切り返しでは BAD として数える
        assertEquals(3, JudgeManager.mssStartJudge(w, -100));
        // BAD 幅外 (POOR 幅含む) は切り返しでは判定しない
        assertEquals(-1, JudgeManager.mssStartJudge(w, -130));
        assertEquals(-1, JudgeManager.mssStartJudge(w, 200));
    }

    @Test
    void unconvertedConsecutiveChargeNotesAreNotLinked() {
        // 変換しなければ 16 分離れた CN 同士は MSS とみなさない (通常の CN 譜面に影響しない)
        BMSModel model = decode(BMSModel.LNTYPE_CHARGENOTE, pseudoMss());
        Note[] lane = model.getLanes()[SCRATCH].getNotes();
        assertNull(JudgeManager.findMssLinkedStart(lane, (LongNote) lane[1]));
    }

    @Test
    void doesNotLinkWhenGapIsLargerThanSixteenth() {
        BMSModel model = decode(BMSModel.LNTYPE_CHARGENOTE,
                "#00156:" + slots16(0, 1, 8, 1, 10, 2, 14, 2));
        assertTrue(PseudoBssConverter.convert(model).isEmpty());
    }

    @Test
    void legacyAssistTurnsMssIntoStartAndMidScratchNotes() {
        BMSModel model = decode(BMSModel.LNTYPE_CHARGENOTE, pseudoMss());
        PseudoBssConverter.convert(model);
        new bms.player.beatoraja.pattern.LongNoteModifier(0, 1.0).modify(model);
        List<Note> notes = scratchNotes(model);
        assertEquals(2, notes.size());
        assertInstanceOf(NormalNote.class, notes.get(0));
        assertInstanceOf(NormalNote.class, notes.get(1));
        assertEquals(1.0, notes.get(0).getSection(), 1e-9);
        assertEquals(1 + 9.0 / 16, notes.get(1).getSection(), 1e-9);
    }

    @Test
    void conditionalBranchWithMssKeepsCountsConsistent() {
        String bms = String.join("\n",
                "#PLAYER 1", "#BPM 120", "#TOTAL 300",
                "#WAV01 a.wav", "#WAV02 b.wav", "#WAV03 c.wav",
                "#00118:" + slots16(0, 1),
                // 評価位置 (2.0) をまたぐ疑似 MSS: LN 1.0-1.875, LN 1.9375-2.25, 2.3125 のノーツ
                "#00156:" + slots16(0, 1, 14, 1, 15, 2),
                "#00256:" + slots16(4, 2),
                "#00216:" + slots16(5, 3),
                "#CONDITIONAL 2.5",
                "#WHEN MISS >= 0",
                "#00356:" + slots16(0, 1, 4, 1, 5, 2, 8, 2),
                "#00316:" + slots16(9, 3),
                "#DEFAULT",
                "#00311:" + slots16(0, 1),
                "#ENDCONDITIONAL",
                "");
        BMSModel model = new BMSDecoder(BMSModel.LNTYPE_CHARGENOTE)
                .decode(bms.getBytes(StandardCharsets.US_ASCII), false, null);
        assertNotNull(model);
        PseudoBssConverter.Result r = PseudoBssConverter.convert(model);
        assertEquals(1, r.bss());
        assertEquals(1, r.mssLinks());
        BMSPlayerRule.validate(model);
        assertEquals(model.getTotalNotes(), assertLanesJudgeable(model));

        ConditionalBranchController controller = new ConditionalBranchController(
                null, model, null, model.getTotalNotes(), model.getTotal(),
                0, false, 0, 100, PseudoBssConverter::convert);
        controller.setCustomVariableProvider(v -> 0.0);
        controller.evaluateUpTo(controller.getNextEvalMicroTime());
        assertArrayEquals(new int[] { 0 }, controller.getSelectedBranches());

        assertTimeLinesOrdered(model);
        assertEquals(model.getTotalNotes(), assertLanesJudgeable(model));
        // 18ch 1 + 評価前の MSS 2 区間 (4) + 分岐側の MSS 2 区間 (4) = 9
        assertEquals(9, model.getTotalNotes());
    }
}
