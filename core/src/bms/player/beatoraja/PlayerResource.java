package bms.player.beatoraja;

import bms.model.*;
import bms.player.beatoraja.CourseData.CourseDataConstraint;
import bms.player.beatoraja.TableData.TableFolder;
import bms.player.beatoraja.audio.AudioDriver;
import bms.player.beatoraja.audio.BMSLoudnessAnalyzer;
import bms.player.beatoraja.ir.IRScoreData;
import bms.player.beatoraja.ir.RankingData;
import bms.player.beatoraja.play.BMSPlayerRule;
import bms.player.beatoraja.play.GrooveGauge;
import bms.player.beatoraja.play.PseudoBssConverter;
import bms.player.beatoraja.play.bga.BGAProcessor;
import bms.player.beatoraja.song.SongData;
import bms.player.beatoraja.song.SongResource;
import bms.player.beatoraja.song.SongResources;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.FloatArray;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.concurrent.Future;

/**
 * プレイヤーのコンポーネント間でデータをやり取りするためのクラス
 *
 * @author exch
 */
public final class PlayerResource {
	private static final Logger logger = LoggerFactory.getLogger(PlayerResource.class);
	
	/**
	 * 選曲中のBMS
	 */
	private BMSModel model;
	/** {@link #model} の元リソース（ランダム再デコード時にも使用）。 */
	private SongResource chartResource;
	
	private long marginTime;
	/**
	 * 選択中のBMSの情報
	 */
	private SongData songdata;
	/**
	 * BMSModelの元々のモード
	 */
	private bms.model.Mode orgmode;
	/**
	 * コース 1 曲目の読み込み時のモード (KEY モード毎の設定の参照先)
	 */
	private bms.model.Mode courseOrgmode;

	private PlayerData playerdata = new PlayerData();

	private Config config;
	private PlayerConfig pconfig;
	/**
	 * プレイモード
	 */
	private BMSPlayerMode mode;
	
	private BMSResource bmsresource;

	/**
	 * スコア
	 */
	private ScoreData score;
	/**
	 * ライバルスコア
	 */
	private ScoreData rscore;
	/**
	 * ターゲットスコア
	 */
	private ScoreData tscore;
	
	private RankingData ranking;
	/**
	 * スコア更新するかどうか
	 */
	private boolean updateScore = true;
	private boolean updateCourseScore = true;
	private GrooveGauge grooveGauge;
	/**
	 * ゲージの遷移ログ
	 */
	private FloatArray[] gauge;

	private ReplayData replay;

	private ReplayData playinfo;
	
	private ReplayData chartOption;

	private Path[] bmsPaths;
	private boolean loop;
	
	/**
	 * コース
	 */
	private CourseData coursedata;
	/**
	 * コースのBMSモデル
	 */
	private BMSModel[] course;
	/**
	 * コース何曲目
	 */
	private int courseindex;
	/**
	 * コースゲージ履歴
	 */
	private Array<FloatArray[]> coursegauge = new Array<FloatArray[]>();

	private Array<ReplayData> courseReplay = new Array<ReplayData>();
	/**
	 * コーススコア
	 */
	private ScoreData cscore;
	/**
	 * コンボ数。コースプレイ時の引継ぎに使用
	 */
	private int combo;
	/**
	 * 最大コンボ数。コースプレイ時の引継ぎに使用
	 */
	private int maxcombo;
	/**
	 * 元々のゲージオプション
	 */
	private int orgGaugeOption = 0;

	private int assist = 0;
	/**
	 * 現在プレイしている楽曲を含む難易度表とレベル
	 */
	private String tablename = "";
	private String tablelevel = "";
	private String tablefull;
	private boolean freqOn;
	private String freqString;
	private boolean forceNoIRSend;
	private boolean pseudoBssOptionEnabled;
	private boolean pseudoBssApplied;
	private boolean[] courseStagePseudoBssApplied;
	private int[] courseStageLntypes;
	// Full list of difficult tables that contains current song
	private List<String> reverseLookup = new ArrayList<>();

	private final BMSLoudnessAnalyzer loudnessAnalyzer;
	private Future<BMSLoudnessAnalyzer.AnalysisResult> analysisTask;

	private double failMeasure = Double.NaN;

	public PlayerResource(AudioDriver audio, Config config, PlayerConfig pconfig, BMSLoudnessAnalyzer loudnessAnalyzer) {
		this.config = config;
		this.pconfig = pconfig;
		this.bmsresource = new BMSResource(audio, config, pconfig);
		this.orgGaugeOption = pconfig.getGauge();
		this.loudnessAnalyzer = loudnessAnalyzer;
	}

	public void clear() {
		course = null;
		courseOrgmode = null;
		courseindex = 0;
		cscore = null;
		score = null;
//		rscore = null;
		tscore = null;
		gauge = null;
		courseReplay.clear();
		coursegauge.clear();
		combo = 0;
		maxcombo = 0;
		bmsPaths = null;
		setTablename("");
		setTablelevel("");
		failMeasure = Double.NaN;
		pseudoBssOptionEnabled = false;
		pseudoBssApplied = false;
		courseStagePseudoBssApplied = null;
		courseStageLntypes = null;
	}

	public boolean setBMSFile(final Path f, BMSPlayerMode mode) {
		// TODO play mode, リプレイデータでの読み込み分岐をここで行う
		this.mode = mode;
		replay = new ReplayData();
		chartResource = SongResources.fromPath(f);
		model = loadBMSModel(chartResource);
		if (model == null) {
			logger.warn("楽曲が存在しないか、解析時にエラーが発生しました:{}", f.toString());
			return false;
		}
		if (model.getAllTimeLines().length == 0) {
			return false;
		}
		pseudoBssOptionEnabled = pconfig != null && pconfig.isPseudoBssAsBss();
		setPseudoBssApplied(model.getLntype(), PseudoBssConverter.isConverted(model));

		if (this.songdata != null && (model.getBackbmp() == null || model.getBackbmp().length() == 0)) {
			if (this.songdata.getBackbmp() != null && this.songdata.getBackbmp().length() > 0) {
				model.setBackbmp(this.songdata.getBackbmp());
			}
		}

		orgmode = model.getMode();
		bmsresource.setBMSFile(model, chartResource, config, mode);
		if(songdata != null) {
			songdata.setBMSModel(model);
		} else {
			songdata = new SongData(model, false);			
		}
		// TODO 選曲の時点で表名、フォルダ名を補完しておきたい
		if(tablename.length() == 0 || courseindex != 0){
			setTableinfo();
		}
		if (config.getAudioConfig().isNormalizeVolume() && loudnessAnalyzer != null && loudnessAnalyzer.isAvailable()) {
			analysisTask = loudnessAnalyzer.analyzeAsync(model);
		} else {
			analysisTask = null;
		}
		return true;
	}

	/**
	 * 譜面を読み込み、譜面のモード (KEY モード) に対応する LN モードを PlayerConfig から適用する
	 *
	 * @param f 譜面ファイル
	 * @return 譜面データ。読み込めなかった場合は null
	 */
	public BMSModel loadBMSModel(Path f) {
		return loadBMSModel(SongResources.fromPath(f));
	}

	/**
	 * 譜面を読み込み、譜面のモード (KEY モード) に対応する LN モードを PlayerConfig から適用する
	 *
	 * @param resource 譜面リソース
	 * @return 譜面データ。読み込めなかった場合は null
	 */
	public BMSModel loadBMSModel(SongResource resource) {
		// LN モードは譜面のモード毎に保持しているため、デコード後に判明したモードの値を適用する。
		// デコーダーは lntype を ChartInformation に格納するだけなので、デコード後の差し替えで結果は変わらない。
		// 疑似BSS/MSS変換は lntype (CN/HCN) を参照するため、適用は変換より前に loadBMSModel 内で行う。
		return loadBMSModel(new ChartInformation(chartSource(resource), BMSModel.LNTYPE_LONGNOTE, null), true);
	}

	/**
	 * 譜面データの LN モード (ChartInformation.lntype) を差し替える
	 *
	 * @param model 譜面データ
	 * @param lnmode LN モード
	 */
	public static void applyLnmode(BMSModel model, int lnmode) {
		final ChartInformation info = model.getChartInformation();
		if (info == null || info.lntype == lnmode) {
			return;
		}
		model.setChartInformation(info.source != null
				? new ChartInformation(info.source, lnmode, info.selectedRandoms)
				: new ChartInformation(info.path, lnmode, info.selectedRandoms));
	}

	public BMSModel loadBMSModel(Path f, int lnmode) {
		return loadBMSModel(SongResources.fromPath(f), lnmode);
	}

	public BMSModel loadBMSModel(SongResource resource, int lnmode) {
		return loadBMSModel(new ChartInformation(chartSource(resource), lnmode, null));
	}

	public BMSModel loadBMSModel(int[] selectedRandom) {
		if(model != null) {
			if (chartResource != null) {
				return loadBMSModel(new ChartInformation(chartSource(chartResource),
						model.getChartInformation().lntype, selectedRandom));
			}
			ChartInformation info = model.getChartInformation();
			return loadBMSModel(new ChartInformation(info.path, info.lntype, selectedRandom));			
		}
		return null;
	}

	public BMSModel loadBMSModel(ChartInformation info) {
		return loadBMSModel(info, false);
	}

	/**
	 * @param info 譜面情報
	 * @param lnmodeByMode true の場合、info.lntype ではなく譜面のモードに対応する PlayerConfig の LN モードを適用する
	 */
	private BMSModel loadBMSModel(ChartInformation info, boolean lnmodeByMode) {
		ChartDecoder decoder = info.source != null ? ChartDecoder.getDecoder(info.source) : ChartDecoder.getDecoder(info.path);
		if(decoder == null) {
			return null;
		}
		BMSModel model = decoder.decode(info);
		if (model == null) {
			return null;
		}
		if (decoder instanceof OSUDecoder) {
			model.setFromOSU(true);
		}

		if (lnmodeByMode && pconfig != null) {
			applyLnmode(model, pconfig.getLnmode(model.getMode()));
		}

		if (pconfig != null && pconfig.isPseudoBssAsBss()) {
			final PseudoBssConverter.Result converted = PseudoBssConverter.convert(model);
			if (!converted.isEmpty()) {
				logger.info("疑似BSS/MSSを変換しました: BSS {} 件, MSS中間ノート {} 件", converted.bss(), converted.mssLinks());
			}
		}

		marginTime = BMSModelUtils.setStartNoteTime(model, 1000);
		BMSPlayerRule.validate(model);

		// 地雷ノートに爆発音が定義されていない場合、デフォルト爆発音をセットする
		final int lanes = model.getMode().key;
		final int wavcount = model.getWavList().length;
		for (TimeLine tl : model.getAllTimeLines()) {
			for (int i = 0; i < lanes; i++) {
				final Note n = tl.getNote(i);
				if (n != null) {
					if (n instanceof MineNote && !n.isDummy() && n.getWav() < 0) {
						n.setWav(wavcount);
					}
				}
			}
		}

		return model;
	}

	private static ChartSource chartSource(SongResource resource) {
		return new ChartSource() {
			@Override
			public String location() {
				return resource.displayPath();
			}

			@Override
			public long lastModifiedTimeMillis() {
				return resource.localPath().map(p -> {
					try {
						return java.nio.file.Files.getLastModifiedTime(p).toMillis();
					} catch (java.io.IOException e) {
						return -1L;
					}
				}).orElse(-1L);
			}

			@Override
			public java.io.InputStream openStream() throws java.io.IOException {
				return resource.openStream();
			}
		};
	}

	public BMSModel getBMSModel() {
		return model;
	}
	
	public long getMarginTime() {
		return marginTime;
	}

	public BMSPlayerMode getPlayMode() {
		return mode;
	}

	public void setPlayMode(BMSPlayerMode mode) {
		this.mode = mode;
	}

	public Config getConfig() {
		return config;
	}

	public PlayerConfig getPlayerConfig() {
		return pconfig;
	}

	public BGAProcessor getBGAManager() {
		return bmsresource.getBGAProcessor();
	}

	public boolean mediaLoadFinished() {
		return bmsresource.mediaLoadFinished();
	}

	public ScoreData getScoreData() {
		return score;
	}

	public void setScoreData(ScoreData score) {
		this.score = score;
	}

	public ScoreData getRivalScoreData() {
		return rscore;
	}

	public void setRivalScoreData(ScoreData rscore) {
		this.rscore = rscore;
	}
	
	public ScoreData getTargetScoreData() {
		return tscore;
	}

	public void setTargetScoreData(ScoreData tscore) {
		this.tscore = tscore;
	}
	
	public RankingData getRankingData() {
		return ranking;
	}
	
	public void setRankingData(RankingData ranking) {
		this.ranking = ranking;
	}
	
	public boolean setCourseBMSFiles(Path[] files) {
		Array<BMSModel> models = new Array();
		for (Path f : files) {
			BMSModel model = loadBMSModel(f);
			if (model == null) {
				return false;
			}
			models.add(model);
		}
		course = models.toArray(BMSModel.class);
		courseOrgmode = course.length > 0 ? course[0].getMode() : null;
		pseudoBssOptionEnabled = pconfig != null && pconfig.isPseudoBssAsBss();
		courseStagePseudoBssApplied = new boolean[course.length];
		courseStageLntypes = new int[course.length];
		for (int i = 0; i < course.length; i++) {
			courseStagePseudoBssApplied[i] = PseudoBssConverter.isConverted(course[i]);
			courseStageLntypes[i] = course[i].getLntype();
		}
		updateCourseScore = true;
		return true;
	}

	public BMSModel[] getCourseBMSModels() {
		return course;
	}

	/**
	 * 現在の譜面に適用している LN モードを返す。
	 * 譜面読み込み時に元譜面のモードから決定した値 ({@link BMSModel#getLntype()}) を返すため、
	 * 7to9 等でモードが変換されていても読み込み時のモードの値になる。
	 * 譜面未読み込みの場合は楽曲データのモード (なければ BEAT_7K) の設定値を返す。
	 *
	 * @return LN モード
	 */
	public int getLnmode() {
		if (model != null) {
			return model.getLntype();
		}
		return songdata != null ? pconfig.getLnmode(songdata.getMode()) : pconfig.getLnmode(bms.model.Mode.BEAT_7K);
	}

	/**
	 * コースのスコア・リプレイ・IR に使用する LN モードを返す。
	 * コース内でモードが混在する場合は 1 曲目のモードの設定値を使用する。
	 *
	 * @return LN モード
	 */
	public int getCourseLnmode() {
		if (course != null && course.length > 0) {
			return pconfig.getLnmode(course[0].getMode());
		}
		return getLnmode();
	}

	public void setAutoPlaySongs(Path[] paths, boolean loop) {
		this.bmsPaths = paths;
		this.loop = loop;
	}
	
	public boolean nextSong() {
		if(bmsPaths == null) {
			return false;
		}
		final int orgindex = courseindex;
		do {
			if(courseindex == bmsPaths.length) {
				if(loop) {
					courseindex = 0;
				} else {
					return false;
				}
			}
			songdata = null;
			if(setBMSFile(bmsPaths[courseindex++], BMSPlayerMode.AUTOPLAY)) {
				return true;
			};
		} while(orgindex != courseindex);
		return false;
	}
	
	public boolean nextCourse() {
		courseindex++;
		if (courseindex == course.length) {
			return false;
		} else {
			songdata = null;
			setBMSFile(Paths.get(course[courseindex].getPath()), mode);
			return true;
		}
	}

	public int getCourseIndex() {
		return courseindex;
	}

	public void reloadBMSFile() {
		if (model != null) {
			model = loadBMSModel(Paths.get(model.getPath()));
		}
		final String name = tablename;
		final String lev = tablelevel;
		clear();
		tablename = name;
		tablelevel = lev;
		pseudoBssOptionEnabled = pconfig != null && pconfig.isPseudoBssAsBss();
		pseudoBssApplied = PseudoBssConverter.isConverted(model);
	}

	/**
	 * 現在のプレイ開始時点で疑似BSS/MSS変換オプションが有効であったかを返す
	 */
	public boolean isPseudoBssOptionEnabled() {
		return pseudoBssOptionEnabled;
	}

	/**
	 * 現在の単曲プレイで疑似BSS/MSS変換が1件以上適用されたかを返す
	 */
	public boolean isPseudoBssApplied() {
		return pseudoBssApplied;
	}

	/**
	 * 現在の単曲（およびコースステージ）の疑似BSS/MSS変換適用状態を更新する
	 */
	public void setPseudoBssApplied(int lntype, boolean applied) {
		this.pseudoBssApplied = applied;
		if (course != null && courseStagePseudoBssApplied != null && courseindex >= 0
				&& courseindex < courseStagePseudoBssApplied.length) {
			courseStagePseudoBssApplied[courseindex] = applied;
			courseStageLntypes[courseindex] = lntype;
		}
	}

	/**
	 * 動的条件分岐等によりプレイ中に疑似BSS/MSS変換が適用されたことを記録する
	 */
	public void recordPseudoBssApplied(int lntype) {
		setPseudoBssApplied(lntype, true);
	}

	/**
	 * コースプレイにおいて1曲以上疑似BSS/MSS変換が適用されたかを返す
	 */
	public boolean isCoursePseudoBssApplied() {
		if (courseStagePseudoBssApplied == null) {
			return false;
		}
		for (boolean applied : courseStagePseudoBssApplied) {
			if (applied) {
				return true;
			}
		}
		return false;
	}

	/**
	 * コースのIR送信用LNタイプを返す。疑似BSS/MSS変換が発生していた場合はCN+(4)またはHCN+(5)に変換する
	 */
	public int getCourseIrLntype(int fallbackLnmode) {
		if (!isCoursePseudoBssApplied()) {
			return fallbackLnmode;
		}
		int convertedLntype = fallbackLnmode;
		if (!PseudoBssConverter.isTargetLntype(convertedLntype) && courseStagePseudoBssApplied != null
				&& courseStageLntypes != null) {
			for (int i = 0; i < courseStagePseudoBssApplied.length; i++) {
				if (courseStagePseudoBssApplied[i] && PseudoBssConverter.isTargetLntype(courseStageLntypes[i])) {
					convertedLntype = Math.max(convertedLntype, courseStageLntypes[i]);
				}
			}
		}
		return IRScoreData.toIrLntype(convertedLntype, true);
	}

	public FloatArray[] getGauge() {
		return gauge;
	}

	public void setGauge(FloatArray[] gauge) {
		this.gauge = gauge;
	}

	public GrooveGauge getGrooveGauge() {
		return grooveGauge;
	}

	public void setGrooveGauge(GrooveGauge grooveGauge) {
		this.grooveGauge = grooveGauge;
	}

	public ReplayData getReplayData() {
		return replay;
	}

	public void setReplayData(ReplayData replay) {
		this.replay = replay;
	}

	public ReplayData getPlayInfo() {
		return playinfo;
	}

	public void setPlayInfo(ReplayData playinfo) {
		this.playinfo = playinfo;
	}

	public ScoreData getCourseScoreData() {
		return cscore;
	}

	public void setCourseScoreData(ScoreData cscore) {
		this.cscore = cscore;
	}

	public boolean isUpdateScore() {
		return updateScore;
	}

	public void setUpdateScore(boolean b) {
		this.updateScore = b;
	}

	public boolean isUpdateCourseScore() {
		return updateCourseScore;
	}

	public void setUpdateCourseScore(boolean updateCourseScore) {
		this.updateCourseScore = updateCourseScore;
	}

	public CourseData getCourseData() {
		return coursedata;
	}

	public void setCourseData(CourseData coursedata) {
		this.coursedata = coursedata;
	}
	
	public String getCoursetitle() {
		return coursedata != null ? coursedata.getName() : null;
	}
	
	public CourseDataConstraint[] getConstraint() {
		return coursedata != null ? coursedata.getConstraint() : new CourseDataConstraint[0];
	}

	public ReplayData[] getCourseReplay() {
		return courseReplay.toArray(ReplayData.class);
	}

	public void addCourseReplay(ReplayData rd) {
		courseReplay.add(rd);
	}

	public Array<FloatArray[]> getCourseGauge() {
		return coursegauge;
	}

	public void addCourseGauge(FloatArray[] gauge) {
		coursegauge.add(gauge);
	}

	public int getCombo() {
		return combo;
	}

	public void setCombo(int combo) {
		this.combo = combo;
	}

	public int getMaxcombo() {
		return maxcombo;
	}

	public void setMaxcombo(int maxcombo) {
		this.maxcombo = maxcombo;
	}

	public void dispose() {
		if(bmsresource != null) {
			bmsresource.dispose();
			bmsresource = null;
		}
	}

	public SongData getSongdata() {
		return songdata;
	}

	public void setSongdata(SongData songdata) {
		this.songdata = songdata;
	}

	public BMSResource getBMSResource() {
		return bmsresource;
	}
	
	public int getOrgGaugeOption() {
		return orgGaugeOption;
	}

	public void setOrgGaugeOption(int orgGaugeOption) {
		this.orgGaugeOption = orgGaugeOption;
	}

	public int getAssist() {
		return assist;
	}

	public void setAssist(int assist) {
		this.assist = assist;
	}

	public String getTablename() {
		return tablename;
	}

	public void setTablename(String tablename) {
		this.tablename = tablename;
		this.tablefull = null;
	}

	public String getTablelevel() {
		return tablelevel;
	}

	public void setTablelevel(String tablelevel) {
		this.tablelevel = tablelevel;
		this.tablefull = null;
	}

	public String getTableFullname() {
		if(tablefull == null) {
			tablefull = tablelevel + tablename;
		}
		return tablefull;
	}


	public PlayerData getPlayerData() {
		return playerdata;
	}

	public void setPlayerData(PlayerData playerdata) {
		this.playerdata = playerdata;
	}

	private void setTableinfo(){
		final String[] urls = this.getConfig().getTableURL();
		final TableDataAccessor tdaccessor = new TableDataAccessor(config.getTablepath());
		final TableData[] tds = tdaccessor.readAll();
		for(String url: urls){
			for(TableData td: tds){
				if(td.getUrl().equals(url)){
					final TableFolder[] tfs = td.getFolder();
					for(TableFolder tf: tfs){
						final SongData[] tss = tf.getSong();
						for(SongData ts: tss){
							if((ts.getMd5().length() != 0 && this.getSongdata().getMd5().length() != 0 &&
									ts.getMd5().equals(this.getSongdata().getMd5()))||
									(ts.getMd5().length() != 0 && this.getSongdata().getMd5().length() != 0 &&
									ts.getSha256().equals(this.getSongdata().getSha256()))){
								setTablename(td.getName());
								setTablelevel(tf.getName());
								return;
							}
						}
					}
				}
			}
		}
		setTablename("");
		setTablelevel("");
	}

	public List<String> getReverseLookupData() {
		Set<String> urlSet = new HashSet<>(List.of(this.getConfig().getTableURL()));
		TableDataAccessor tdaccessor = new TableDataAccessor(config.getTablepath());
		TableData[] tds = tdaccessor.readAll();
		List<String> reverseLookup = new ArrayList<>();
		for (TableData td : tds) {
			if (!urlSet.contains(td.getUrl())) {
				continue;
			}
			TableFolder[] tfs = td.getFolder();
			boolean found = false;
			for (TableFolder tf : tfs) {
				SongData[] tss = tf.getSong();
				for (SongData ts : tss) {
					boolean matchOnMd5 = !ts.getMd5().isEmpty() && ts.getMd5().equals(this.getSongdata().getMd5());
					boolean matchOnSha256 = !ts.getSha256().isEmpty() && ts.getSha256().equals(this.getSongdata().getSha256());
					if (matchOnMd5 || matchOnSha256) {
						found = true;
						break;
					}
				}
				if (found) {
					reverseLookup.add(td.getName() + " " + tf.getName());
					break;
				}
			}
		}
		return reverseLookup;
	}

    public List<String> getReverseLookupLevels() {
        Set<String> urlSet = new HashSet<>(List.of(this.getConfig().getTableURL()));
        TableDataAccessor tdaccessor = new TableDataAccessor(config.getTablepath());
        TableData[] tds = tdaccessor.readAll();
        List<String> reverseLookup = new ArrayList<>();
        for (TableData td : tds) {
            if (!urlSet.contains(td.getUrl())) {
                continue;
            }
            TableFolder[] tfs = td.getFolder();
            boolean found = false;
            for (TableFolder tf : tfs) {
                SongData[] tss = tf.getSong();
                for (SongData ts : tss) {
                    boolean matchOnMd5 = !ts.getMd5().isEmpty() && ts.getMd5().equals(this.getSongdata().getMd5());
                    boolean matchOnSha256 = !ts.getSha256().isEmpty() && ts.getSha256().equals(this.getSongdata().getSha256());
                    if (matchOnMd5 || matchOnSha256) {
                        found = true;
                        break;
                    }
                }
                if (found) {
                    reverseLookup.add(tf.getName());
                    break;
                }
            }
        }
        return reverseLookup;
    }

	public ReplayData getChartOption() {
		return chartOption;
	}

	public void setChartOption(ReplayData chartOption) {
		this.chartOption = chartOption;
	}

	/**
	 * KEY モード毎の設定 (ターゲットスコア・ゲージオートシフト等) の参照先とするモードを返す。
	 * <ul>
	 * <li>コース: 1 曲目の読み込み時のモード (コース内でモードが混在していても 1 曲目を代表とする)</li>
	 * <li>楽曲: 読み込み時の譜面のモード (7to9 等の変換前)</li>
	 * <li>譜面未読み込み: 楽曲データのモード (なければ BEAT_7K)</li>
	 * </ul>
	 *
	 * @return 対象モード (null にはならない)
	 */
	public bms.model.Mode getConfigMode() {
		if (course != null && courseOrgmode != null) {
			return courseOrgmode;
		}
		if (orgmode != null) {
			return orgmode;
		}
		if (songdata != null) {
			for (bms.model.Mode m : bms.model.Mode.values()) {
				if (m.id == songdata.getMode()) {
					return m;
				}
			}
		}
		return bms.model.Mode.BEAT_7K;
	}

	public bms.model.Mode getOriginalMode() {
		return orgmode;
	}

	public void setOriginalMode(bms.model.Mode orgmode) {
		this.orgmode = orgmode;
	}

	public boolean isFreqOn() {
		return freqOn;
	}

	public void setFreqOn(boolean freqOn) {
		this.freqOn = freqOn;
	}

	public String getFreqString() {
		return freqString;
	}

	public void setFreqString(String freqString) {
		this.freqString = freqString;
	}

	public boolean isForceNoIRSend() {
		return forceNoIRSend;
	}

	public void setForceNoIRSend(boolean forceNoIRSend) {
		this.forceNoIRSend = forceNoIRSend;
	}

	public Future<BMSLoudnessAnalyzer.AnalysisResult> getAnalysisTask() {
		return analysisTask;
	}

	public double getFailMeasure() {
		return failMeasure;
	}

	public void setFailMeasure(double failMeasure) {
		this.failMeasure = failMeasure;
	}
}
