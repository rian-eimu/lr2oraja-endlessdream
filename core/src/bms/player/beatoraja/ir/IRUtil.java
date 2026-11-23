package bms.player.beatoraja.ir;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

import bms.player.beatoraja.CourseData;
import bms.player.beatoraja.IRConfig;
import bms.player.beatoraja.ScoreData;
import bms.player.beatoraja.song.SongData;

/**
 * IRに関するユーティリティクラス
 */
public class IRUtil {

    private static final String BMS_IR_DOMAIN = "bms-ir.org";
    private static final String BMS_IR_NAME_PREFIX = "bms-ir";
    private static final String BMC_EXTENSION = ".bmc";

    /**
     * IR名がrianIRであるかどうかを判定する
     * @param irName IR名
     * @return rianIRであればtrue
     */
    public static boolean isRianIR(String irName) {
        return irName != null && irName.startsWith("rianIR");
    }

    /**
     * IR接続をスキップすべきかどうかを判定する
     * @param irName IR名
     * @param isDxMode DX MODEがオンかどうか
     * @return スキップすべきであればtrue
     */
    public static boolean shouldSkipIR(String irName, boolean isDxMode) {
        // rianIRの場合は常にスキップしない
        if (isRianIR(irName)) {
            return false;
        }
        // rianIR以外かつDX MODEオンの場合はスキップ
        return isDxMode;
    }

    /**
     * 疑似BSS/MSS変換が適用されたプレイについて、指定IRへのスコア送信をスキップすべきかどうかを判定する。
     * 変換が発生したCN/HCNプレイ（CN+/HCN+）はrianIR以外に送信しない。
     *
     * @param irName IR名
     * @param isPseudoBssApplied 疑似BSS/MSS変換が適用されたかどうか
     * @return スキップすべきであればtrue
     */
    public static boolean shouldSkipPseudoBssScoreSend(String irName, boolean isPseudoBssApplied) {
        if (!isPseudoBssApplied) {
            return false;
        }
        return !isRianIR(irName);
    }

    /**
     * スコア非保存オプション使用時の打鍵数のみ送信において、指定IRへの送信をスキップすべきかどうかを判定する。
     * 打鍵数のみ送信（keystrokeOnly）は rianIR 専用機能であるため、rianIR 以外に送信しない。
     *
     * @param irName IR名
     * @param keystrokeOnly 打鍵数のみ送信プレイかどうか
     * @return スキップすべきであればtrue
     */
    public static boolean shouldSkipKeystrokeOnlyScoreSend(String irName, boolean keystrokeOnly) {
        if (!keystrokeOnly) {
            return false;
        }
        return !isRianIR(irName);
    }

    /**
     * スコアデータに有効打鍵数（PGREAT + GREAT + GOOD + BAD）が1件以上含まれるかを判定する。
     *
     * @param score スコアデータ
     * @return 有効打鍵が1以上あればtrue
     */
    public static boolean hasValidKeystrokes(ScoreData score) {
        if (score == null) {
            return false;
        }
        long validNotes = (long) score.getEpg() + score.getLpg()
                + score.getEgr() + score.getLgr()
                + score.getEgd() + score.getLgd()
                + score.getEbd() + score.getLbd();
        return validNotes > 0L;
    }

    /**
     * 単曲リザルト画面において、スコア通常送信または打鍵数のみ送信の対象になるかを判定する。
     *
     * @param updateScore 通常スコア保存が有効か（isUpdateScore）
     * @param forceNoIRSend 強制IR送信抑止が有効か（FreqTrainer・osu譜面など）
     * @param keystrokeIRSendAllowed 打鍵数のみ送信が許可されたオプション構成か
     * @param irSendMode IRConfig の送信タイミング設定
     * @param songCompleted 完奏条件（最終ゲージ > 0）を満たしているか
     * @param newScore 今回のスコアデータ
     * @param oldScore 既存の自己ベストスコアデータ
     * @return 送信対象であればtrue
     */
    public static boolean shouldSendSinglePlayToIR(
            boolean updateScore,
            boolean forceNoIRSend,
            boolean keystrokeIRSendAllowed,
            int irSendMode,
            boolean songCompleted,
            ScoreData newScore,
            ScoreData oldScore) {
        if (forceNoIRSend || newScore == null) {
            return false;
        }
        if (updateScore) {
            return switch (irSendMode) {
                case IRConfig.IR_SEND_COMPLETE_SONG -> songCompleted;
                case IRConfig.IR_SEND_UPDATE_SCORE -> oldScore == null
                        || newScore.getExscore() > oldScore.getExscore()
                        || newScore.getClear() > oldScore.getClear()
                        || newScore.getCombo() > oldScore.getCombo()
                        || newScore.getMinbp() < oldScore.getMinbp();
                default -> true;
            };
        }
        if (!keystrokeIRSendAllowed || !hasValidKeystrokes(newScore)) {
            return false;
        }
        // 打鍵数のみ送信時は IR_SEND_UPDATE_SCORE でも毎回送信し、IR_SEND_COMPLETE_SONG のみ完奏条件を適用する
        return irSendMode != IRConfig.IR_SEND_COMPLETE_SONG || songCompleted;
    }

    // =========================================================================
    // 拡張子.bmc譜面のBMS-IRスコア送信制限（将来的に廃止予定の一時措置）
    // 廃止時は以下のメソッド群および MusicResult / CourseResult の呼び出し箇所を削除すること。
    // =========================================================================

    /**
     * 拡張子.bmcの譜面スコアについて、BMS-IRへの送信をスキップすべきかどうかを判定する。
     * ※将来的に廃止予定の一時的な送信制限。
     *
     * @param irName IR名
     * @param song 楽曲データ
     * @return スキップすべきであればtrue
     */
    public static boolean shouldSkipBmcScoreSend(String irName, SongData song) {
        return isBmcSong(song) && isBmsIR(irName);
    }

    /**
     * 拡張子.bmcの譜面を含むコーススコアについて、BMS-IRへの送信をスキップすべきかどうかを判定する。
     * ※将来的に廃止予定の一時的な送信制限。
     *
     * @param irName IR名
     * @param course コースデータ
     * @return スキップすべきであればtrue
     */
    public static boolean shouldSkipBmcScoreSend(String irName, CourseData course) {
        return containsBmcSong(course) && isBmsIR(irName);
    }

    static boolean shouldSkipBmcScoreSend(String irName, String homeUrl, SongData song) {
        return isBmcSong(song) && isBmsIR(irName, homeUrl);
    }

    static boolean shouldSkipBmcScoreSend(String irName, String homeUrl, CourseData course) {
        return containsBmcSong(course) && isBmsIR(irName, homeUrl);
    }

    /**
     * 指定されたIRがBMS-IR（https://bms-ir.org/）であるかどうかを判定する。
     *
     * @param irName IR名
     * @return BMS-IRであればtrue
     */
    public static boolean isBmsIR(String irName) {
        if (irName == null || irName.isBlank() || isRianIR(irName)) {
            return false;
        }
        if (isBmsIrName(irName)) {
            return true;
        }
        return isBmsIrUrl(IRConnectionManager.getHomeURL(irName));
    }

    static boolean isBmsIR(String irName, String homeUrl) {
        if (isRianIR(irName)) {
            return false;
        }
        return isBmsIrName(irName) || isBmsIrUrl(homeUrl);
    }

    static boolean isBmsIrName(String irName) {
        if (irName == null || irName.isBlank()) {
            return false;
        }
        String lower = irName.trim().toLowerCase(Locale.ROOT);
        return lower.equals(BMS_IR_NAME_PREFIX)
                || lower.startsWith(BMS_IR_NAME_PREFIX + " ")
                || lower.startsWith(BMS_IR_NAME_PREFIX + "-")
                || lower.startsWith(BMS_IR_NAME_PREFIX + "_")
                || lower.startsWith(BMS_IR_NAME_PREFIX + "(");
    }

    static boolean isBmsIrUrl(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        String trimmed = url.trim();
        try {
            URI uri = new URI(trimmed.contains("://") ? trimmed : "https://" + trimmed);
            String host = uri.getHost();
            if (host != null) {
                String lowerHost = host.toLowerCase(Locale.ROOT);
                return lowerHost.equals(BMS_IR_DOMAIN) || lowerHost.endsWith("." + BMS_IR_DOMAIN);
            }
        } catch (URISyntaxException ignored) {
        }
        return false;
    }

    static boolean isBmcSong(SongData song) {
        if (song == null) {
            return false;
        }
        String modelPath = song.getBMSModel() != null ? song.getBMSModel().getPath() : null;
        if (modelPath != null && !modelPath.isEmpty()) {
            return isBmcPath(modelPath);
        }
        for (String path : song.getAllPaths()) {
            if (isBmcPath(path)) {
                return true;
            }
        }
        return false;
    }

    static boolean containsBmcSong(CourseData course) {
        if (course == null || course.getSong() == null) {
            return false;
        }
        for (SongData song : course.getSong()) {
            if (isBmcSong(song)) {
                return true;
            }
        }
        return false;
    }

    static boolean isBmcPath(String path) {
        return path != null && path.trim().toLowerCase(Locale.ROOT).endsWith(BMC_EXTENSION);
    }
}
