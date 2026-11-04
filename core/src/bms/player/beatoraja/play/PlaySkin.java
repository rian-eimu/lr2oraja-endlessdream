package bms.player.beatoraja.play;

import static bms.player.beatoraja.skin.SkinProperty.OFFSET_LIFT;
import static bms.player.beatoraja.skin.SkinProperty.OFFSET_LIFT_OBSOLETE;

import java.util.ArrayList;
import java.util.List;

import bms.player.beatoraja.MainState;
import bms.player.beatoraja.Resolution;
import bms.player.beatoraja.skin.*;

import com.badlogic.gdx.math.Rectangle;


/**
 * プレイスキン
 *
 * @author exch
 */
public class PlaySkin extends Skin {
	/**
	 * STATE_READYからSTATE_PLAYに移行するまでのマージン(ms)
	 */
	private int playstart;

	private SkinImage[] line = new SkinImage[0];
	private SkinImage[] time = new SkinImage[0];
	private SkinImage[] bpm = new SkinImage[0];
	private SkinImage[] stop = new SkinImage[0];

	private Rectangle[] laneregion;
	private Rectangle[] lanegroupregion;

	private int judgeregion;
	/**
	 * STATE_FAILEDからプレイヤーを終了するまでのマージン(ms)
	 */
	private int close;

	/**
	 * STATE_FINISHEDからフェードアウトを開始するまでのマージン(ms)
	 */
	private int finishMargin = 0;

	private int loadstart;
	private int loadend;
	
	/**
	 * 各レーンの判定タイマーを発動するときの判定条件。(0:PG, 1:GR, 2:GD, 3:BD)
	 */
	private int judgetimer = 1;

	/**
	 * PMSのリズムに合わせたノートの拡大の最大拡大率(%) w h
	 */
	private int[] noteExpansionRate = {100,100};

	public SkinSlider laneCover;

	private SkinPractice practice;

	public final PomyuCharaProcessor pomyu = new PomyuCharaProcessor();

	private final List<SkinObject> judgeLines = new ArrayList<>();

	public void addJudgeLine(SkinObject jl) {
		if (jl != null) {
			judgeLines.add(jl);
		}
	}

	@Override
	public void prepare(MainState state) {
		final SkinObject[] allObjects = getAllSkinObjects();
		for (int i = 0; i < allObjects.length; i++) {
			if (allObjects[i] instanceof SkinNote skinNote) {
				final SkinNote.SkinFreeZone freeZone = skinNote.getFreeZoneObject();
				freeZone.setOffsetID(skinNote.getOffsetID());
				boolean alreadyAdded = false;
				for (SkinObject existing : allObjects) {
					if (existing == freeZone) {
						alreadyAdded = true;
						break;
					}
				}
				if (!alreadyAdded) {
					SkinObject target = null;
					for (int j = 0; j < i; j++) {
						final SkinObject candidate = allObjects[j];
						final String name = candidate.getName();
						if (judgeLines.contains(candidate)
								|| "15".equals(name)
								|| (name != null && name.toLowerCase().contains("judgeline"))) {
							target = candidate;
							break;
						}
					}
					if (target == null) {
						for (int j = 0; j < i; j++) {
							final SkinObject candidate = allObjects[j];
							for (int off : candidate.getOffsetID()) {
								if (off == OFFSET_LIFT || off == OFFSET_LIFT_OBSOLETE) {
									target = candidate;
									break;
								}
							}
							if (target != null) {
								break;
							}
						}
					}
					insertObjectBefore(target != null ? target : skinNote, freeZone);
				}
			}
		}
		super.prepare(state);
	}
	
	public PlaySkin(SkinHeader header) {
		super(header);
	}
	
	public Rectangle[] getLaneGroupRegion() {
		return lanegroupregion;
	}

	public void setLaneGroupRegion(Rectangle[] r) {
		lanegroupregion = r;
	}

	public Rectangle[] getLaneRegion() {
		return laneregion;
	}

	public void setLaneRegion(Rectangle[] r) {
		laneregion = r;
	}

	public void setJudgeregion(int jr) {
		judgeregion = jr;
	}

	public int getJudgeregion() {
		return judgeregion;
	}

	public int getClose() {
		return close;
	}

	public void setClose(int close) {
		this.close = close;
	}

	public int getFinishMargin() {
		return finishMargin;
	}

	public void setFinishMargin(int finishMargin) {
		this.finishMargin = finishMargin;
	}

	public int getPlaystart() {
		return playstart;
	}

	public void setPlaystart(int playstart) {
		this.playstart = playstart;
	}

	public SkinImage[] getLine() {
		return line;
	}

	public SkinImage[] getBPMLine() {
		return bpm;
	}

	public SkinImage[] getStopLine() {
		return stop;
	}

	public void setLine(SkinImage[] line) {
		this.line = line;
	}

	public void setBPMLine(SkinImage[] bpm) {
		this.bpm = bpm;
	}

	public void setStopLine(SkinImage[] stop) {
		this.stop = stop;
	}

	public SkinImage[] getTimeLine() {
		return time;
	}
	
	public void setTimeLine(SkinImage[] time) {
		this.time = time;
	}

	public int getLoadstart() {
		return loadstart;
	}

	public void setLoadstart(int loadstart) {
		this.loadstart = loadstart;
	}

	public int getLoadend() {
		return loadend;
	}

	public void setLoadend(int loadend) {
		this.loadend = loadend;
	}
	
	public int getJudgetimer() {
		return judgetimer;
	}
	
	public void setJudgetimer(int judgetimer) {
		this.judgetimer = judgetimer;
	}

	public int[] getNoteExpansionRate() {
		return noteExpansionRate;
	}

	public void setNoteExpansionRate(int[] rate) {
		this.noteExpansionRate = rate;
	}

	public SkinPractice getPractice() {
		return practice;
	}

	public boolean hasPractice() {
		return practice != null;
	}

	public void setPractice(SkinPractice practice) {
		this.practice = practice;
	}
}
