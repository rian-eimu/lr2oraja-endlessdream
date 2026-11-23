package bms.player.beatoraja.ir;

import bms.player.beatoraja.ClearType;
import bms.player.beatoraja.ReplayData;
import bms.player.beatoraja.ScoreData;
import bms.player.beatoraja.input.BMSPlayerInputDevice;
import bms.player.beatoraja.play.BMSPlayerRule;
import bms.player.beatoraja.play.JudgeAlgorithm;

/**
 * IR用スコアデータ
 * 
 * @author exch
 */
public class IRScoreData {

	public static final int LNTYPE_LN = 0;
	public static final int LNTYPE_CN = 1;
	public static final int LNTYPE_HCN = 2;
	public static final int LNTYPE_HLN = 3;
	public static final int LNTYPE_CN_PLUS = 4;
	public static final int LNTYPE_HCN_PLUS = 5;

	/**
	 * 譜面のハッシュ値
	 */
	public final String sha256;
	/**
	 * LN TYPE(0: LN, 1: CN, 2: HCN, 3: HLN, 4: CN+, 5: HCN+)
	 */
	public final int lntype;
	/**
	 * 疑似BSS/MSS変換オプションが適用されたスコアかどうか
	 */
	public final boolean pseudoBss;
	/**
	 * スコア自体は保存せず打鍵数のみをIRへ送信・加算するプレイかどうか
	 */
	public final boolean keystrokeOnly;
	/**
	 * プレイヤーID
	 */
	public final String id;
	/**
	 * プレイヤー名。自身のスコアの場合は空白
	 */
	public final String player;
	/**
	 * クリアタイプ
	 */
	public final ClearType clear;
	/**
	 * スコア最終取得日時(unixtime, 秒単位)
	 */
	public final long date;
	/**
	 * 総PGREATノート数
	 */
	public final int epg;
	public final int lpg;
	/**
	 * 総GREATノート数
	 */
	public final int egr;
	public final int lgr;
	/**
	 * 総GOODノート数
	 */
	public final int egd;
	public final int lgd;
	/**
	 * 総BADノート数
	 */
	public final int ebd;
	public final int lbd;
	/**
	 * 総POORノート数
	 */
	public final int epr;
	public final int lpr;
	/**
	 * 総MISSノート数
	 */
	public final int ems;
	public final int lms;
	
	public final long avgjudge;
	/**
	 * 最大コンボ数
	 */
	public final int maxcombo;
	/**
	 * 総ノート数
	 */
	public final int notes;
	/**
	 * 処理済ノート数
	 */
	public final int passnotes;
	/**
	 * 最小ミスカウント
	 */
	public final int minbp;
	/**
	 * 更新時のオプション
	 */
	public final int option;
	
	public final long seed;
	/**
	 * アシストオプション
	 */
	public final int assist;
	/**
	 * プレイゲージ
	 */
	public final int gauge;
	/**
	 * 入力デバイス
	 */
	public final BMSPlayerInputDevice.Type deviceType;
	/**
	 * 判定アルゴリズム
	 */
	public final JudgeAlgorithm judgeAlgorithm;
	/**
	 * ルール
	 */
	public final BMSPlayerRule rule;
	
	public final String skin;

	/**
	 * 実プレイ経過時間(ミリ秒単位)
	 */
	public final long playDuration;
	/**
	 * 実演奏時間(ミリ秒単位、未計測時は-1)
	 */
	public final long playtime;

	public IRGaugeHistory gaugeHistory;
	
	/**
	 * リプレイデータ（打鍵ログを含む）
	 */
	public transient ReplayData replayData;
	/**
	 * コース用リプレイデータ
	 */
	public transient ReplayData[] replays;
	
	public IRScoreData(String sha256, int lntype, String id, String player, ClearType clear, long date, int epg,
			int lpg, int egr, int lgr, int egd, int lgd, int ebd, int lbd, int epr, int lpr, int ems, int lms,
			long avgjudge, int maxcombo, int notes, int passnotes, int minbp, int option, long seed, int assist,
			int gauge, BMSPlayerInputDevice.Type deviceType, JudgeAlgorithm judgeAlgorithm, BMSPlayerRule rule,
			String skin) {
		this(sha256, lntype, id, player, clear, date, epg, lpg, egr, lgr, egd, lgd, ebd, lbd,
				epr, lpr, ems, lms, avgjudge, maxcombo, notes, passnotes, minbp, option, seed, assist,
				gauge, deviceType, judgeAlgorithm, rule, skin, -1);
	}

	/**
	 * 実演奏時間付きのIRスコアデータを生成する（未計測の場合は -1）。
	 */
	public IRScoreData(String sha256, int lntype, String id, String player, ClearType clear, long date, int epg,
			int lpg, int egr, int lgr, int egd, int lgd, int ebd, int lbd, int epr, int lpr, int ems, int lms,
			long avgjudge, int maxcombo, int notes, int passnotes, int minbp, int option, long seed, int assist,
			int gauge, BMSPlayerInputDevice.Type deviceType, JudgeAlgorithm judgeAlgorithm, BMSPlayerRule rule,
			String skin, long playtime) {
		this(sha256, lntype, id, player, clear, date, epg, lpg, egr, lgr, egd, lgd, ebd, lbd,
				epr, lpr, ems, lms, avgjudge, maxcombo, notes, passnotes, minbp, option, seed, assist,
				gauge, deviceType, judgeAlgorithm, rule, skin, playtime, isPseudoBssLntype(lntype));
	}

	/**
	 * 実演奏時間および疑似BSS/MSS変換適用フラグ付きのIRスコアデータを生成する。
	 */
	public IRScoreData(String sha256, int lntype, String id, String player, ClearType clear, long date, int epg,
			int lpg, int egr, int lgr, int egd, int lgd, int ebd, int lbd, int epr, int lpr, int ems, int lms,
			long avgjudge, int maxcombo, int notes, int passnotes, int minbp, int option, long seed, int assist,
			int gauge, BMSPlayerInputDevice.Type deviceType, JudgeAlgorithm judgeAlgorithm, BMSPlayerRule rule,
			String skin, long playtime, boolean pseudoBss) {
		this(sha256, lntype, id, player, clear, date, epg, lpg, egr, lgr, egd, lgd, ebd, lbd,
				epr, lpr, ems, lms, avgjudge, maxcombo, notes, passnotes, minbp, option, seed, assist,
				gauge, deviceType, judgeAlgorithm, rule, skin, playtime, pseudoBss, false);
	}

	/**
	 * 実演奏時間・疑似BSS/MSS変換適用フラグ・打鍵数のみ送信フラグ付きのIRスコアデータを生成する。
	 */
	public IRScoreData(String sha256, int lntype, String id, String player, ClearType clear, long date, int epg,
			int lpg, int egr, int lgr, int egd, int lgd, int ebd, int lbd, int epr, int lpr, int ems, int lms,
			long avgjudge, int maxcombo, int notes, int passnotes, int minbp, int option, long seed, int assist,
			int gauge, BMSPlayerInputDevice.Type deviceType, JudgeAlgorithm judgeAlgorithm, BMSPlayerRule rule,
			String skin, long playtime, boolean pseudoBss, boolean keystrokeOnly) {
		this.sha256 = sha256;
		this.lntype = toIrLntype(lntype, pseudoBss);
		this.pseudoBss = pseudoBss || isPseudoBssLntype(this.lntype);
		this.keystrokeOnly = keystrokeOnly;
		this.id = id;
		this.player = player;
		this.clear = clear;
		this.date = date;
		this.playtime = Math.max(-1, playtime);
		this.playDuration = Math.max(0, playtime);
		this.epg = epg;
		this.lpg = lpg;
		this.egr = egr;
		this.lgr = lgr;
		this.egd = egd;
		this.lgd = lgd;
		this.ebd = ebd;
		this.lbd = lbd;
		this.epr = epr;
		this.lpr = lpr;
		this.ems = ems;
		this.lms = lms;
		this.avgjudge = avgjudge;
		this.maxcombo = maxcombo;
		this.notes = notes;
		this.passnotes = passnotes;
		this.minbp = minbp;
		this.option = option;
		this.seed = seed;
		this.assist = assist;
		this.gauge = gauge;
		this.deviceType = deviceType;
		this.judgeAlgorithm = judgeAlgorithm;
		this.rule = rule;
		this.skin = skin;
	}

	public IRScoreData(ScoreData score) {
		this(score, score.getMode(), false, false);
	}

	public IRScoreData(ScoreData score, boolean pseudoBss) {
		this(score, score.getMode(), pseudoBss, false);
	}

	public IRScoreData(ScoreData score, int baseLntype, boolean pseudoBss) {
		this(score, baseLntype, pseudoBss, false);
	}

	public IRScoreData(ScoreData score, int baseLntype, boolean pseudoBss, boolean keystrokeOnly) {
		this.sha256 = score.getSha256();
		this.lntype = toIrLntype(baseLntype, pseudoBss);
		this.pseudoBss = pseudoBss || isPseudoBssLntype(this.lntype);
		this.keystrokeOnly = keystrokeOnly;
		this.id = score.getID();
		this.player = score.getPlayer();
		this.clear = ClearType.getClearTypeByID(score.getClear());
		this.date = score.getDate();
		this.playDuration = score.getPlayDuration();
		this.playtime = score.getPlayDuration() > 0 ? score.getPlayDuration() : -1;
		this.epg = score.getEpg();
		this.lpg = score.getLpg();
		this.egr = score.getEgr();
		this.lgr = score.getLgr();
		this.egd = score.getEgd();
		this.lgd = score.getLgd();
		this.ebd = score.getEbd();
		this.lbd = score.getLbd();
		this.epr = score.getEpr();
		this.lpr = score.getLpr();
		this.ems = score.getEms();
		this.lms = score.getLms();
		this.avgjudge = score.getAvgjudge();
		this.maxcombo = score.getCombo();
		this.notes = score.getNotes();
		this.passnotes = score.getPassnotes();
		this.minbp = score.getMinbp();
		this.option = score.getOption();
		this.seed = score.getSeed();
		this.assist = score.getAssist();
		this.gauge = score.getGauge();
		this.deviceType = score.getDeviceType();
		this.judgeAlgorithm = score.getJudgeAlgorithm();
		this.rule = score.getRule();
		this.skin = score.getSkin();
		this.gaugeHistory = IRGaugeHistory.fromGaugeLog(score.getGaugelog());
	}
	
	public static boolean isPseudoBssLntype(int lntype) {
		return lntype == LNTYPE_CN_PLUS || lntype == LNTYPE_HCN_PLUS;
	}

	/**
	 * ローカルLNモードと疑似BSS/MSS変換適用フラグからIR送信用lntypeへ変換する
	 */
	public static int toIrLntype(int lnmode, boolean pseudoBss) {
		if (!pseudoBss) {
			return lnmode;
		}
		return switch (lnmode) {
			case LNTYPE_CN, LNTYPE_CN_PLUS -> LNTYPE_CN_PLUS;
			case LNTYPE_HCN, LNTYPE_HCN_PLUS -> LNTYPE_HCN_PLUS;
			default -> lnmode;
		};
	}

	/**
	 * IRのlntypeをローカルscore.db互換のLNモード（0..3）へ正規化する
	 */
	public static int toLocalLnmode(int irLntype) {
		return switch (irLntype) {
			case LNTYPE_CN_PLUS -> LNTYPE_CN;
			case LNTYPE_HCN_PLUS -> LNTYPE_HCN;
			default -> irLntype;
		};
	}

	/**
	 * IRのlntypeに対応する表記名（LN / CN / HCN / HLN / CN+ / HCN+）を返す
	 */
	public static String getLntypeName(int lntype) {
		return switch (lntype) {
			case LNTYPE_CN -> "CN";
			case LNTYPE_HCN -> "HCN";
			case LNTYPE_HLN -> "HLN";
			case LNTYPE_CN_PLUS -> "CN+";
			case LNTYPE_HCN_PLUS -> "HCN+";
			default -> "LN";
		};
	}

	public String getLntypeName() {
		return getLntypeName(this.lntype);
	}

	public int getExscore() {
		return (epg + lpg) * 2 + egr + lgr;
	}

	public ScoreData convertToScoreData() {
		ScoreData score = new ScoreData();
		score.setSha256(this.sha256);
		score.setMode(toLocalLnmode(this.lntype));
		score.setID(this.id);
		score.setPlayer(this.player);
		score.setClear(this.clear.id);
		score.setDate(this.date);
		score.setEpg(this.epg);
		score.setLpg(this.lpg);
		score.setEgr(this.egr);
		score.setLgr(this.lgr);
		score.setEgd(this.egd);
		score.setLgd(this.lgd);
		score.setEbd(this.ebd);
		score.setLbd(this.lbd);
		score.setEpr(this.epr);
		score.setLpr(this.lpr);
		score.setEms(this.ems);
		score.setLms(this.lms);
		score.setCombo(this.maxcombo);
		score.setNotes(this.notes);
		score.setPassnotes(this.passnotes != 0 ? this.notes : this.passnotes);
		score.setMinbp(this.minbp);
		score.setAvgjudge(this.avgjudge);
		score.setOption(this.option);
		score.setSeed(this.seed);
		score.setAssist(this.assist);
		score.setGauge(this.gauge);
		score.setDeviceType(this.deviceType);
		return score;
	}
}
