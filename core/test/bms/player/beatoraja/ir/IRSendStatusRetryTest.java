package bms.player.beatoraja.ir;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import bms.player.beatoraja.CourseData;
import bms.player.beatoraja.MainController;
import bms.player.beatoraja.ReplayData;
import bms.player.beatoraja.ScoreData;
import bms.player.beatoraja.song.SongData;

/**
 * {@link MainController.IRSendStatus} および {@link IRSubmissionResponse} の再送制御・待機時間計算の単体テスト。
 */
public class IRSendStatusRetryTest {

    @Test
    public void testRateLimitedSingleScoreRemainsRetryableAndHonorsRetryAfter() {
        AtomicInteger calls = new AtomicInteger();
        Queue<IRResponse<Object>> responses = new ArrayDeque<>();
        responses.add(IRSubmissionResponse.retryable("Too many score submissions.", 300_000L));
        responses.add(succeededResponse("Score Sent"));

        IRConnection stubIr = new TestIRConnectionBase() {
            @Override
            public IRResponse<Object> sendPlayData(IRChartData model, IRScoreData score) {
                calls.incrementAndGet();
                return responses.remove();
            }
        };

        SongData song = createSong("Test Title", "[ANOTHER]");
        ScoreData score = createScore();
        MainController.IRSendStatus status = new MainController.IRSendStatus(stubIr, song, score, new ReplayData());

        assertEquals("Test Title [ANOTHER]", status.getDisplayName());
        assertEquals(0L, status.getRetryDelayMillis());

        // 1回目: 429 により失敗するが retry=-1 にならず、Retry-After (300s) > 4^1s (4s) が採用される
        assertFalse(status.send());
        assertFalse(status.isSent);
        assertEquals(1, status.retry);
        assertEquals(300_000L, status.retryAfterMillis);
        assertEquals(300_000L, status.getRetryDelayMillis());
        assertTrue(status.lastTry > 0L);

        // 2回目: 再送で成功し、以降の呼び出しでは二重送信されない
        assertTrue(status.send());
        assertTrue(status.isSent);
        assertEquals(2, status.retry);
        assertEquals(0L, status.retryAfterMillis);
        assertEquals(2, calls.get());

        assertTrue(status.send());
        assertEquals(2, calls.get());
    }

    @Test
    public void testRetryDelayUsesLargerOfExponentialBackoffAndRetryAfter() {
        Queue<IRResponse<Object>> responses = new ArrayDeque<>();
        responses.add(IRSubmissionResponse.retryable("Service Unavailable", 30_000L)); // retry=1: max(4s, 30s) = 30s
        responses.add(IRSubmissionResponse.retryable("Service Unavailable", 30_000L)); // retry=2: max(16s, 30s) = 30s
        responses.add(IRSubmissionResponse.retryable("Service Unavailable", 30_000L)); // retry=3: max(64s, 30s) = 64s
        responses.add(IRSubmissionResponse.retryable("Temporary error", 0L));          // retry=4: Retry-Afterリセット -> 256s

        IRConnection stubIr = new TestIRConnectionBase() {
            @Override
            public IRResponse<Object> sendPlayData(IRChartData model, IRScoreData score) {
                return responses.remove();
            }
        };

        MainController.IRSendStatus status = new MainController.IRSendStatus(
                stubIr, createSong("Backoff", ""), createScore(), new ReplayData());

        assertFalse(status.send());
        assertEquals(1, status.retry);
        assertEquals(30_000L, status.getRetryDelayMillis());

        assertFalse(status.send());
        assertEquals(2, status.retry);
        assertEquals(30_000L, status.getRetryDelayMillis());

        assertFalse(status.send());
        assertEquals(3, status.retry);
        assertEquals(64_000L, status.getRetryDelayMillis());

        assertFalse(status.send());
        assertEquals(4, status.retry);
        assertEquals(0L, status.retryAfterMillis);
        assertEquals(256_000L, status.getRetryDelayMillis());
    }

    @Test
    public void testCourseScoreSubmissionRetriesOn429AndPreservesPseudoBss() {
        AtomicInteger courseCalls = new AtomicInteger();
        IRCourseData[] capturedCourse = new IRCourseData[1];
        IRScoreData[] capturedScore = new IRScoreData[1];

        Queue<IRResponse<Object>> responses = new ArrayDeque<>();
        responses.add(IRSubmissionResponse.retryable("Too many course score submissions.", 300_000L));
        responses.add(succeededResponse("Course Score Sent"));

        IRConnection stubIr = new TestIRConnectionBase() {
            @Override
            public IRResponse<Object> sendCoursePlayData(IRCourseData course, IRScoreData score) {
                courseCalls.incrementAndGet();
                capturedCourse[0] = course;
                capturedScore[0] = score;
                return responses.remove();
            }
        };

        CourseData courseData = new CourseData();
        courseData.setName("段位認定 皆伝");
        courseData.setSong(new SongData[] { createSong("Track 1", "") });

        MainController.IRSendStatus status = new MainController.IRSendStatus(
                stubIr, courseData, IRScoreData.LNTYPE_CN, true, createScore(), new ReplayData[] { new ReplayData() });

        assertEquals("段位認定 皆伝", status.getDisplayName());

        // 1回目: コース送信が 429 で拒否されても自動再送対象として残る
        assertFalse(status.send());
        assertFalse(status.isSent);
        assertEquals(1, status.retry);
        assertEquals(300_000L, status.getRetryDelayMillis());
        assertNotNull(capturedCourse[0]);
        assertEquals(IRScoreData.LNTYPE_CN_PLUS, capturedCourse[0].lntype);
        assertTrue(capturedCourse[0].pseudoBss);
        assertEquals(IRScoreData.LNTYPE_CN_PLUS, capturedScore[0].lntype);
        assertTrue(capturedScore[0].pseudoBss);

        // 2回目: コース再送に成功する
        assertTrue(status.send());
        assertTrue(status.isSent);
        assertEquals(2, courseCalls.get());
    }

    @Test
    public void testUnknownOutcomeImmediatelyStopsAutoRetry() {
        AtomicInteger calls = new AtomicInteger();
        IRConnection stubIr = new TestIRConnectionBase() {
            @Override
            public IRResponse<Object> sendPlayData(IRChartData model, IRScoreData score) {
                calls.incrementAndGet();
                return IRSubmissionResponse.unknown("IR worker timeout");
            }
        };

        MainController.IRSendStatus status = new MainController.IRSendStatus(
                stubIr, createSong("Ambiguous", ""), createScore(), new ReplayData());

        assertFalse(status.send());
        assertFalse(status.isSent);
        assertEquals(-1, status.retry);
        assertEquals(0L, status.getRetryDelayMillis());

        // retry < 0 となった後は再呼び出ししても送信しない
        assertFalse(status.send());
        assertEquals(1, calls.get());
    }

    private static SongData createSong(String title, String subtitle) {
        SongData song = new SongData();
        song.setTitle(title);
        song.setSubtitle(subtitle);
        song.setSha256("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
        song.setPath("D:/bms/sample/test.bms");
        return song;
    }

    private static ScoreData createScore() {
        ScoreData score = new ScoreData();
        score.setSha256("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
        score.setEpg(100);
        score.setNotes(100);
        return score;
    }

    private static IRResponse<Object> succeededResponse(String message) {
        return new IRResponse<Object>() {
            @Override public boolean isSucceeded() { return true; }
            @Override public String getMessage() { return message; }
            @Override public Object getData() { return null; }
        };
    }
}
