package bms.player.beatoraja.ir;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import bms.model.BMSDecoder;
import bms.model.BMSModel;
import bms.model.ChartInformation;
import bms.model.ChartSource;
import bms.player.beatoraja.ClearType;
import bms.player.beatoraja.CourseData;
import bms.player.beatoraja.IRConfig;
import bms.player.beatoraja.MainController;
import bms.player.beatoraja.ReplayData;
import bms.player.beatoraja.ScoreData;
import bms.player.beatoraja.song.SongData;

public class IRUtilTest {

    // 将来的に.bmc拡張子のBMS-IR送信制限を廃止する際は、対応するテストも削除すること。

    @Test
    public void testIsBmsIRByUrlAndName() {
        // URLによる判定
        assertTrue(IRUtil.isBmsIR("CustomIR", "https://bms-ir.org/"));
        assertTrue(IRUtil.isBmsIR("CustomIR", "https://bms-ir.org"));
        assertTrue(IRUtil.isBmsIR("CustomIR", "https://www.bms-ir.org/"));
        assertTrue(IRUtil.isBmsIR("CustomIR", "http://bms-ir.org/new"));
        assertTrue(IRUtil.isBmsIR(null, "bms-ir.org"));

        // IR名による判定
        assertTrue(IRUtil.isBmsIR("BMS-IR", null));
        assertTrue(IRUtil.isBmsIR("bms-ir", ""));
        assertTrue(IRUtil.isBmsIR("BMS-IR (lr2oraja-ed)", null));

        // 非BMS-IRの判定（誤検知しないこと）
        assertFalse(IRUtil.isBmsIR("rianIR", "https://rianir.link/"));
        assertFalse(IRUtil.isBmsIR("rianIR", "https://bms-ir.org/"));
        assertFalse(IRUtil.isBmsIR("Mocha-Repository", "https://mocha-repository.info/"));
        assertFalse(IRUtil.isBmsIR("OtherIR", "https://fake-bms-ir.org/"));
        assertFalse(IRUtil.isBmsIR("OtherIR", "https://example.com/?ref=https://bms-ir.org/"));
        assertFalse(IRUtil.isBmsIR(null, null));
        assertFalse(IRUtil.isBmsIR("", ""));
    }

    @Test
    public void testBmcSongScoreSendSkip() {
        SongData bmcSong = createSongWithPath("D:/bms/sample/chart.bmc");
        SongData upperBmcSong = createSongWithPath("D:/bms/sample/CHART.BMC");
        SongData archiveBmcSong = createSongWithPath("D:/bms/archive.zip!/folder/chart.bmc");
        SongData bmsSong = createSongWithPath("D:/bms/sample/chart.bms");
        SongData bmsonSong = createSongWithPath("D:/bms/sample/chart.bmson");

        // .bmc × BMS-IR はスキップされる
        assertTrue(IRUtil.shouldSkipBmcScoreSend("BMS-IR", "https://bms-ir.org/", bmcSong));
        assertTrue(IRUtil.shouldSkipBmcScoreSend("BMS-IR", null, upperBmcSong));
        assertTrue(IRUtil.shouldSkipBmcScoreSend("CustomName", "https://www.bms-ir.org/", archiveBmcSong));

        // .bmc × 他IR（rianIR等）はスキップされない
        assertFalse(IRUtil.shouldSkipBmcScoreSend("rianIR", "https://rianir.link/", bmcSong));
        assertFalse(IRUtil.shouldSkipBmcScoreSend("Mocha-Repository", "https://mocha-repository.info/", bmcSong));

        // 通常拡張子（.bms / .bmson）× BMS-IR はスキップされない
        assertFalse(IRUtil.shouldSkipBmcScoreSend("BMS-IR", "https://bms-ir.org/", bmsSong));
        assertFalse(IRUtil.shouldSkipBmcScoreSend("BMS-IR", "https://bms-ir.org/", bmsonSong));
    }

    @Test
    public void testBmcModelPathPriorityAndCourseSendSkip() {
        BMSModel bmcModel = decodeMinimalModel("D:/bms/conditional/branch.bmc");
        SongData modelBackedBmcSong = new SongData(bmcModel, false);
        SongData normalSong = createSongWithPath("D:/bms/normal/song.bme");

        assertTrue(IRUtil.shouldSkipBmcScoreSend("BMS-IR", "https://bms-ir.org/", modelBackedBmcSong));

        // .bmcを含むコースはBMS-IRへの送信がスキップされる
        CourseData mixedCourse = new CourseData();
        mixedCourse.setSong(new SongData[] { normalSong, modelBackedBmcSong });
        assertTrue(IRUtil.shouldSkipBmcScoreSend("BMS-IR", "https://bms-ir.org/", mixedCourse));
        assertFalse(IRUtil.shouldSkipBmcScoreSend("rianIR", "https://rianir.link/", mixedCourse));

        // .bmcを含まないコースはBMS-IRへ送信可能
        CourseData normalCourse = new CourseData();
        normalCourse.setSong(new SongData[] { normalSong });
        assertFalse(IRUtil.shouldSkipBmcScoreSend("BMS-IR", "https://bms-ir.org/", normalCourse));
    }

    @Test
    public void testShouldSkipPseudoBssScoreSend() {
        // 疑似BSS/MSS変換が適用されたプレイは rianIR のみ送信し、他IRはすべてスキップする
        assertFalse(IRUtil.shouldSkipPseudoBssScoreSend("rianIR", true));
        assertFalse(IRUtil.shouldSkipPseudoBssScoreSend("rianIR (dev)", true));
        assertTrue(IRUtil.shouldSkipPseudoBssScoreSend("BMS-IR", true));
        assertTrue(IRUtil.shouldSkipPseudoBssScoreSend("Mocha-Repository", true));
        assertTrue(IRUtil.shouldSkipPseudoBssScoreSend("LR2IR", true));
        assertTrue(IRUtil.shouldSkipPseudoBssScoreSend("", true));
        assertTrue(IRUtil.shouldSkipPseudoBssScoreSend(null, true));

        // 疑似BSS/MSS変換が未発生の場合は従来通りどのIRへも送信可能
        assertFalse(IRUtil.shouldSkipPseudoBssScoreSend("rianIR", false));
        assertFalse(IRUtil.shouldSkipPseudoBssScoreSend("BMS-IR", false));
        assertFalse(IRUtil.shouldSkipPseudoBssScoreSend("Mocha-Repository", false));
    }

    @Test
    public void testPseudoBssIrLntypeAndProtocolRoundTrip() {
        ScoreData localCnScore = new ScoreData();
        localCnScore.setSha256("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
        localCnScore.setMode(IRScoreData.LNTYPE_CN);
        localCnScore.setClear(ClearType.Normal.id);
        localCnScore.setEpg(100);

        // ローカルScoreDataのmode(1:CN)を書き換えずに、IRScoreDataでCN+(4)・pseudoBss=trueになること
        IRScoreData cnPlusScore = new IRScoreData(localCnScore, IRScoreData.LNTYPE_CN, true);
        assertEquals(IRScoreData.LNTYPE_CN, localCnScore.getMode());
        assertEquals(IRScoreData.LNTYPE_CN_PLUS, cnPlusScore.lntype);
        assertTrue(cnPlusScore.pseudoBss);
        assertEquals("CN+", cnPlusScore.getLntypeName());

        // IRWorkerProtocolのDTOシリアライズを経由してもCN+(4)とpseudoBss=trueが保持されること
        IRScoreData roundTrippedCnPlus = IRWorkerProtocol.Score.from(cnPlusScore).toIRScoreData();
        assertEquals(IRScoreData.LNTYPE_CN_PLUS, roundTrippedCnPlus.lntype);
        assertTrue(roundTrippedCnPlus.pseudoBss);
        assertEquals(IRScoreData.LNTYPE_CN, roundTrippedCnPlus.convertToScoreData().getMode());

        // HCNモード時はHCN+(5)として扱われること
        IRScoreData hcnPlusScore = new IRScoreData(localCnScore, IRScoreData.LNTYPE_HCN, true);
        assertEquals(IRScoreData.LNTYPE_HCN_PLUS, hcnPlusScore.lntype);
        assertTrue(hcnPlusScore.pseudoBss);
        assertEquals("HCN+", hcnPlusScore.getLntypeName());
        assertEquals(IRScoreData.LNTYPE_HCN, hcnPlusScore.convertToScoreData().getMode());

        // コースデータでもHCN+(5)・pseudoBss=trueが保持され、プロトコル往復すること
        CourseData course = new CourseData();
        course.setName("Pseudo BSS Course");
        course.setSong(new SongData[] { createSongWithPath("D:/bms/sample/a.bms") });
        IRCourseData irCourse = new IRCourseData(course, IRScoreData.LNTYPE_HCN, true);
        IRCourseData roundTrippedCourse = IRWorkerProtocol.Course.from(irCourse).toIRCourseData();
        assertEquals(IRScoreData.LNTYPE_HCN_PLUS, roundTrippedCourse.lntype);
        assertTrue(roundTrippedCourse.pseudoBss);

        // MainController.IRSendStatus 経由の送信でも CN+ が渡されること
        IRScoreData[] captured = new IRScoreData[1];
        IRConnection stubIr = new TestIRConnectionBase() {
            @Override
            public IRResponse<Object> sendPlayData(IRChartData model, IRScoreData score) {
                captured[0] = score;
                return new IRResponse<Object>() {
                    @Override public boolean isSucceeded() { return true; }
                    @Override public String getMessage() { return "ok"; }
                    @Override public Object getData() { return null; }
                };
            }
        };
        MainController.IRSendStatus sendStatus = new MainController.IRSendStatus(
                stubIr, createSongWithPath("D:/bms/sample/a.bms"), localCnScore, new ReplayData(),
                IRScoreData.LNTYPE_CN, true);
        assertTrue(sendStatus.send());
        assertNotNull(captured[0]);
        assertEquals(IRScoreData.LNTYPE_CN_PLUS, captured[0].lntype);
        assertTrue(captured[0].pseudoBss);
        assertFalse(captured[0].keystrokeOnly);
    }

    @Test
    public void testKeystrokeOnlySendFilteringAndProtocolRoundTrip() {
        // 1. IR別スキップ判定（打鍵数のみ送信は rianIR 専用）
        assertFalse(IRUtil.shouldSkipKeystrokeOnlyScoreSend("rianIR", false));
        assertFalse(IRUtil.shouldSkipKeystrokeOnlyScoreSend("Mocha-Repository", false));
        assertFalse(IRUtil.shouldSkipKeystrokeOnlyScoreSend("rianIR", true));
        assertTrue(IRUtil.shouldSkipKeystrokeOnlyScoreSend("Mocha-Repository", true));
        assertTrue(IRUtil.shouldSkipKeystrokeOnlyScoreSend("BMS-IR", true));
        assertTrue(IRUtil.shouldSkipKeystrokeOnlyScoreSend("LR2IR", true));
        assertTrue(IRUtil.shouldSkipKeystrokeOnlyScoreSend(null, true));

        // 2. 有効打鍵数判定（PGREAT / GREAT / GOOD / BAD のみ対象、POOR / MISS のみは除外）
        ScoreData zeroKeystrokes = new ScoreData();
        zeroKeystrokes.setEpr(20);
        zeroKeystrokes.setEms(50);
        assertFalse(IRUtil.hasValidKeystrokes(null));
        assertFalse(IRUtil.hasValidKeystrokes(zeroKeystrokes));

        ScoreData validKeystrokes = new ScoreData();
        validKeystrokes.setEpg(100);
        validKeystrokes.setLgr(25);
        validKeystrokes.setEbd(3);
        validKeystrokes.setClear(ClearType.LightAssistEasy.id);
        validKeystrokes.setMinbp(15);
        assertTrue(IRUtil.hasValidKeystrokes(validKeystrokes));

        ScoreData higherBest = new ScoreData();
        higherBest.setEpg(500);
        higherBest.setClear(ClearType.Hard.id);
        higherBest.setCombo(1000);
        higherBest.setMinbp(1);

        // 3. スコア非保存オプション（H-RANDOM / DBR 等）時は IR_SEND_UPDATE_SCORE でも毎回送信し、IR_SEND_COMPLETE_SONG のみ完奏条件に従う
        assertTrue(IRUtil.shouldSendSinglePlayToIR(false, false, true, IRConfig.IR_SEND_ALWAYS, false, validKeystrokes, higherBest));
        assertTrue(IRUtil.shouldSendSinglePlayToIR(false, false, true, IRConfig.IR_SEND_UPDATE_SCORE, false, validKeystrokes, higherBest));
        assertFalse(IRUtil.shouldSendSinglePlayToIR(false, false, true, IRConfig.IR_SEND_COMPLETE_SONG, false, validKeystrokes, higherBest));
        assertTrue(IRUtil.shouldSendSinglePlayToIR(false, false, true, IRConfig.IR_SEND_COMPLETE_SONG, true, validKeystrokes, higherBest));

        // 4. 判定幅拡大・ExtraNote（keystrokeIRSendAllowed=false）、FreqTrainer・osu（forceNoIRSend=true）、0打鍵時は送信しない
        assertFalse(IRUtil.shouldSendSinglePlayToIR(false, false, false, IRConfig.IR_SEND_ALWAYS, true, validKeystrokes, higherBest));
        assertFalse(IRUtil.shouldSendSinglePlayToIR(false, true, true, IRConfig.IR_SEND_ALWAYS, true, validKeystrokes, higherBest));
        assertFalse(IRUtil.shouldSendSinglePlayToIR(false, false, true, IRConfig.IR_SEND_ALWAYS, true, zeroKeystrokes, higherBest));

        // 5. IRWorkerProtocol DTO 往復および IRSendStatus での keystrokeOnly 保持
        IRScoreData keystrokeIrScore = new IRScoreData(validKeystrokes, IRScoreData.LNTYPE_CN, true, true);
        assertTrue(keystrokeIrScore.keystrokeOnly);
        assertTrue(keystrokeIrScore.pseudoBss);
        IRScoreData roundTripped = IRWorkerProtocol.Score.from(keystrokeIrScore).toIRScoreData();
        assertTrue(roundTripped.keystrokeOnly);
        assertTrue(roundTripped.pseudoBss);
        assertEquals(IRScoreData.LNTYPE_CN_PLUS, roundTripped.lntype);

        IRScoreData[] captured = new IRScoreData[1];
        IRConnection stubIr = new TestIRConnectionBase() {
            @Override
            public IRResponse<Object> sendPlayData(IRChartData model, IRScoreData score) {
                captured[0] = score;
                return new IRResponse<Object>() {
                    @Override public boolean isSucceeded() { return true; }
                    @Override public String getMessage() { return "ok"; }
                    @Override public Object getData() { return null; }
                };
            }
        };
        MainController.IRSendStatus status = new MainController.IRSendStatus(
                stubIr, createSongWithPath("D:/bms/sample/hran.bms"), validKeystrokes, new ReplayData(),
                IRScoreData.LNTYPE_LN, false, true);
        assertTrue(status.send());
        assertNotNull(captured[0]);
        assertTrue(captured[0].keystrokeOnly);
    }

    private static SongData createSongWithPath(String path) {
        SongData song = new SongData();
        song.setTitle("Test");
        song.setPath(path);
        return song;
    }

    private static BMSModel decodeMinimalModel(String location) {
        byte[] bytes = String.join("\r\n",
                "#PLAYER 1",
                "#TITLE BMC Test",
                "#BPM 120",
                "#WAV01 a.wav",
                "#00111:01",
                "").getBytes(StandardCharsets.UTF_8);
        ChartSource source = new ChartSource() {
            @Override
            public String location() {
                return location;
            }

            @Override
            public long lastModifiedTimeMillis() {
                return 0L;
            }

            @Override
            public InputStream openStream() {
                return new ByteArrayInputStream(bytes);
            }
        };
        return new BMSDecoder().decode(new ChartInformation(source, BMSModel.LNTYPE_LONGNOTE, null));
    }
}
