package bms.player.beatoraja.ir;

import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

/**
 * 送信失敗時の再送制御情報を保持するレスポンス。
 * <ul>
 *   <li>{@link #unknown(String)}: タイムアウト等により既にサーバー側で受理されている可能性があるため、呼び出し側は自動リトライを行わない。</li>
 *   <li>{@link #retryable(String, long)}: レート制限（429）やメンテナンス（503）等、サーバー側で未処理であることが確定している失敗を表し、自動リトライを許可する。</li>
 * </ul>
 */
public final class IRSubmissionResponse implements IRResponse<Object> {
    /** ワーカーから返される待機時間の上限（異常値による無期限滞留・オーバーフロー防止） */
    public static final long MAX_RETRY_AFTER_MILLIS = TimeUnit.HOURS.toMillis(1);

    private final String message;
    private final boolean outcomeUnknown;
    private final boolean retryable;
    private final long retryAfterMillis;

    private IRSubmissionResponse(String message, boolean outcomeUnknown, boolean retryable, long retryAfterMillis) {
        this.message = message != null ? message : "";
        this.outcomeUnknown = outcomeUnknown;
        this.retryable = retryable;
        this.retryAfterMillis = clampRetryAfterMillis(retryAfterMillis);
    }

    public static IRSubmissionResponse unknown(String message) {
        return new IRSubmissionResponse(message, true, false, 0L);
    }

    public static IRSubmissionResponse retryable(String message) {
        return retryable(message, 0L);
    }

    public static IRSubmissionResponse retryable(String message, long retryAfterMillis) {
        return new IRSubmissionResponse(message, false, true, retryAfterMillis);
    }

    public static long clampRetryAfterMillis(long retryAfterMillis) {
        if (retryAfterMillis <= 0L) return 0L;
        return Math.min(retryAfterMillis, MAX_RETRY_AFTER_MILLIS);
    }

    public static boolean isOutcomeUnknown(IRResponse<?> response) {
        return response instanceof IRSubmissionResponse sub && sub.outcomeUnknown;
    }

    /**
     * サーバー側で未処理であることが確定しており安全に再送可能かを判定する。
     * 旧バージョンの本体JARに対してビルドされた外部IRプラグインJARとのバイナリ互換のため、
     * 公開メソッド {@code boolean isRetryable()} によるリフレクション検出にも対応する。
     */
    public static boolean isRetryable(IRResponse<?> response) {
        if (response == null || response.isSucceeded()) return false;
        if (response instanceof IRSubmissionResponse sub) return sub.retryable;
        try {
            Method m = response.getClass().getMethod("isRetryable");
            if (!m.canAccess(response)) m.trySetAccessible();
            Object val = m.invoke(response);
            return val instanceof Boolean b && b;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    /**
     * 再送までに待機すべき最小時間（ミリ秒）を取得する。指定がない場合は {@code 0L} を返す。
     * 外部IRプラグインJARが公開メソッド {@code long getRetryAfterMillis()} を持つ場合も読み取る。
     */
    public static long getRetryAfterMillis(IRResponse<?> response) {
        if (response == null || response.isSucceeded()) return 0L;
        if (response instanceof IRSubmissionResponse sub) return sub.retryAfterMillis;
        try {
            Method m = response.getClass().getMethod("getRetryAfterMillis");
            if (!m.canAccess(response)) m.trySetAccessible();
            Object val = m.invoke(response);
            if (val instanceof Number n) {
                return clampRetryAfterMillis(n.longValue());
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            // 未対応コネクタの場合は 0L（既存の指数バックオフのみ）
        }
        return 0L;
    }

    @Override public boolean isSucceeded() { return false; }
    @Override public String getMessage() { return message; }
    @Override public Object getData() { return null; }
}
