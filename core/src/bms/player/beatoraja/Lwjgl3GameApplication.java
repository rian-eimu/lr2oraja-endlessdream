package bms.player.beatoraja;

import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import com.badlogic.gdx.ApplicationListener;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import org.lwjgl.glfw.GLFW;

class Lwjgl3GameApplication extends Lwjgl3Application {
    Lwjgl3GameApplication(ApplicationListener listener, Lwjgl3ApplicationConfiguration config) {
        super(listener, config);
    }

    @Override
    protected void loop() {
        try {
            super.loop();
        } catch (Throwable failure) {
            logBeforeCleanup("LWJGL3 game loop failed before cleanup", failure);
            throw failure;
        }
    }

    @Override
    protected void cleanupWindows() {
        try {
            super.cleanupWindows();
        } catch (Throwable failure) {
            logBeforeCleanup("LWJGL3 window cleanup failed before GLFW termination", failure);
            throw failure;
        }
    }

    @Override
    protected void cleanup() {
        // libGDX側がglfwTerminate()の前にコールバックを解放するため、ここでは事前登録解除のみ行う
        GLFW.glfwSetErrorCallback(null);
        super.cleanup();
    }

    private static void logBeforeCleanup(String message, Throwable failure) {
        try {
            Logger.getGlobal().log(new BeforeCleanupLogRecord(message, failure));
            for (Handler handler : Logger.getLogger("").getHandlers()) {
                handler.flush();
            }
        } catch (RuntimeException loggingFailure) {
            // ログハンドラ側が失敗した場合でも元の例外は必ず出力する
            System.err.println(message);
            failure.printStackTrace(System.err);
        }
    }

    // ネイティブ終了処理でクラッシュしてもコンソール・XMLログに残るよう即時フラッシュ対象とするレコード
    static final class BeforeCleanupLogRecord extends LogRecord {
        private BeforeCleanupLogRecord(String message, Throwable failure) {
            super(Level.SEVERE, message);
            setLoggerName(Logger.GLOBAL_LOGGER_NAME);
            setThrown(failure);
        }
    }
}
