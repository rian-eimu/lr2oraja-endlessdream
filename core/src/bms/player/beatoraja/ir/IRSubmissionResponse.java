package bms.player.beatoraja.ir;

/**
 * 送信成否が確定できない場合の失敗レスポンス。
 * タイムアウト等により既にサーバー側で受理されている可能性があるため、呼び出し側は自動リトライを行わない。
 */
public final class IRSubmissionResponse implements IRResponse<Object> {
    private final String message;
    private IRSubmissionResponse(String message) { this.message = message; }
    public static IRSubmissionResponse unknown(String message) { return new IRSubmissionResponse(message); }
    public static boolean isOutcomeUnknown(IRResponse<?> response) { return response instanceof IRSubmissionResponse; }
    @Override public boolean isSucceeded() { return false; }
    @Override public String getMessage() { return message; }
    @Override public Object getData() { return null; }
}

