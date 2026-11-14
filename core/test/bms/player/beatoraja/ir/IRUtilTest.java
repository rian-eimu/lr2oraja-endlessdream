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
import bms.player.beatoraja.CourseData;
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
