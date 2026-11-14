package bms.player.beatoraja.audio;

import com.portaudio.BlockingStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PortAudioDriver のストリーム終了スレッドアフィニティおよびライフサイクル制御の単体テスト
 */
class PortAudioDriverLifecycleTest {

    @Test
    @DisplayName("通常のdispose手順ではミキサースレッドではなくオーナースレッド上でcloseとreleaseが実行されること")
    void testOwnerDisposeClosesStreamOnOwnerThread() throws Exception {
        CountDownLatch writeEntered = new CountDownLatch(1);
        CountDownLatch allowWriteExit = new CountDownLatch(1);
        FakeBlockingStream fakeStream = new FakeBlockingStream(writeEntered, allowWriteExit, true);

        AtomicInteger releaseCount = new AtomicInteger();
        AtomicReference<Thread> releaseThread = new AtomicReference<>();
        var lifecycle = new PortAudioDriver.StreamLifecycle(fakeStream, () -> {
            releaseThread.set(Thread.currentThread());
            releaseCount.incrementAndGet();
        });

        Thread mixerThread = new Thread(() -> lifecycle.run(() -> {
            float[] buf = new float[64];
            while (lifecycle.write(buf, 32)) {
                // abortされるまで書き込みを継続
            }
        }), "Test-PortAudio-Mixer");
        mixerThread.start();

        assertTrue(writeEntered.await(5, TimeUnit.SECONDS), "ミキサースレッドがwriteに到達すること");

        Thread ownerThread = new Thread(() -> {
            lifecycle.beginOwnerDispose();
            try {
                mixerThread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (!mixerThread.isAlive()) {
                lifecycle.close();
            } else {
                lifecycle.deferCloseToMixer();
            }
        }, "Test-Play-Thread");
        ownerThread.start();
        ownerThread.join(5000);
        mixerThread.join(5000);

        assertEquals(1, fakeStream.closeCount.get(), "nativeStream.close()が1回だけ呼ばれること");
        assertEquals(1, releaseCount.get(), "releaseコールバックが1回だけ呼ばれること");
        assertSame(ownerThread, fakeStream.closeThread.get(),
                "STA COMアフィニティ維持のためオーナースレッド上でcloseされること");
        assertSame(ownerThread, releaseThread.get(),
                "STA COMアフィニティ維持のためオーナースレッド上でreleaseされること");
    }

    @Test
    @DisplayName("ミキサー停止待機タイムアウトでdeferCloseToMixerが呼ばれた場合はミキサー終了時に遅延closeされること")
    void testDeferredCloseWhenMixerStopsLate() throws Exception {
        CountDownLatch writeEntered = new CountDownLatch(1);
        CountDownLatch allowWriteExit = new CountDownLatch(1);
        // abortだけでは即座にアンブロックしないドライバを模擬
        FakeBlockingStream fakeStream = new FakeBlockingStream(writeEntered, allowWriteExit, false);

        AtomicInteger releaseCount = new AtomicInteger();
        AtomicReference<Thread> releaseThread = new AtomicReference<>();
        var lifecycle = new PortAudioDriver.StreamLifecycle(fakeStream, () -> {
            releaseThread.set(Thread.currentThread());
            releaseCount.incrementAndGet();
        });

        Thread mixerThread = new Thread(() -> lifecycle.run(() -> {
            float[] buf = new float[64];
            lifecycle.write(buf, 32);
        }), "Test-Stuck-Mixer");
        mixerThread.start();

        assertTrue(writeEntered.await(5, TimeUnit.SECONDS));

        // オーナー側がdisposeを開始するが、ミキサーがまだwrite中のためタイムアウトして遅延クローズへ委譲する想定
        lifecycle.beginOwnerDispose();
        lifecycle.deferCloseToMixer();

        // まだwrite実行中のため、この時点ではcloseされていないこと
        assertEquals(0, fakeStream.closeCount.get());
        assertNull(releaseThread.get());

        // 遅れてネイティブwriteが復帰すると、ミキサースレッド側で確実にクリーンアップされること
        allowWriteExit.countDown();
        mixerThread.join(5000);

        assertEquals(1, fakeStream.closeCount.get());
        assertEquals(1, releaseCount.get());
        assertSame(mixerThread, fakeStream.closeThread.get());
        assertSame(mixerThread, releaseThread.get());
    }

    @Test
    @DisplayName("beginOwnerDispose後にミキサーが先に終了してからdeferCloseToMixerが呼ばれても即座にcloseされること")
    void testDeferCloseAfterMixerAlreadyExitedClosesImmediately() throws Exception {
        FakeBlockingStream fakeStream = new FakeBlockingStream(new CountDownLatch(0), new CountDownLatch(0), true);
        AtomicInteger releaseCount = new AtomicInteger();
        var lifecycle = new PortAudioDriver.StreamLifecycle(fakeStream, releaseCount::incrementAndGet);

        lifecycle.beginOwnerDispose();
        Thread mixerThread = new Thread(() -> lifecycle.run(() -> {
            // すでにabort済みのため即終了
        }), "Test-Fast-Mixer");
        mixerThread.start();
        mixerThread.join(5000);

        assertEquals(0, fakeStream.closeCount.get(), "ownerDisposing中のためミキサー側ではcloseしないこと");

        lifecycle.deferCloseToMixer();
        assertEquals(1, fakeStream.closeCount.get(), "ミキサー停止済みなら呼び出し元で即座にcloseされること");
        assertEquals(1, releaseCount.get());
    }

    @Test
    @DisplayName("ownerDisposingでない状態でミキサーが終了した場合はミキサースレッド自身がcloseすること")
    void testUnexpectedMixerExitClosesStreamAutomatically() throws Exception {
        FakeBlockingStream fakeStream = new FakeBlockingStream(new CountDownLatch(0), new CountDownLatch(0), true);
        AtomicInteger releaseCount = new AtomicInteger();
        var lifecycle = new PortAudioDriver.StreamLifecycle(fakeStream, releaseCount::incrementAndGet);

        Thread mixerThread = new Thread(() -> lifecycle.run(() -> {
            // 異常終了・早期終了の模擬
        }), "Test-Autonomous-Mixer");
        mixerThread.start();
        mixerThread.join(5000);

        assertEquals(1, fakeStream.closeCount.get());
        assertEquals(1, releaseCount.get());
        assertSame(mixerThread, fakeStream.closeThread.get());
    }

    @Test
    @DisplayName("未初期化状態でのterminateIfIdle呼び出しはネイティブ呼び出しを行わず安全に完了すること")
    void testTerminateIfIdleWhenUninitializedIsNoOp() {
        assertDoesNotThrow(PortAudioDriver::terminateIfIdle);
    }

    @Test
    @DisplayName("ASIOバッファサイズ調整ロジックがドライバ制約に合わせて適切に丸めること")
    void testAdjustAsioBufferSize() {
        var granularitySizes = new PortAudioAsioDiagnostics.BufferSizes(64, 1024, 256, 32);
        assertEquals(256, PortAudioDriver.adjustAsioBufferSize(0, 0, granularitySizes));
        assertEquals(64, PortAudioDriver.adjustAsioBufferSize(0, 16, granularitySizes));
        assertEquals(128, PortAudioDriver.adjustAsioBufferSize(0, 120, granularitySizes));

        // pow2 / upper のいずれかが preferred と一致する場合は preferred を優先する
        var pow2PreferredUpper = new PortAudioAsioDiagnostics.BufferSizes(64, 2048, 512, -1);
        assertEquals(512, PortAudioDriver.adjustAsioBufferSize(0, 300, pow2PreferredUpper));

        // preferred が候補外の場合は最も近い2のべき乗へ丸める
        var pow2Nearest = new PortAudioAsioDiagnostics.BufferSizes(64, 2048, 1024, -1);
        assertEquals(256, PortAudioDriver.adjustAsioBufferSize(0, 300, pow2Nearest));
        assertEquals(512, PortAudioDriver.adjustAsioBufferSize(0, 400, pow2Nearest));
    }

    /**
     * ネイティブDLLをロードせずにStreamLifecycleを検証するためのテスト用BlockingStream実装
     */
    private static final class FakeBlockingStream extends BlockingStream {
        private final CountDownLatch writeEntered;
        private final CountDownLatch allowWriteExit;
        private final boolean unblockOnAbort;
        private volatile boolean stopped;
        final AtomicInteger abortCount = new AtomicInteger();
        final AtomicInteger closeCount = new AtomicInteger();
        final AtomicReference<Thread> closeThread = new AtomicReference<>();

        FakeBlockingStream(CountDownLatch writeEntered, CountDownLatch allowWriteExit, boolean unblockOnAbort) {
            super();
            this.writeEntered = writeEntered;
            this.allowWriteExit = allowWriteExit;
            this.unblockOnAbort = unblockOnAbort;
        }

        @Override
        public void start() {
            stopped = false;
        }

        @Override
        public boolean isStopped() {
            return stopped;
        }

        @Override
        public void abort() {
            abortCount.incrementAndGet();
            stopped = true;
            if (unblockOnAbort) {
                allowWriteExit.countDown();
            }
        }

        @Override
        public boolean write(float[] buffer, int numFrames) {
            writeEntered.countDown();
            try {
                allowWriteExit.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (stopped && unblockOnAbort) {
                throw new RuntimeException("Stream aborted");
            }
            return true;
        }

        @Override
        public void close() {
            closeThread.set(Thread.currentThread());
            closeCount.incrementAndGet();
            stopped = true;
        }
    }
}
