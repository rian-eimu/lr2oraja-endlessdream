package bms.player.beatoraja.ir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** レガシーメソッドのみを持つプラグインフィクスチャ。継承されたオプションIR APIがバイナリ互換のデフォルト動作を検証する。 */
public final class RecoveryIRConnection extends TestIRConnectionBase {
    public static final String NAME = "Recovery fixture";
    private boolean loggedIn;

    public static void main(String[] args) throws Exception {
        System.setProperty("fixture.events", args[0]); System.setProperty("fixture.mode", args[1]);
        if (args[1].equals("no-read")) { Thread.sleep(60_000); return; }
        if (args[1].equals("malformed")) {
            new java.io.BufferedReader(new java.io.InputStreamReader(System.in)).readLine();
            System.out.println("{broken-json"); System.out.flush(); Thread.sleep(60_000); return;
        }
        IRWorkerMain.main(new String[]{NAME});
    }
    private static void event(String text) {
        try { Files.writeString(Path.of(System.getProperty("fixture.events")), text + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND); }
        catch (java.io.IOException e) { throw new IllegalStateException(e); }
    }
    private static String mode() { return System.getProperty("fixture.mode"); }
    @Override public IRResponse<IRPlayerData> login(IRAccount account) {
        if (getClass().getProtectionDomain().getCodeSource().getLocation().getPath().endsWith(".jar")) event("plugin-jar");
        event("login:" + account.id);
        try {
            boolean rejected = mode().equals("fail-reauth") && Files.readAllLines(Path.of(System.getProperty("fixture.events")))
                    .stream().filter(s -> s.startsWith("login:")).count() > 1;
            loggedIn = !rejected;
            return response(loggedIn, rejected ? "fixture authentication rejected" : "login", new IRPlayerData(account.id, "fixture", ""));
        } catch (java.io.IOException e) { throw new IllegalStateException(e); }
    }
    @Override public IRResponse<IRPlayerData[]> getRivals() {
        event("rivals");
        if (mode().equals("crash") || mode().equals("fail-reauth")) System.exit(7);
        if (mode().equals("timeout")) pause();
        return response(loggedIn, "rivals", new IRPlayerData[0]);
    }
    @Override public IRResponse<IRTableData[]> getTableDatas() {
        event("tables"); return response(loggedIn, "tables", new IRTableData[0]);
    }
    @Override public IRResponse<IRScoreData[]> getPlayData(IRPlayerData player, IRChartData chart) {
        return response(loggedIn, "scores", new IRScoreData[0]);
    }
    @Override public IRResponse<Object> sendPlayData(IRChartData chart, IRScoreData score) { return submission("send"); }
    @Override public IRResponse<Object> sendCoursePlayData(IRCourseData course, IRScoreData score) { return submission("course-send"); }
    private IRResponse<Object> submission(String method) {
        event(method);
        if (mode().equals("accept-hang")) pause();
        if (mode().equals("rate-limited")) {
            return new RetryableResponse("Too Many Requests", true, 300_000L);
        }
        if (mode().equals("service-unavailable-huge-retry")) {
            return new RetryableResponse("Service Unavailable", true, 99_999_999_999L);
        }
        return response(loggedIn && !mode().equals("negative-response"), "negative-response".equals(mode()) ? "Read timed out" : "sent", null);
    }
    private static void pause() { try { Thread.sleep(60_000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
    /** 旧本体JAR向けにビルドされた外部プラグインの公開メソッド規約を模倣するレスポンス。 */
    public static final class RetryableResponse implements IRResponse<Object> {
        private final String message;
        private final boolean retryable;
        private final long retryAfterMillis;
        public RetryableResponse(String message, boolean retryable, long retryAfterMillis) {
            this.message = message;
            this.retryable = retryable;
            this.retryAfterMillis = retryAfterMillis;
        }
        @Override public boolean isSucceeded() { return false; }
        @Override public String getMessage() { return message; }
        @Override public Object getData() { return null; }
        public boolean isRetryable() { return retryable; }
        public long getRetryAfterMillis() { return retryAfterMillis; }
    }
    static <T> IRResponse<T> response(boolean success, String message, T data) {
        return new IRResponse<T>() {
            public boolean isSucceeded() { return success; }
            public String getMessage() { return message; }
            public T getData() { return data; }
        };
    }
}

