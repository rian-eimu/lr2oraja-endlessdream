package bms.player.beatoraja.video;

import bms.player.beatoraja.song.SongResources;
import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.LinkedBlockingDeque;

import static org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_MPEG4;
import static org.bytedeco.ffmpeg.global.avutil.*;

/** FFmpegProcessor・BgrFrameConverterのデコードおよびライフサイクル回帰テスト。 */
public final class FFmpegProcessorTest {

    @Test
    public void testBgrFrameConverter() throws Exception {
        try (var converter = new BgrFrameConverter()) {
            for (int width : new int[] {1, 2, 7, 9, 16, 17, 31, 33, 64, 66, 67, 70, 80, 641})
                for (int format : new int[] {AV_PIX_FMT_RGB24, AV_PIX_FMT_BGR24, AV_PIX_FMT_BGRA, AV_PIX_FMT_GRAY8})
                    checkPacked(converter, width, 5, format, false);
            checkPacked(converter, 66, 7, AV_PIX_FMT_RGB24, true);
            for (int width = 2; width <= 80; width += 2) checkYuv(converter, width, AV_PIX_FMT_YUV420P);
            checkYuv(converter, 66, AV_PIX_FMT_YUV444P);
            checkYuv(converter, 66, AV_PIX_FMT_YUVJ420P);
            checkYuv(converter, 66, AV_PIX_FMT_NV12);
        }
    }

    @Test
    public void testGrabDecodedFrameAndRestart() throws Exception {
        Path dir = Files.createTempDirectory("ffmpeg-processor-test-");
        try {
            Path movie = dir.resolve("test.avi");
            try (var recorder = new FFmpegFrameRecorder(movie.toFile(), 64, 48)) {
                recorder.setFormat("avi");
                recorder.setVideoCodec(AV_CODEC_ID_MPEG4);
                recorder.setPixelFormat(AV_PIX_FMT_YUV420P);
                recorder.setFrameRate(20);
                recorder.setVideoBitrate(400_000);
                recorder.start();
                int plane = 64 * 48;
                var data = ByteBuffer.allocateDirect(plane * 3 / 2 + 64);
                for (int i = 0; i < plane; i++) data.put(i, (byte) 81);
                for (int i = plane; i < plane * 5 / 4; i++) data.put(i, (byte) 90);
                for (int i = plane * 5 / 4; i < plane * 3 / 2; i++) data.put(i, (byte) 240);
                for (int i = 0; i < 6; i++) {
                    data.position(0);
                    recorder.recordImage(64, 48, Frame.DEPTH_UBYTE, 1, 64, AV_PIX_FMT_YUV420P, data);
                }
                recorder.stop();
            }

            byte[] movieBytes = Files.readAllBytes(movie);
            try (var grabber = new FFmpegFrameGrabber(new ByteArrayInputStream(movieBytes));
                 var converter = new BgrFrameConverter()) {
                grabber.setImageMode(FrameGrabber.ImageMode.RAW);
                grabber.start();

                // MovieSeekThread.restart() と同様に setVideoFrameNumber(0) 後の grabDecodedFrame も検証する
                Method setVideoFrameNumber = FFmpegFrameGrabber.class.getMethod("setVideoFrameNumber", int.class);
                setVideoFrameNumber.invoke(grabber, 0);

                int count = 0;
                Frame frame;
                while ((frame = FFmpegProcessor.grabDecodedFrame(grabber)) != null) {
                    Frame converted = converter.convert(frame);
                    check(converted.imageWidth == 64 && converted.imageHeight == 48 && converted.imageChannels == 3,
                            "Converted dimensions must match");
                    count++;
                }
                check(count == 6, "Expected 6 decoded frames after restart, got " + count);
                grabber.stop();
            }
        } finally {
            try (var paths = Files.walk(dir)) {
                for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(p);
                }
            }
        }
    }

    @Test
    public void testLifecycle() throws Exception {
        var processor = new FFmpegProcessor(1);
        var worker = processor.new MovieSeekThread(SongResources.fromPath(Path.of("unused.mp4")));
        Field queueField = worker.getClass().getDeclaredField("commands");
        queueField.setAccessible(true);
        var queue = (LinkedBlockingDeque<?>) queueField.get(worker);
        for (int i = 0; i < 4; i++) worker.exec(FFmpegProcessor.Command.STOP);
        check(queue.remainingCapacity() == 0, "Test queue must be full");
        worker.exec(FFmpegProcessor.Command.HALT);
        Field haltField = worker.getClass().getDeclaredField("haltRequested");
        haltField.setAccessible(true);
        check(haltField.getBoolean(worker) && queue.size() == 1 && queue.peekFirst() == FFmpegProcessor.Command.HALT,
                "HALT must replace the full command queue with a wake signal");
        worker.exec(FFmpegProcessor.Command.PLAY);
        check(queue.size() == 1 && queue.peekFirst() == FFmpegProcessor.Command.HALT,
                "Stopped workers must reject new playback commands");
        worker.start();
        worker.join(5000);
        check(!worker.isAlive() && queue.isEmpty(), "A halted worker must exit without opening the movie");
        Field converter = worker.getClass().getDeclaredField("converter");
        converter.setAccessible(true);
        check(converter.get(worker) == null, "Converter is released even when halted before open");
        processor.dispose();
        processor.dispose();
    }

    private static AVFrame allocate(int width, int height, int format) {
        AVFrame source = av_frame_alloc();
        source.width(width).height(height).format(format);
        if (av_frame_get_buffer(source, 32) < 0) throw new AssertionError("Input allocation");
        return source;
    }

    private static void checkPacked(BgrFrameConverter converter, int width, int height, int format, boolean flipped) {
        AVFrame source = allocate(width, height, format), view = null;
        try {
            int channels = format == AV_PIX_FMT_BGRA ? 4 : format == AV_PIX_FMT_GRAY8 ? 1 : 3;
            ByteBuffer input = source.data(0).capacity((long) source.linesize(0) * height).asByteBuffer();
            for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                int p = y * source.linesize(0) + x * channels, red = x % 180, green = y * 20, blue = 96;
                if (format == AV_PIX_FMT_GRAY8) input.put(p, (byte) red);
                else {
                    input.put(p, (byte) (format == AV_PIX_FMT_RGB24 ? red : blue));
                    input.put(p + 1, (byte) green);
                    input.put(p + 2, (byte) (format == AV_PIX_FMT_RGB24 ? blue : red));
                    if (channels == 4) input.put(p + 3, (byte) 127);
                }
            }
            AVFrame borrowed = source;
            if (flipped) {
                view = av_frame_alloc();
                view.width(width).height(height).format(format);
                view.data(0, source.data(0).position((long) (height - 1) * source.linesize(0))).linesize(0, -source.linesize(0));
                borrowed = view;
            }
            var frame = new Frame();
            frame.opaque = borrowed;
            frame.timestamp = 123456;
            frame.keyFrame = true;
            Frame result = converter.convert(frame);
            var output = (ByteBuffer) result.image[0];
            check(result.imageWidth == width && result.imageHeight == height && result.imageChannels == 3, "Dimensions/channels unchanged");
            check(result.timestamp == 123456 && result.keyFrame, "Metadata unchanged");
            for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                int p = y * result.imageStride + x * 3, red = x % 180, green = (flipped ? height - 1 - y : y) * 20;
                int blue = format == AV_PIX_FMT_GRAY8 ? red : 96;
                if (format == AV_PIX_FMT_GRAY8) green = red;
                check((output.get(p) & 255) == blue && (output.get(p + 1) & 255) == green && (output.get(p + 2) & 255) == red,
                        "Packed conversion: width=" + width + ", format=" + format + ", x=" + x + ", y=" + y);
            }
            check(converter.convert(frame) == result && result.image[0] == output, "Reuse frame/buffer for stable layout");
        } finally {
            if (view != null) av_frame_free(view);
            av_frame_free(source);
        }
    }

    private static void checkYuv(BgrFrameConverter converter, int width, int format) {
        AVFrame source = allocate(width, 48, format);
        try {
            for (int plane = 0; plane < (format == AV_PIX_FMT_NV12 ? 2 : 3); plane++) {
                int height = plane == 0 || format == AV_PIX_FMT_YUV444P ? 48 : 24, value = plane == 0 ? 81 : plane == 1 ? 90 : 240;
                var bytes = source.data(plane).capacity((long) source.linesize(plane) * height).asByteBuffer();
                for (int p = 0; p < bytes.capacity(); p++)
                    bytes.put(p, (byte) (format == AV_PIX_FMT_NV12 && plane == 1 ? p % 2 == 0 ? 90 : 240 : value));
            }
            var frame = new Frame();
            frame.opaque = source;
            var result = converter.convert(frame);
            var bytes = (ByteBuffer) result.image[0];
            for (int y = 0; y < 48; y++) for (int x = 0; x < width; x++) {
                int p = y * result.imageStride + x * 3;
                check((bytes.get(p) & 255) < 20 && (bytes.get(p + 1) & 255) < 20 && (bytes.get(p + 2) & 255) > 220,
                        "YUV conversion: width=" + width + ", stride=" + result.imageStride + ", x=" + x);
            }
        } finally {
            av_frame_free(source);
        }
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}

