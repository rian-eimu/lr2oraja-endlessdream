package bms.player.beatoraja.play;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

import bms.model.BMSModel;
import bms.model.LongNote;
import bms.model.Mode;
import bms.model.Note;
import bms.model.NormalNote;
import bms.model.TimeLine;

/**
 * LN モード前提で作られた「疑似 BSS」「疑似 MSS」を、CN / HCN モード時に BSS / MSS として扱えるよう変換する。
 * <p>
 * LN モードでは LN 終端は判定されないため、譜面作者は次のように BSS / MSS を表現する。
 * <ul>
 * <li>疑似 BSS: スクラッチ LN の直後に通常ノーツを置き、「回し続けて逆回転」を表す。</li>
 * <li>疑似 MSS: スクラッチ LN を連続させ、次の LN 始端で切り返し、最後に通常ノーツで逆回転する。</li>
 * </ul>
 * これを CN モードで読み込むと、LN 終端ごとに余計な判定が増え、想定と大きく異なる譜面になる。
 * <p>
 * 本変換は次の 2 つを行う。
 * <ul>
 * <li>BSS 化: LN 終端を直後の通常ノーツの位置まで延長し、通常ノーツを取り除く。
 * CN 始端 + BSS 終端 (逆回転で離す) の 2 判定になる。</li>
 * <li>MSS 化: LN 終端を直後の LN 始端の直前 ({@link #MSS_LINK_SECTION_OFFSET} 小節、
 * {@link #MSS_LINK_TIME_OFFSET} マイクロ秒前) まで延長し、切れ目のない BSS の連続にする。
 * jbms-parser の TimeLine は 1 レーンに 1 ノーツしか置けないため、終端用の TimeLine を新たに挿入する。
 * 判定側 ({@link JudgeManager}) は「スクラッチ CN 終端の直後 {@link #MSS_LINK_MAX_GAP_MICRO} マイクロ秒以内に
 * 次の CN 始端がある」ものを MSS の中間ノートとして扱い、1 回の切り返しで終端と次の始端を両方判定する。
 * 中間ノート 1 つにつき 2 判定となり、総ノーツ数は (始点ノート + 中間ノート総数) * 2 になる。</li>
 * </ul>
 * <p>
 * 対象条件:
 * <ul>
 * <li>モデルの LN タイプが CN または HCN (譜面側で LN 種別未定義の LN のみ対象)</li>
 * <li>スクラッチレーン上の LN (ダミー・FreeZone 内でない)</li>
 * <li>LN 終端の次にある同レーンのノーツが、通常ノーツまたは LN 始端 (いずれもダミー・FreeZone 内でない) で、
 * 終端からの距離が {@link #MAX_GAP_SECTION} 小節以内</li>
 * <li>次のノーツが LN 終端より粗い拍のグリッドに乗っている ({@link #isReleaseOnCoarserGrid}。
 * 例: 終端が 16 分の裏、ノーツが拍頭)</li>
 * </ul>
 * jbms-parser は LN 終端と同位置の通常ノーツを既に破棄するため、距離 0 のケースは対象外となる。
 */
public final class PseudoBssConverter {

	/**
	 * LN 終端と次のノーツの最大間隔 (小節単位、4/4 換算)。16 分音符 1 つ分。
	 */
	public static final double MAX_GAP_SECTION = 1.0 / 16;

	/**
	 * MSS の中間ノートで、終端を次の始端の何小節前に置くか。
	 * 同じ section だと ConditionalBranchController の section キーが衝突するためずらす。
	 */
	public static final double MSS_LINK_SECTION_OFFSET = 1e-6;

	/**
	 * MSS の中間ノートで、終端を次の始端の何マイクロ秒前に置くか。
	 * 同じ時刻だと LaneRenderer の補間で 0 除算になるためずらす。
	 */
	public static final long MSS_LINK_TIME_OFFSET = 1;

	/**
	 * 判定側で MSS の中間ノートとみなす「CN 終端 → 次の CN 始端」の最大時間差 (マイクロ秒)。
	 * 再生速度変更で時間差が伸びても収まるよう余裕を持たせる。通常の譜面ではこの間隔に CN が並ぶことはない。
	 */
	public static final long MSS_LINK_MAX_GAP_MICRO = 100;

	/**
	 * 変換結果
	 *
	 * @param bss BSS 化した疑似 BSS の数
	 * @param mssLinks MSS の中間ノートとして連結した LN 境目の数
	 */
	public record Result(int bss, int mssLinks) {
		public boolean isEmpty() {
			return bss == 0 && mssLinks == 0;
		}
	}

	private static final Result EMPTY = new Result(0, 0);

	private PseudoBssConverter() {
	}

	/**
	 * 変換対象となる LN タイプかどうか
	 */
	public static boolean isTargetLntype(int lntype) {
		return lntype == BMSModel.LNTYPE_CHARGENOTE || lntype == BMSModel.LNTYPE_HELLCHARGENOTE;
	}

	/**
	 * 疑似 BSS / 疑似 MSS を変換する。モデルの LN タイプが CN / HCN でなければ何もしない。
	 *
	 * @param model 変換対象のモデル (直接書き換える)
	 * @return 変換結果
	 */
	public static Result convert(BMSModel model) {
		if (model == null || !isTargetLntype(model.getLntype())) {
			return EMPTY;
		}
		final Mode mode = model.getMode();
		if (mode == null) {
			return EMPTY;
		}
		final TimeLine[] timelines = model.getAllTimeLines();
		final double[] measureStarts = measureStarts(timelines);
		// MSS 終端用に挿入する TimeLine (キー: 直後に来る既存 TimeLine の index)
		final TreeMap<Integer, TimeLine> inserts = new TreeMap<>();
		int bss = 0;
		int mss = 0;
		for (int lane : mode.scratchKey) {
			if (lane < 0 || lane >= mode.key) {
				continue;
			}
			for (int i = 0; i < timelines.length; i++) {
				final Note n = timelines[i].getNote(lane);
				if (!(n instanceof LongNote end) || !end.isEnd() || !isConvertible(end)) {
					continue;
				}
				final LongNote start = end.getPair();
				if (start == null || !isConvertible(start)) {
					continue;
				}
				// LN 終端の次にある同レーンのノーツを探す
				int next = -1;
				for (int j = i + 1; j < timelines.length; j++) {
					if (timelines[j].getNote(lane) != null) {
						next = j;
						break;
					}
				}
				if (next < 0) {
					continue;
				}
				final TimeLine nextTl = timelines[next];
				final double gap = nextTl.getSection() - timelines[i].getSection();
				if (gap <= 0 || gap > MAX_GAP_SECTION + 1e-9
						|| !isReleaseOnCoarserGrid(measureStarts, timelines[i].getSection(), nextTl.getSection())) {
					continue;
				}
				final Note target = nextTl.getNote(lane);
				if (target instanceof NormalNote && !target.isDummy() && !target.isFreeZoneInner()) {
					convertToBss(timelines[i], nextTl, lane, start, end, target);
					bss++;
					i = next;
				} else if (target instanceof LongNote nextStart && !nextStart.isEnd() && isConvertible(nextStart)
						&& nextStart.getPair() != null) {
					final TimeLine linkTl = inserts.computeIfAbsent(next,
							k -> createLinkTimeLine(timelines[k - 1], timelines[k], mode.key));
					timelines[i].setNote(lane, null);
					linkTl.setNote(lane, end);
					start.setPair(end);
					mss++;
				}
			}
		}
		if (!inserts.isEmpty()) {
			final List<TimeLine> merged = new ArrayList<>(timelines.length + inserts.size());
			for (int i = 0; i < timelines.length; i++) {
				final TimeLine linkTl = inserts.get(i);
				if (linkTl != null) {
					merged.add(linkTl);
				}
				merged.add(timelines[i]);
			}
			model.setAllTimeLine(merged.toArray(new TimeLine[0]));
		}
		return bss == 0 && mss == 0 ? EMPTY : new Result(bss, mss);
	}

	/**
	 * 拍のグリッド (384 単位/小節)。4分, 8分, 16分, 32分, 64分, 128分 の順に細かくなる。
	 */
	private static final int[] GRID_STEPS = { 96, 48, 24, 12, 6, 3 };

	/**
	 * 次のノーツが LN 終端より粗い拍のグリッドに乗っているか。
	 * <p>
	 * 疑似 BSS は「LN を拍の 16 分手前などで切り、逆回転のノーツを拍頭 / 8 分の位置に置く」形が大半
	 * (BMS 2 万 2 千譜面の調査で 16 分空きの 93%)。一方、公式譜面で BSS 終端の後ろに独立したノーツを置く配置は
	 * 「BSS 終端が拍頭 / 8 分、ノーツが 16 分の裏」が 94% で、並びが逆になる。
	 * ノーツの方が粗いグリッドに乗る場合のみ変換することで、公式由来の配置の誤変換を防ぐ。
	 *
	 * @param measureStarts 小節頭の section (昇順)
	 * @param endSection LN 終端の section
	 * @param nextSection 次のノーツの section
	 */
	static boolean isReleaseOnCoarserGrid(double[] measureStarts, double endSection, double nextSection) {
		return gridLevel(nextSection - measureStartOf(measureStarts, nextSection))
				< gridLevel(endSection - measureStartOf(measureStarts, endSection));
	}

	/**
	 * 小節頭からの位置 (section 単位、4/4 換算) が乗っている最も粗いグリッドの段を返す。
	 * 0 = 拍頭 (4 分)、値が大きいほど細かい。どのグリッドにも乗らない場合は {@code GRID_STEPS.length}。
	 */
	static int gridLevel(double relSection) {
		final double u = relSection * 384;
		for (int i = 0; i < GRID_STEPS.length; i++) {
			final double k = u / GRID_STEPS[i];
			if (Math.abs(k - Math.rint(k)) < 1e-4) {
				return i;
			}
		}
		return GRID_STEPS.length;
	}

	/**
	 * 小節線のある TimeLine の section を昇順で返す。
	 * 小節内に小節線を追加する拡張チャンネル (ST) がある譜面では、その位置も小節頭として扱われる。
	 */
	static double[] measureStarts(TimeLine[] timelines) {
		final double[] tmp = new double[timelines.length + 1];
		int n = 0;
		tmp[n++] = 0;
		for (TimeLine tl : timelines) {
			if (tl.getSectionLine() && tl.getSection() > tmp[n - 1]) {
				tmp[n++] = tl.getSection();
			}
		}
		return java.util.Arrays.copyOf(tmp, n);
	}

	private static double measureStartOf(double[] starts, double section) {
		double s = 0;
		for (double x : starts) {
			if (x <= section + 1e-9) {
				s = x;
			} else {
				break;
			}
		}
		return s;
	}

	/**
	 * 変換対象にできる LN か (種別未定義・ダミーでない・FreeZone 内でない)
	 */
	private static boolean isConvertible(LongNote ln) {
		return ln.getType() == LongNote.TYPE_UNDEFINED && !ln.isDummy() && !ln.isFreeZoneInner();
	}

	private static void convertToBss(TimeLine endTl, TimeLine targetTl, int lane, LongNote start, LongNote end, Note target) {
		// 通常ノーツの位置に新しい終端を置き、通常ノーツの音を終端の音として引き継ぐ
		final LongNote newEnd = new LongNote(target.getWav());
		newEnd.setMicroStarttime(target.getMicroStarttime());
		newEnd.setMicroDuration(target.getMicroDuration());
		endTl.setNote(lane, null);
		if (end.getWav() >= 0) {
			// 元の終端に音が割り当てられていた場合は BG ノーツとして残す
			final NormalNote bg = new NormalNote(end.getWav());
			bg.setMicroStarttime(end.getMicroStarttime());
			bg.setMicroDuration(end.getMicroDuration());
			endTl.addBackGroundNote(bg);
		}
		targetTl.setNote(lane, newEnd);
		for (Note layered : target.getLayeredNotes()) {
			newEnd.addLayeredNote(layered);
		}
		for (Note layered : end.getLayeredNotes()) {
			endTl.addBackGroundNote(layered);
		}
		start.setPair(newEnd);
	}

	/**
	 * MSS 中間ノートの終端を置く TimeLine を作る。次の TimeLine の直前に置き、
	 * BPM・スクロール・スピードは直前の TimeLine (その時点で有効な値) を引き継ぐ。
	 */
	private static TimeLine createLinkTimeLine(TimeLine prev, TimeLine next, int keys) {
		final double section = Math.max(next.getSection() - MSS_LINK_SECTION_OFFSET,
				(prev.getSection() + next.getSection()) / 2);
		final long time = Math.max(next.getMicroTime() - MSS_LINK_TIME_OFFSET, prev.getMicroTime());
		final TimeLine tl = new TimeLine(section, time, keys);
		tl.setBPM(prev.getBPM());
		tl.setScroll(prev.getScroll());
		tl.setSpeed(prev.getSpeed());
		return tl;
	}
}
