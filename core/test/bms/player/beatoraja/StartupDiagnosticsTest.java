package bms.player.beatoraja;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * オプトイン起動計測ユーティリティ StartupDiagnostics の単体テスト
 */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class StartupDiagnosticsTest {

    @Test
    @DisplayName("デフォルト状態では無効化されておりログを一切出力しないこと")
    void testDisabledByDefault() {
        String previous = System.getProperty(StartupDiagnostics.PROPERTY);
        List<String> messages = Collections.synchronizedList(new ArrayList<>());
        Logger logger = Logger.getGlobal();
        Handler capture = createCaptureHandler(messages);
        logger.addHandler(capture);
        try {
            System.clearProperty(StartupDiagnostics.PROPERTY);
            StartupDiagnostics.start();
            StartupDiagnostics.gameEntry();
            StartupDiagnostics.selectionFrame(true);
            try (var span = StartupDiagnostics.measure(StartupDiagnostics.Step.IR_LOGIN)) {
                span.outcome(StartupDiagnostics.Outcome.OK);
            }
            Map<String, String> env = new HashMap<>();
            StartupDiagnostics.configureGameEnvironment(env);
            assertTrue(messages.isEmpty());
            assertTrue(env.isEmpty());
        } finally {
            logger.removeHandler(capture);
            restoreProperty(StartupDiagnostics.PROPERTY, previous);
        }
    }

    @Test
    @DisplayName("システムプロパティ有効化時にマイルストーンとSpan計測ログが固定ラベルで記録されること")
    void testEnabledEmitsMilestonesAndSpans() {
        String previous = System.getProperty(StartupDiagnostics.PROPERTY);
        List<String> messages = Collections.synchronizedList(new ArrayList<>());
        Logger logger = Logger.getGlobal();
        Handler capture = createCaptureHandler(messages);
        logger.addHandler(capture);
        try {
            System.setProperty(StartupDiagnostics.PROPERTY, "true");
            StartupDiagnostics.start();
            Map<String, String> env = new HashMap<>();
            StartupDiagnostics.configureGameEnvironment(env);
            var parsed = StartupDiagnostics.readLaunch(
                    env.get("BEATORAJA_STARTUP_TRACE"),
                    env.get("BEATORAJA_STARTUP_EPOCH_MS"));
            assertNotNull(parsed);
            assertNull(StartupDiagnostics.readLaunch("invalid-uuid", "123"));

            StartupDiagnostics.gameEntry();
            try (var span = StartupDiagnostics.measure(StartupDiagnostics.Step.IR_WORKER_START)) {
                span.worker(43210L);
                span.outcome(StartupDiagnostics.Outcome.OK);
            }
            try (var ignored = StartupDiagnostics.measure(StartupDiagnostics.Step.SELECT_SKIN_LOAD, false)) {
                // include == false の場合は記録されないこと
            }

            String joined = String.join("\n", messages);
            assertTrue(joined.contains("step=START"));
            assertTrue(joined.contains("step=GAME_ENTRY"));
            assertTrue(messages.stream().anyMatch(m -> m.contains("step=IR_WORKER_START")
                    && m.contains("outcome=OK")
                    && m.contains("workerPid=43210")
                    && m.contains("trace=" + parsed.id())));
            assertEquals(0L, messages.stream().filter(m -> m.contains("step=SELECT_SKIN_LOAD")).count());
        } finally {
            logger.removeHandler(capture);
            restoreProperty(StartupDiagnostics.PROPERTY, previous);
        }
    }

    private static Handler createCaptureHandler(List<String> messages) {
        return new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getMessage() != null && record.getMessage().startsWith("Startup diagnostics:")) {
                    messages.add(record.getMessage());
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
    }

    private static void restoreProperty(String key, String previous) {
        if (previous == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, previous);
        }
    }
}
