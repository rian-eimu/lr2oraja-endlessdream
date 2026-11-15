package bms.player.beatoraja.play;

import static bms.player.beatoraja.skin.SkinProperty.*;

import java.util.Arrays;
import java.util.stream.IntStream;

import com.badlogic.gdx.utils.FloatArray;

import bms.model.*;
import bms.player.beatoraja.*;
import bms.player.beatoraja.audio.AudioDriver;
import bms.player.beatoraja.input.BMSPlayerInputProcessor;
import bms.player.beatoraja.play.JudgeProperty.MissCondition;
import bms.player.beatoraja.play.JudgeProperty.NoteType;
import bms.player.beatoraja.skin.SkinPropertyMapper;

/**
 * ノーツ判定管理用クラス
 *
 * @author exch
 */
public class JudgeManager {

    // TODO HCN押し直しの発音はどうする？

    private final BMSPlayer main;
    private final AudioDriver keysound;
    /**
     * LN type
     */
    private int lntype;

    /**
     * 現在の判定カウント内訳
     */
    private ScoreData score = new ScoreData();

    /**
     * 現在のコンボ数
     */
    private int combo;
    /**
     * コース時の現在のコンボ数
     */
    private int coursecombo;
    /**
     * コース時の最大コンボ数
     */
    private int coursemaxcombo;
    /**
     * 判定連動レーザーの色
     */
    private int[][] judge;
    /**
     * 現在表示中の判定
     */
    private int[] judgenow;
    private int[] judgecombo;
    /**
     * ゴースト記録用の判定ログ
     */
    private int[] ghost;
    /**
     * 判定差時間(ms , +は早押しで-は遅押し)
     */
    private long[] judgefast;
    
    private long[] mjudgefast;
    
    private LaneState[] states;

    private int[] keyassign;

    private int[] sckey;
    /**
     * HCNの増減間隔(ms)
     */
    private static final int hcnmduration = 200000;
    /**
     * ノーツ判定テーブル
     */
    private long[][] nmjudge;
    private long mjudgestart;
    private long mjudgeend;
    /**
     * CN終端判定テーブル
     */
    private long[][] cnendmjudge;
    private long nreleasemargin;
    /**
     * スクラッチ判定テーブル
     */
    private long[][] smjudge;
    private long[][] scnendmjudge;
    private long sreleasemargin;
    /**
     * PMS用判定システム(空POORでコンボカット、1ノーツにつき1空POORまで)の有効/無効
     */
    private boolean[] combocond;

    private MissCondition miss;
    /**
     * 各判定毎のノートの判定を消失するかどうか。PG, GR, GD, BD, PR, MSの順
     */
    private boolean[] judgeVanish;

    private long prevmtime;
    private long lastBranchEvalTime = Long.MIN_VALUE;

    private boolean autoplay = false;
    private long[] auto_presstime;
    /**
     * オートプレイでキーを押下する最小時間(ms)
     */
    private final int auto_minduration = 80;

    private JudgeAlgorithm algorithm;

    /**
     * 直近100ノーツの判定差時間
     */
    private long[] recentJudges = new long[100];
    private long[] microrecentJudges = new long[100];
    /**
     * 判定差時間のヘッド
     */
    private int recentJudgesIndex = 0;

    private int pressesSinceLastAutoadjust = 0;

    private MultiBadCollector multiBadCollector = new MultiBadCollector();

    public JudgeManager(BMSPlayer main) {
        this.main = main;
        this.keysound = main.main.getAudioProcessor();
    }

    public void init(BMSModel model, PlayerResource resource) {
        final Mode orgmode = resource.getOriginalMode();
        prevmtime = 0;
        lastBranchEvalTime = Long.MIN_VALUE;
        final int  judgeregion = main.getSkin() instanceof PlaySkin ? ((PlaySkin) main.getSkin()).getJudgeregion() : 0;
        judgenow = new int[judgeregion];
        judgecombo = new int[judgeregion];
        judgefast = new long[judgeregion];
        mjudgefast = new long[judgeregion];
        score = new ScoreData(orgmode);
        score.setNotes(model.getTotalNotes());
        score.setSha256(model.getSHA256());
        ghost = new int[model.getTotalNotes()];
        for (int i=0; i<ghost.length; i++) {
            ghost[i] = 4;
        }

        this.lntype = model.getLntype();
        Lane[] lanes = model.getLanes();

        algorithm = JudgeAlgorithm.valueOf(resource.getPlayerConfig().getPlayConfig(orgmode).getPlayconfig().getJudgetype());
        JudgeProperty rule = BMSPlayerRule.getBMSPlayerRule(orgmode).judge;
    
        if(resource.getPlayerConfig().isDxMode() && model.getMode() == Mode.POPN_9K) {
        	rule = JudgeProperty.POP;
        }
        else if(resource.getPlayerConfig().isDxMode()) {
        	rule = JudgeProperty.IIDX;
        }
        
        score.setJudgeAlgorithm(algorithm);
        score.setRule(BMSPlayerRule.getBMSPlayerRule(orgmode));
        
        combocond = rule.combo;
        miss = rule.miss;
        judgeVanish = rule.judgeVanish;

        keyassign = main.getLaneProperty().getKeyLaneAssign();
        sckey = new int[model.getMode().scratchKey.length];

        judge = new int[model.getMode().player][model.getMode().key / model.getMode().player + 1];
        auto_presstime = new long[keyassign.length];

        states = IntStream.range(0, lanes.length).mapToObj(i -> new LaneState(i, main.getLaneProperty(), lanes[i])).toArray(LaneState[]::new);
        

        for (int key = 0; key < keyassign.length; key++) {
            auto_presstime[key] = Long.MIN_VALUE;
        }

        final int judgerank = model.getJudgerank();
        final PlayerConfig config = resource.getPlayerConfig();
        final int[] keyJudgeWindowRate = config.isCustomJudge() && !config.isDxMode()
                ? new int[]{config.getKeyJudgeWindowRatePerfectGreat(), config.getKeyJudgeWindowRateGreat(), config.getKeyJudgeWindowRateGood()}
                : new int[]{100, 100, 100};
        final int[] scratchJudgeWindowRate = config.isCustomJudge() && !config.isDxMode()
                ? new int[]{config.getScratchJudgeWindowRatePerfectGreat(), config.getScratchJudgeWindowRateGreat(), config.getScratchJudgeWindowRateGood()}
                : new int[]{100, 100, 100};
        for (CourseData.CourseDataConstraint mode : resource.getConstraint()) {
            if (mode == CourseData.CourseDataConstraint.NO_GREAT) {
                keyJudgeWindowRate[1] = keyJudgeWindowRate[2] = 0;
                scratchJudgeWindowRate[1] = scratchJudgeWindowRate[2] = 0;
            } else if (mode == CourseData.CourseDataConstraint.NO_GOOD) {
                keyJudgeWindowRate[2] = 0;
                scratchJudgeWindowRate[2] = 0;
            }
        }
        
        nmjudge = rule.getJudge(NoteType.NOTE, judgerank, keyJudgeWindowRate);
        cnendmjudge = rule.getJudge(NoteType.LONGNOTE_END, judgerank, keyJudgeWindowRate);
        nreleasemargin = (config.isCustomJudge() && !config.isDxMode()) ? rule.longnoteMargin * config.getLongnoteMarginRate() / 100L : rule.longnoteMargin;
        smjudge = rule.getJudge(NoteType.SCRATCH, judgerank, scratchJudgeWindowRate);
        scnendmjudge = rule.getJudge(NoteType.LONGSCRATCH_END, judgerank, scratchJudgeWindowRate);
        sreleasemargin = rule.longscratchMargin;
        mjudgestart = mjudgeend = 0;
        for (long[] l : nmjudge) {
            mjudgestart = Math.min(mjudgestart, l[0]);
            mjudgeend = Math.max(mjudgeend, l[1]);
        }
        for (long[] l : smjudge) {
            mjudgestart = Math.min(mjudgestart, l[0]);
            mjudgeend = Math.max(mjudgeend, l[1]);
        }

        this.autoplay = resource.getPlayMode().mode == BMSPlayerMode.Mode.AUTOPLAY;

        FloatArray[] f = resource.getGauge();
        if (f != null) {
            setCourseCombo(resource.getCombo());
            setCourseMaxcombo(resource.getMaxcombo());
        }

        Arrays.fill(recentJudges, Long.MIN_VALUE);
        this.recentJudgesIndex = 0;
        multiBadCollector = new MultiBadCollector();
    }
    
    /**
     * 指定のロングノートがLNであるか判定する
     */
    private boolean isLongNote(LongNote ln) {
        return (lntype == BMSModel.LNTYPE_LONGNOTE && ln.getType() == LongNote.TYPE_UNDEFINED)
                || ln.getType() == LongNote.TYPE_LONGNOTE;
    }

    /**
     * 指定のロングノートがHLNであるか判定する
     */
    private boolean isHellLongNote(LongNote ln) {
        return (lntype == BMSModel.LNTYPE_HELLLONGNOTE && ln.getType() == LongNote.TYPE_UNDEFINED)
                || ln.getType() == LongNote.TYPE_HELLLONGNOTE;
    }

    /**
     * 指定のロングノートがHCNであるか判定する
     */
    private boolean isHellChargeNote(LongNote ln) {
        return (lntype == BMSModel.LNTYPE_HELLCHARGENOTE && ln.getType() == LongNote.TYPE_UNDEFINED)
                || ln.getType() == LongNote.TYPE_HELLCHARGENOTE;
    }

    /**
     * 指定のロングノートがCNまたはHCNであるか判定する
     */
    private boolean isChargeOrHellChargeNote(LongNote ln) {
        return !isLongNote(ln) && !isHellLongNote(ln);
    }

    /**
     * 指定レーンのいずれかのキーが押下されているか判定する
     */
    private boolean isLanePressed(LaneState state, BMSPlayerInputProcessor input) {
        for (int key : state.laneassign) {
            if (input.getKeyState(key)) {
                return true;
            }
        }
        return false;
    }

    /**
     * HLNの明示終端音を終点時刻で1回だけ再生する
     */
    private void playHlnEndSoundIfNeeded(LaneState state, LongNote endNote) {
        if (endNote == null || !state.hlnEndSoundPlayed.add(endNote)) {
            return;
        }
        if (endNote.getWav() >= 0 || endNote.getLayeredNotes().length > 0) {
            keysound.play(endNote, getKeyVolume(), 0);
        }
    }

    /**
     * HLNのBODY管理状態をクリアする
     */
    private void clearHlnBodyState(LaneState state) {
        state.hlnStart = null;
        state.passing = null;
        state.mpassingcount = 0;
        state.hlnLastGaugeTime = Long.MIN_VALUE;
        state.hlnSoundPlayed = false;
        state.hlnReactive = false;
    }

    /**
     * HLNの保持ゲージを指定時刻まで精算する
     */
    private void advanceHlnGauge(LaneState state, long targetTime, boolean held) {
        if (state.hlnStart == null || state.hlnLastGaugeTime == Long.MIN_VALUE) {
            return;
        }
        final long endTime = state.hlnStart.getPair().getMicroTime();
        final long toTime = Math.min(targetTime, endTime);
        if (toTime <= state.hlnLastGaugeTime) {
            return;
        }
        final long elapsed = toTime - state.hlnLastGaugeTime;
        state.hlnLastGaugeTime = toTime;
        if (held) {
            state.mpassingcount += elapsed;
            while (state.mpassingcount > hcnmduration) {
                main.getGauge().update(1, 0.5f);
                state.mpassingcount -= hcnmduration;
            }
        } else {
            state.mpassingcount -= elapsed;
            while (state.mpassingcount < -hcnmduration) {
                main.getGauge().update(3, 0.5f);
                state.mpassingcount += hcnmduration;
            }
        }
    }

    /**
     * 指定時刻までのHLNイベント（早離し猶予期限、始点見逃しPOOR、終点到達、保持ゲージ、終端音）を時系列順に精算する
     */
    private void settleHlnUpTo(LaneState state, long targetTime, boolean playOnMiss) {
        final long releasemargin = state.sckey >= 0 ? sreleasemargin : nreleasemargin;
        final long[][] mjudge = state.sckey >= 0 ? smjudge : nmjudge;
        final boolean held = state.hlnHeld || autoplay;

        if (state.hlnStart != null) {
            final LongNote start = state.hlnStart;
            final LongNote end = start.getPair();
            final long endTime = end.getMicroTime();

            if (state.processing == end && state.releasetime != Long.MIN_VALUE) {
                final long marginTime = state.releasetime + releasemargin;
                if (marginTime <= endTime && marginTime <= targetTime) {
                    advanceHlnGauge(state, marginTime, held);
                    if (state.hlnSoundPlayed) {
                        keysound.setVolume(start, 0.0f);
                    }
                    this.updateMicro(state, start, marginTime, state.lnendJudge, endTime - state.releasetime, true);
                    state.processing = null;
                    state.releasetime = Long.MIN_VALUE;
                    state.lnendJudge = Integer.MIN_VALUE;
                }
            }

            if (endTime <= targetTime) {
                advanceHlnGauge(state, endTime, held);
                if (state.processing == end) {
                    this.updateMicro(state, start, endTime, state.lnstartJudge, state.lnstartDuration, true);
                    state.processing = null;
                    state.releasetime = Long.MIN_VALUE;
                    state.lnendJudge = Integer.MIN_VALUE;
                }
                playHlnEndSoundIfNeeded(state, end);
                clearHlnBodyState(state);
                if (!hasActiveHellLongNoteBody() && score.getPassnotes() == score.getNotes() && score.getPassnotes() == combo) {
                    main.timer.switchTimer(TIMER_FULLCOMBO_1P, true);
                }
            } else {
                advanceHlnGauge(state, targetTime, held);
            }
        }

        for (Note note : state.lanemodel.getNotes()) {
            if (note.getMicroTime() > targetTime) {
                break;
            }
            if (!(note instanceof LongNote) || note.isDummy()) {
                continue;
            }
            final LongNote ln = (LongNote) note;
            if (!isHellLongNote(ln)) {
                continue;
            }
            if (ln.isEnd()) {
                playHlnEndSoundIfNeeded(state, ln);
                continue;
            }
            if (ln.getState() != 0 || state.hlnStart == ln || state.processing == ln.getPair()) {
                continue;
            }
            if (!autoplay && ln.getMicroTime() < targetTime + mjudge[3][0]) {
                final long missTime = ln.getMicroTime() - mjudge[3][0] + 1;
                final int mjud = (int) (ln.getMicroTime() - missTime);
                this.updateMicro(state, ln, missTime, 4, mjud, true);
                boolean soundPlayed = false;
                if (playOnMiss) {
                    keysound.play(ln, getKeyVolume(), 0);
                    soundPlayed = true;
                }
                final LongNote end = ln.getPair();
                final long endTime = end.getMicroTime();
                if (missTime < endTime) {
                    state.hlnStart = ln;
                    state.passing = ln;
                    state.mpassingcount = 0;
                    state.hlnLastGaugeTime = missTime;
                    state.hlnSoundPlayed = soundPlayed;
                    state.hlnReactive = true;
                    if (soundPlayed && !held) {
                        keysound.setVolume(ln, 0.0f);
                    }
                    if (endTime <= targetTime) {
                        advanceHlnGauge(state, endTime, held);
                        playHlnEndSoundIfNeeded(state, end);
                        clearHlnBodyState(state);
                    } else {
                        advanceHlnGauge(state, targetTime, held);
                    }
                }
            }
        }
    }

    /**
     * 指定時刻がいずれかのフリーゾーン区間内にあるかを判定する
     */
    private boolean isInsideFreeZone(LaneState state, long time) {
        for (FreeZoneNote fz : state.lanemodel.getFreeZones()) {
            if (fz.getMicroTime() <= time && time <= fz.getPair().getMicroTime()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 指定時刻のスクラッチ入力により対象フリーゾーンをスクラッチ済みにする
     */
    private void markFreeZoneScratchedAtTime(LaneState state, long time) {
        for (FreeZoneNote fz : state.lanemodel.getFreeZones()) {
            if (fz.getPair().getState() == 0 && fz.getMicroTime() <= time && time <= fz.getPair().getMicroTime()) {
                fz.setScratched(true);
            }
        }
    }

    /**
     * フリーゾーン内包ノーツが判定された際に対象フリーゾーンをスクラッチ済みにする
     */
    private void markFreeZoneScratchedForNote(LaneState state, Note note) {
        final Note target = (note instanceof LongNote ln && ln.isEnd()) ? ln.getPair() : note;
        for (FreeZoneNote fz : state.lanemodel.getFreeZones()) {
            if (fz.getPair().getState() == 0) {
                for (Note inner : fz.getInnerNotes()) {
                    if (inner == target) {
                        fz.setScratched(true);
                        return;
                    }
                }
            }
        }
    }

    /**
     * 終端に到達したフリーゾーンの最終判定を確定させる
     */
    private void settleFreeZones(LaneState state, long mtime, long now) {
        for (FreeZoneNote fz : state.lanemodel.getFreeZones()) {
            final FreeZoneNote fzEnd = fz.getPair();
            if (fzEnd.getState() != 0) {
                continue;
            }
            if (autoplay && mtime >= fz.getMicroTime()) {
                fz.setScratched(true);
                if (fz.getInnerNotes().length == 0 && mtime <= fzEnd.getMicroTime() && state.laneassign.length > 0) {
                    auto_presstime[state.laneassign[0]] = now;
                }
            }
            if (mtime < fzEnd.getMicroTime()) {
                continue;
            }
            final Note[] inners = fz.getInnerNotes();
            boolean allSettled = true;
            for (Note inner : inners) {
                if (inner instanceof LongNote ln) {
                    if (ln.getState() == 0 || state.processing == ln.getPair()
                            || (isChargeOrHellChargeNote(ln) && ln.getPair().getState() == 0)) {
                        allSettled = false;
                        break;
                    }
                } else if (inner.getState() == 0) {
                    allSettled = false;
                    break;
                }
            }
            if (!allSettled) {
                continue;
            }
            final int finalJudge;
            if (inners.length > 0) {
                boolean allPg = true;
                boolean allGrOrBetter = true;
                for (Note inner : inners) {
                    final int st = inner.getState();
                    if (st != 1) {
                        allPg = false;
                    }
                    if (st != 1 && st != 2) {
                        allGrOrBetter = false;
                    }
                    if (inner instanceof LongNote ln && isChargeOrHellChargeNote(ln)) {
                        final int endSt = ln.getPair().getState();
                        if (endSt != 1) {
                            allPg = false;
                        }
                        if (endSt != 1 && endSt != 2) {
                            allGrOrBetter = false;
                        }
                    }
                }
                if (allPg) {
                    finalJudge = 0;
                } else if (allGrOrBetter) {
                    finalJudge = 1;
                } else if (fz.isScratched()) {
                    finalJudge = 2;
                } else {
                    finalJudge = 4;
                }
            } else {
                if (autoplay) {
                    finalJudge = 0;
                } else if (fz.isScratched()) {
                    finalJudge = 2;
                } else {
                    finalJudge = 4;
                }
            }
            fz.setState(finalJudge + 1);
            this.updateMicro(state, fzEnd, mtime, finalJudge, 0, true);
        }
    }

    private float getKeyVolume() {
        float adjusted = main.getAdjustedVolume();
        if (adjusted < 0) {
            return main.resource.getConfig().getAudioConfig().getKeyvolume();
        }
        return adjusted;
    }

    public void update(final long mtime) {
        final MainController mc = main.main;
        final TimerManager timer = main.timer;
        final BMSPlayerInputProcessor input = mc.getInputProcessor();
        final Config config = main.resource.getConfig();
        final long now = timer.getNowTime();
        // 通過系の判定
        for (LaneState state : states) {
            state.lanemodel.mark((int) ((prevmtime + mjudgestart - 100000) / 1000));
            boolean next_inclease = false;
            boolean pressed = false;
            for (int key : state.laneassign) {
                if (input.getKeyState(key)) {
                    pressed = true;
                    break;
                }
            }
            for (Note note = state.lanemodel.getNote(); note != null && note.getMicroTime() <= mtime; note = state.lanemodel.getNote()) {
                if (note.getMicroTime() <= prevmtime || note.isDummy()) {
                    continue;
                }
                if (note instanceof LongNote) {
                    // HCN判定
                    final LongNote lnote = (LongNote) note;
                    if ((lnote.getType() == LongNote.TYPE_UNDEFINED && lntype == BMSModel.LNTYPE_HELLCHARGENOTE)
                            || lnote.getType() == LongNote.TYPE_HELLCHARGENOTE) {
                        if (lnote.isEnd()) {
                            state.passing = null;
                            state.mpassingcount = 0;
                        } else {
                            state.passing = lnote;
                        }
                    }
                } else if (note instanceof PotionNote && pressed) {
                    final PotionNote pnote = (PotionNote) note;
                    // ポーションノーツ判定
                    main.getGauge().addValue((float) pnote.getRecovery());
                } else if (note instanceof MineNote && pressed) {
                    final MineNote mnote = (MineNote) note;
                    // 地雷ノート判定
                    main.getGauge().addValue((float) -mnote.getDamage());
//                  System.out.println("Mine Damage : " + (float) mnote.getDamage());
                    keysound.play(note, getKeyVolume(), 0);
                }

                if (autoplay) {
                    // ここにオートプレイ処理を入れる
                    if (note instanceof NormalNote && note.getState() == 0) {
                        auto_presstime[state.laneassign[0]] = now;
                        keysound.play(note, getKeyVolume(), 0);
                        if (((NormalNote) note).isFreeZoneInner()) {
                            note.setState(1);
                            note.setMicroPlayTime(0);
                            markFreeZoneScratchedForNote(state, (NormalNote) note);
                        } else {
                            this.updateMicro(state, note, mtime, 0, 0, true);
                        }
                    }
                    if (note instanceof LongNote) {
                        final LongNote ln = (LongNote) note;
                        if (isHellLongNote(ln)) {
                            if (!ln.isEnd() && ln.getState() == 0 && state.hlnStart != ln) {
                                settleHlnUpTo(state, ln.getMicroTime(), false);
                                auto_presstime[state.laneassign[0]] = now;
                                keysound.play(note, getKeyVolume(), 0);
                                state.lnstartJudge = 0;
                                state.lnstartDuration = 0;
                                state.processing = ln.getPair();
                                state.releasetime = Long.MIN_VALUE;
                                state.lnendJudge = Integer.MIN_VALUE;
                                state.hlnStart = ln;
                                state.passing = ln;
                                state.mpassingcount = 0;
                                state.hlnLastGaugeTime = ln.getMicroTime();
                                state.hlnSoundPlayed = true;
                                state.hlnReactive = false;
                                state.hlnHeld = true;
                                state.lnstartJudge = 0;
                                state.lnstartDuration = 0;
                                this.judge[state.player][state.offset] = 8;
                                if (ln.isFreeZoneInner()) {
                                    markFreeZoneScratchedForNote(state, ln);
                                }
                            } else if (ln.isEnd()) {
                                settleHlnUpTo(state, ln.getMicroTime(), false);
                            }
                            continue;
                        }
                        if (!ln.isEnd() && ln.getState() == 0 && state.processing == null) {
                            auto_presstime[state.laneassign[0]] = now;
                            keysound.play(note, getKeyVolume(), 0);
                            if ((lntype == BMSModel.LNTYPE_LONGNOTE && ln.getType() == LongNote.TYPE_UNDEFINED)
                                    || ln.getType() == LongNote.TYPE_LONGNOTE || isHellLongNote(ln)) {
                                state.mpassingcount = 0;
                                //LN時のレーザー色変更処理
                                state.lnstartJudge = 0;
                                state.lnstartDuration = 0;
                                this.judge[state.player][state.offset] = 8;
                                if (ln.isFreeZoneInner()) {
                                    markFreeZoneScratchedForNote(state, ln);
                                }
                            } else {
                                this.updateMicro(state, ln, mtime, 0, 0, true);
                            }
                            state.processing = ln.getPair();
                        }
                        if (ln.isEnd() && ln.getState() == 0) {
                            if ((lntype != BMSModel.LNTYPE_LONGNOTE && ln.getType() == LongNote.TYPE_UNDEFINED)
                                    || ln.getType() == LongNote.TYPE_CHARGENOTE
                                    || ln.getType() == LongNote.TYPE_HELLCHARGENOTE) {
                                if (state.sckey >= 0 && state.laneassign.length >= 2) {
                                    auto_presstime[state.laneassign[0]] = Long.MIN_VALUE;
                                    auto_presstime[state.laneassign[1]] = now;
                                }
                                this.updateMicro(state, ln, mtime, 0, 0, true);
                                keysound.play(state.processing, getKeyVolume(), 0);
                                state.processing = null;
                            }
                        }
                    }
                }
            }
            // HCNゲージ増減判定
            if (state.passing != null
                    && (pressed || (state.passing.getPair().getState() > 0 && state.passing.getPair().getState() <= 3)
                            || autoplay)) {
                next_inclease = true;
            }

            if (autoplay) {
                for (int key : state.laneassign) {
                    if (auto_presstime[key] != Long.MIN_VALUE && now - auto_presstime[key] > auto_minduration
                            && state.processing == null && state.hlnStart == null) {
                        auto_presstime[key] = Long.MIN_VALUE;
                    }
                }
            }
            state.inclease = next_inclease;
        }

        for (LaneState state : states) {
            if (state.passing != null && isHellLongNote(state.passing)) {
                continue;
            }
            if (state.passing == null || state.passing.getState() == 0) {
                timer.setTimerOff(state.timerActive);
                timer.setTimerOff(state.timerDamage);
                continue;
            }

            if (state.inclease) {
                state.mpassingcount += (mtime - prevmtime);
                if (state.mpassingcount > hcnmduration) {
                    main.getGauge().update(1, 0.5f);
//                   System.out.println("HCN : Gauge increase");
                    state.mpassingcount -= hcnmduration;
                }
                timer.switchTimer(state.timerActive, true);
                timer.setTimerOff(state.timerDamage);
                if(state.passing.getPair().getState() > 3) {
                    keysound.setVolume(state.passing, getKeyVolume());
                }
            } else {
                state.mpassingcount -= (mtime - prevmtime);
                if (state.mpassingcount < -hcnmduration) {
                    main.getGauge().update(3, 0.5f);
//                   System.out.println("HCN : Gauge decrease");
                    state.mpassingcount += hcnmduration;
                }
                timer.setTimerOff(state.timerActive);
                timer.switchTimer(state.timerDamage, true);
                if(state.passing.getPair().getState() > 3) {
                    keysound.setVolume(state.passing, 0.0f);
                }
            }
        }
        prevmtime = mtime;
        final boolean playOnMiss = main.resource.getConfig().getAudioConfig().isPlayKeySoundOnMiss();


        for (int key = 0; key < keyassign.length; key++) {
            final int lane = keyassign[key];
            if (lane == -1) {
                continue;
            }
            long pmtime = input.getKeyChangedTime(key);
            if (pmtime != Long.MIN_VALUE && pmtime > mtime) {
                continue;
            }
            if (pmtime != Long.MIN_VALUE && lastBranchEvalTime != Long.MIN_VALUE && pmtime < lastBranchEvalTime
                    && main.resource.getPlayMode().mode == BMSPlayerMode.Mode.PLAY) {
                pmtime = lastBranchEvalTime;
            }
            if (pmtime == Long.MIN_VALUE) {
                continue;
            }
            final LaneState state = states[lane];
            settleHlnUpTo(state, pmtime, playOnMiss);
            state.lanemodel.reset();
            final int sc = state.sckey;
            if (input.getKeyState(key)) {
                // キーが押されたときの処理
                markFreeZoneScratchedAtTime(state, pmtime);
                final boolean inFreeZone = isInsideFreeZone(state, pmtime);
                state.hlnHeld = true;
                if (state.processing != null) {
                    if (isHellLongNote(state.processing)) {
                        if (state.hlnSoundPlayed) {
                            keysound.setVolume(state.hlnStart, getKeyVolume());
                        }
                        if (sc >= 0) {
                            sckey[sc] = key;
                        }
                        state.releasetime = Long.MIN_VALUE;
                        state.lnendJudge = Integer.MIN_VALUE;
                    } else if (((lntype != BMSModel.LNTYPE_LONGNOTE && state.processing.getType() == LongNote.TYPE_UNDEFINED)
                            || state.processing.getType() == LongNote.TYPE_CHARGENOTE
                            || state.processing.getType() == LongNote.TYPE_HELLCHARGENOTE)) {
                        if(sc >= 0 && key != sckey[sc]) {
                            // BSS終端処理
                            final long[][] mjudge = scnendmjudge;
                            final long dmtime = state.processing.getMicroTime() - pmtime;
                            int j = 0;
                            for (; j < mjudge.length && !(dmtime >= mjudge[j][0] && dmtime <= mjudge[j][1]); j++)
                                ;

                            keysound.play(state.processing, getKeyVolume(), 0);
                            this.updateMicro(state, state.processing, mtime, j, dmtime, true);
//                           System.out.println("BSS終端判定 - Time : " + ptime + " Judge : " + j + " LN : " + processing[lane].hashCode());
                            state.processing = null;
                            state.releasetime = Long.MIN_VALUE;
                            state.lnendJudge = Integer.MIN_VALUE;
                            sckey[sc] = 0;                          
                        } else {
                            // 押し直し処理
                            state.releasetime = Long.MIN_VALUE;
                        }
                    } else {
                        // 押し直し処理
                        state.releasetime = Long.MIN_VALUE;
                    }
                } else {
                    final long[][] mjudge = sc >= 0 ? smjudge : nmjudge;
                    if (state.hlnStart != null) {
                        state.hlnReactive = true;
                        if (state.hlnSoundPlayed) {
                            keysound.setVolume(state.hlnStart, getKeyVolume());
                        }
                        if (sc >= 0) {
                            sckey[sc] = key;
                        }
                    }
                    // 対象ノーツの抽出
                    state.lanemodel.reset();
                    Note tnote = null;
                    int judge = 0;
                    multiBadCollector.clear();
                    multiBadCollector.setJudge(mjudge);
                    for (Note judgenote = state.lanemodel.getNote(); judgenote != null; judgenote = state.lanemodel.getNote()) {
                        final long dmtime = judgenote.getMicroTime() - pmtime;
                        if (dmtime >= mjudgeend) {
                            break;
                        }
                        if (dmtime < mjudgestart) {
                            continue;
                        }
                        if (judgenote.isDummy() || judgenote instanceof MineNote || judgenote instanceof PotionNote || (judgenote instanceof LongNote
                                && ((LongNote) judgenote).isEnd()) || judgenote == state.hlnStart) {
                            continue;
                        }
                        if (judgenote.getState() == 0) {
                            multiBadCollector.add(judgenote, dmtime);
                        }
                        if (tnote == null || tnote.getState() != 0 || algorithm.compare(tnote, judgenote, pmtime, mjudge)) {
                            if (!(miss == MissCondition.ONE && (judgenote.getState() != 0
                                    || (judgenote.getState() == 0 && judgenote.getPlayTime() != 0
                                            && (dmtime > mjudge[2][1] || dmtime < mjudge[2][0]))))) {
                                if (judgenote.getState() != 0) {
                                    judge = (dmtime >= mjudge[4][0] && dmtime <= mjudge[4][1]) ? 5 : 6;
                                } else if (judgenote instanceof LongNote && dmtime < mjudge[2][0]) {
                                    //LR2oraja: Remove late bad for LN
                                    judge = 6;
                                } else {
                                    for (judge = 0; judge < mjudge.length
                                            && !(dmtime >= mjudge[judge][0] && dmtime <= mjudge[judge][1]); judge++) {
                                    }
                                    judge = (judge >= 4 ? judge + 1 : judge);
                                }
                                
                                if (judge >= 4 && (inFreeZone || judgenote.isFreeZoneInner())) {
                                    judge = 6;
                                }
                                if(judge < 6) {
                                    if (judge < 6 && (judge < 4 || tnote == null
                                            || Math.abs(tnote.getMicroTime() - pmtime) > Math.abs(judgenote.getMicroTime() - pmtime))) {
                                        tnote = judgenote;
                                    }                                   
                                } else {
                                    tnote = null;
                                }
                            }
                        }
                    }
                    multiBadCollector.filter(tnote);
                    if (tnote != null && state.passing != null && judge >= 4) {
                        tnote = null;
                    }

                    if (tnote != null) {
                        // process multiBad
                        for (int i=multiBadCollector.arrayStart; i<multiBadCollector.size; i++) {
                            final Note mbNote = multiBadCollector.noteList[i];
                            if (mbNote instanceof LongNote && isHellLongNote((LongNote) mbNote)) {
                                final LongNote mbLn = (LongNote) mbNote;
                                if (pmtime >= mbLn.getPair().getMicroTime()) {
                                    this.updateMicroMultiBad(state, mbLn, mtime, 3, multiBadCollector.timeList[i], true);
                                }
                                continue;
                            }
                            if (mbNote instanceof NormalNote nn && nn.isFreeZoneInner()) {
                                nn.setState(4);
                                nn.setMicroPlayTime(multiBadCollector.timeList[i]);
                                markFreeZoneScratchedForNote(state, nn);
                                continue;
                            }
                            this.updateMicroMultiBad(state, mbNote, mtime, 3, multiBadCollector.timeList[i], judgeVanish[3]);
                        }

                        // TODO この時点で空POOR処理を分岐させるべきか
                        if (tnote instanceof LongNote) {
                            // ロングノート処理
                            final LongNote ln = (LongNote) tnote;
                            final long dmtime = tnote.getMicroTime() - pmtime;
                            keysound.play(tnote, getKeyVolume(), 0);
                            if ((lntype == BMSModel.LNTYPE_LONGNOTE && ln.getType() == LongNote.TYPE_UNDEFINED)
                                    || ln.getType() == LongNote.TYPE_LONGNOTE || isHellLongNote(ln)) {
                                // HLN・LN処理
                                if (isHellLongNote(ln)) {
                                    if (judge <= 3) {
                                        if (pmtime >= ln.getPair().getMicroTime()) {
                                            this.updateMicro(state, ln, mtime, judge, dmtime, true);
                                        } else {
                                            state.lnstartJudge = judge;
                                            state.lnstartDuration = dmtime;
                                            state.processing = ln.getPair();
                                            state.releasetime = Long.MIN_VALUE;
                                            state.lnendJudge = Integer.MIN_VALUE;
                                            state.hlnStart = ln;
                                            state.passing = ln;
                                            state.mpassingcount = 0;
                                            state.hlnLastGaugeTime = Math.max(ln.getMicroTime(), pmtime);
                                            state.hlnSoundPlayed = true;
                                            state.hlnReactive = judge >= 3;
                                            state.hlnHeld = true;
                                            if (sc >= 0) {
                                                sckey[sc] = key;
                                            }
                                            this.judge[state.player][state.offset] = 8;
                                            if (ln.isFreeZoneInner()) {
                                                markFreeZoneScratchedForNote(state, ln);
                                            }
                                        }
                                    } else {
                                        this.updateMicro(state, tnote, mtime, judge, dmtime, false);
                                    }
                                } else
                                if(judgeVanish[judge]) {
                                    state.lnstartJudge = judge;
                                    state.lnstartDuration = dmtime;
                                    state.processing = ln.getPair();
                                    state.releasetime = Long.MIN_VALUE;
                                    state.lnendJudge = Integer.MIN_VALUE;
                                    if (sc >= 0) {
                                        sckey[sc] = key;
                                    }
                                    //LN時のレーザー色変更処理
                                    this.judge[state.player][state.offset] = 8;
                                    if (ln.isFreeZoneInner()) {
                                        markFreeZoneScratchedForNote(state, ln);
                                    }                                 
                                } else {
                                    this.updateMicro(state, tnote, mtime, judge, dmtime, false);
                                }
                            } else {
                            // unused
                                // CN,HCN処理
                                if (judgeVanish[judge]) {
                                    state.processing = ln.getPair();
                                    state.releasetime = Long.MIN_VALUE;
                                    state.lnendJudge = Integer.MIN_VALUE;
                                    if (sc >= 0) {
                                        sckey[sc] = key;
                                    }
                                }
                                this.updateMicro(state, ln, mtime, judge, dmtime, judgeVanish[judge]);
                            }
                        } else {
                            keysound.play(tnote, getKeyVolume(), 0);
                            // 通常ノート処理
                            final long dmtime = tnote.getMicroTime() - pmtime;
                            if (tnote instanceof NormalNote nn && nn.isFreeZoneInner()) {
                                nn.setState(judge + 1);
                                nn.setMicroPlayTime(dmtime);
                                if (judge <= 3) {
                                    markFreeZoneScratchedForNote(state, nn);
                                }
                            } else {
                                this.updateMicro(state, tnote, mtime, judge, dmtime, judgeVanish[judge]);
                            }
                        }
                    } else {
                        // 空POOR判定がないときのレーザー色変更処理
                        this.judge[state.player][state.offset] = 0;

                        // 空POOR判定がないときのキー音処理
                        final Note[] notes = state.lanemodel.getNotes();
                        Note n = notes.length > 0 ? notes[0] : null;
                        for (Note note : state.lanemodel.getHiddens()) {
                            if (note.getMicroTime() >= pmtime) {
                                break;
                            }
                            n = note;
                        }

                        for (Note note : notes) {
                            if (note.getMicroTime() >= pmtime) {
                                break;
                            }
                            if ((n == null || n.getMicroTime() <= note.getMicroTime())
                                    && !note.isDummy() && !(note instanceof LongNote && note.getState() != 0)) {
                                n = note;
                            }
                        }

                        if (!inFreeZone && n != null && !n.isDummy() && state.passing == null && !(n instanceof MineNote || n instanceof PotionNote)) {
                            keysound.play(n, getKeyVolume(), 0);
                        }
                    }
                }
                main.getKeyinput().inputKeyOn(lane);
            } else {
                // キーが離されたときの処理
                state.hlnHeld = isLanePressed(state, input);
                if (state.processing == null && state.hlnStart != null) {
                    if (sc >= 0 && key == sckey[sc]) {
                        sckey[sc] = 0;
                    }
                    if (!state.hlnHeld) {
                        state.hlnReactive = true;
                        if (state.hlnSoundPlayed) {
                            keysound.setVolume(state.hlnStart, 0.0f);
                        }
                    }
                }
                if (state.processing != null) {
                    final long[][] mjudge = sc >= 0 ? scnendmjudge : cnendmjudge;
                    long dmtime = state.processing.getMicroTime() - pmtime;
                    int judge = 0;
                    for (; judge < mjudge.length && !(dmtime >= mjudge[judge][0] && dmtime <= mjudge[judge][1]); judge++)
                        ;

                    if ((lntype != BMSModel.LNTYPE_LONGNOTE
                            && state.processing.getType() == LongNote.TYPE_UNDEFINED)
                            || state.processing.getType() == LongNote.TYPE_CHARGENOTE
                            || state.processing.getType() == LongNote.TYPE_HELLCHARGENOTE
                            || isHellLongNote(state.processing)) {
                        // CN, HCN離し処理
                        if (isHellLongNote(state.processing)) {
                            boolean release = true;
                            if (sc >= 0) {
                                if (key != sckey[sc]) {
                                    release = false;
                                } else {
                                    sckey[sc] = 0;
                                }
                            } else if (state.hlnHeld) {
                                release = false;
                            }
                            if (!state.hlnHeld) {
                                state.hlnReactive = true;
                                if (state.hlnSoundPlayed) {
                                    keysound.setVolume(state.hlnStart, 0.0f);
                                }
                            }
                            if (release) {
                                final long endDmtime = dmtime;
                                judge = Math.max(judge, state.lnstartJudge);
                                if (Math.abs(state.lnstartDuration) > Math.abs(dmtime)) {
                                    dmtime = state.lnstartDuration;
                                }
                                if (judge >= 3 && endDmtime > 0) {
                                    final long releasemargin = sc >= 0 ? sreleasemargin : nreleasemargin;
                                    if (releasemargin > 0) {
                                        state.releasetime = pmtime;
                                        state.lnendJudge = 3;
                                    } else {
                                        if (state.hlnSoundPlayed) {
                                            keysound.setVolume(state.processing.getPair(), 0.0f);
                                        }
                                        this.updateMicro(state, state.processing.getPair(), mtime, 3, endDmtime, true);
                                        state.processing = null;
                                        state.releasetime = Long.MIN_VALUE;
                                        state.lnendJudge = Integer.MIN_VALUE;
                                    }
                                } else {
                                    this.updateMicro(state, state.processing.getPair(), mtime, Math.min(judge, 3), dmtime, true);
                                    state.processing = null;
                                    state.releasetime = Long.MIN_VALUE;
                                    state.lnendJudge = Integer.MIN_VALUE;
                                }
                            }
                        } else {
                        boolean release = true;
                        if (sc >= 0) {
                            if (judge != 4 || key != sckey[sc]) {
                                release = false;
                            } else {
//                               System.out.println("BSS途中離し判定 - Time : " + ptime + " Judge : " + j + " LN : " + processing[lane]);
                                sckey[sc] = 0;
                            }
                        }
                        if (release) {
                            if(judge >= 3 && dmtime > 0) {
                                state.releasetime = mtime;
                                state.lnendJudge = judge;
                            } else {
                                this.updateMicro(state, state.processing, mtime, judge, dmtime, true);
                                keysound.play(state.processing, getKeyVolume(), 0);
                                state.processing = null;
                                state.releasetime = Long.MIN_VALUE;
                                state.lnendJudge = Integer.MIN_VALUE;
                            }
                        }
                    }
                    if (false) {
                        if (false) {
                                keysound.play(state.processing, getKeyVolume(), 0);
                                state.processing = null;
                                state.releasetime = Long.MIN_VALUE;
                                state.lnendJudge = Integer.MIN_VALUE;
                            }
                        }
                    } else {
                        boolean release = true;
                        if (sc >= 0) {
                            if (key != sckey[sc]) {
                                release = false;
                            } else {
//                               System.out.println("BSS途中離し判定 - Time : " + ptime + " Judge : " + j + " LN : " + processing[lane]);
                                sckey[sc] = 0;
                            }
                        }
                        if (release) {
                            // LN離し処理
                            judge = Math.max(judge, state.lnstartJudge);
                            if (Math.abs(state.lnstartDuration) > Math.abs(dmtime)) {
                                dmtime = state.lnstartDuration;
                            }
                            if(judge >= 3 && dmtime > 0) {
                                state.releasetime = mtime;                              
                                state.lnendJudge = 3;
                            } else {
                                this.updateMicro(state, state.processing.getPair(), mtime, Math.min(judge, 3), dmtime, true);
                                keysound.play(state.processing, getKeyVolume(), 0);
                                state.processing = null;
                                state.releasetime = Long.MIN_VALUE;
                                state.lnendJudge = Integer.MIN_VALUE;
//                              System.out.println("LN途中離し判定 - Time : " + ptime + " Judge : " + j + " LN : " + processing[lane]);                                   
                            }
                        }
                    }
                }
            }
            input.resetKeyChangedTime(key);
        }

        for (LaneState state : states) {
            final long[][] mjudge = state.sckey >= 0 ? smjudge : nmjudge;
            final long releasemargin = state.sckey >= 0 ? sreleasemargin : nreleasemargin;

            state.hlnHeld = isLanePressed(state, input);
            settleHlnUpTo(state, mtime, playOnMiss);
            // LN終端判定
            if (state.processing != null) {
                if (isHellLongNote(state.processing)) {
                    // HLNはsettleHlnUpToで精算済み
                } else if((lntype == BMSModel.LNTYPE_LONGNOTE && state.processing.getType() == LongNote.TYPE_UNDEFINED)
                    || state.processing.getType() == LongNote.TYPE_LONGNOTE) {
                    if(state.releasetime != Long.MIN_VALUE && state.releasetime + releasemargin <= mtime) {
                        keysound.setVolume(state.processing.getPair(), 0.0f);
                        this.updateMicro(state, state.processing.getPair(), mtime, state.lnendJudge, state.processing.getMicroTime() - state.releasetime, true);
                        keysound.play(state.processing, getKeyVolume(), 0);
                        state.processing = null;
                        state.releasetime = Long.MIN_VALUE;
                        state.lnendJudge = Integer.MIN_VALUE;
                    } else if(state.processing.getMicroTime() < mtime) {
                        this.updateMicro(state, state.processing.getPair(), mtime, state.lnstartJudge, state.lnstartDuration, true);
                        keysound.play(state.processing, getKeyVolume(), 0);
                        state.processing = null;                        
                        state.releasetime = Long.MIN_VALUE;
                        state.lnendJudge = Integer.MIN_VALUE;
                    }
                } else {
                    if(state.releasetime != Long.MIN_VALUE && state.releasetime + releasemargin <= mtime) {
                        if (state.lnendJudge >= 3) {
                            keysound.setVolume(state.processing.getPair(), 0.0f);
                        }
                        this.updateMicro(state, state.processing, mtime, state.lnendJudge, state.processing.getMicroTime() - state.releasetime, true);
                        keysound.play(state.processing, getKeyVolume(), 0);
                        state.processing = null;
                        state.releasetime = Long.MIN_VALUE;
                        state.lnendJudge = Integer.MIN_VALUE;
                    }
                }
            }
            // 見逃しPOOR判定
    
            state.lanemodel.reset();
            for (Note note = state.lanemodel.getNote(); note != null
                    && note.getMicroTime() < mtime + mjudge[3][0]; note = state.lanemodel.getNote()) {
                final int mjud = (int) (note.getMicroTime() - mtime);
                if (note.isDummy()) {
                    continue;
                }
                if (note instanceof NormalNote && note.getState() == 0) {
                    if (((NormalNote) note).isFreeZoneInner()) {
                        note.setState(5);
                        note.setMicroPlayTime(mjud);
                    } else {
                        this.updateMicro(state, note, mtime, 4, mjud, true);
                    }
                    if (playOnMiss) {
                        keysound.play(note, getKeyVolume(), 0);
                    }
                } else if (note instanceof LongNote) {
                    final LongNote ln = (LongNote) note;
                    if (isHellLongNote(ln)) {
                        continue;
                    }
                    if (!ln.isEnd() && ln.getState() == 0) {
                        if ((lntype != BMSModel.LNTYPE_LONGNOTE && ln.getType() == LongNote.TYPE_UNDEFINED)
                                || ln.getType() == LongNote.TYPE_CHARGENOTE
                                || ln.getType() == LongNote.TYPE_HELLCHARGENOTE) {
                            // System.out.println("CN start poor");
                            this.updateMicro(state, note, mtime, 4, mjud, true);
                            this.updateMicro(state, ((LongNote) note).getPair(), mtime, 4, mjud, true);
                            if (playOnMiss) {
                                keysound.play(note, getKeyVolume(), 0);
                            }
                        }
                        if (((lntype == BMSModel.LNTYPE_LONGNOTE && ln.getType() == LongNote.TYPE_UNDEFINED)
                                || ln.getType() == LongNote.TYPE_LONGNOTE) && state.processing != ln.getPair()) {
                            // System.out.println("LN start poor");
                            this.updateMicro(state, note, mtime, 4, mjud, true);
                            if (playOnMiss) {
                                keysound.play(note, getKeyVolume(), 0);
                            }
                        }

                    }
                    if (((lntype != BMSModel.LNTYPE_LONGNOTE && ln.getType() == LongNote.TYPE_UNDEFINED)
                            || ln.getType() == LongNote.TYPE_CHARGENOTE || ln.getType() == LongNote.TYPE_HELLCHARGENOTE)
                            && ((LongNote) note).isEnd() && ((LongNote) note).getState() == 0) {
                        // System.out.println("CN end poor");
                        this.updateMicro(state, ((LongNote) note), mtime, 4, mjud, true);
                        if (playOnMiss) {
                            keysound.play(((LongNote) note), getKeyVolume(), 0);
                        }
                        state.processing = null;
                        state.releasetime = Long.MIN_VALUE;
                        state.lnendJudge = Integer.MIN_VALUE;
                        if (state.sckey >= 0) {
                            sckey[state.sckey] = 0;
                        }
                    }
                }
            }
            settleFreeZones(state, mtime, now);
            // LN処理タイマー
            if (state.hlnStart != null) {
                state.inclease = state.hlnHeld || autoplay;
                if (state.inclease) {
                    timer.switchTimer(state.timerActive, true);
                    timer.setTimerOff(state.timerDamage);
                } else {
                    timer.setTimerOff(state.timerActive);
                    timer.switchTimer(state.timerDamage, true);
                }
            } else if (state.passing == null) {
                timer.setTimerOff(state.timerActive);
                timer.setTimerOff(state.timerDamage);
            }
            // TODO processing値の変化のときのみ実行したい
            // TODO HCNは別タイマーにするかも
            timer.switchTimer(SkinPropertyMapper.holdTimerId(state.player, state.offset),
                    state.processing != null || (state.passing != null && state.inclease));
        }
    }

    private final int[] JUDGE_TIMER = { TIMER_JUDGE_1P, TIMER_JUDGE_2P, TIMER_JUDGE_3P };
    private final int[] COMBO_TIMER = { TIMER_COMBO_1P, TIMER_COMBO_2P, TIMER_COMBO_3P };

    private void updateMicro(LaneState state, Note n, long mtime, int judge, long mfast, boolean judgeVanish) {
        updateMicro(state, n, mtime, judge, mfast, judgeVanish, false);
    }

    private void updateMicroMultiBad(LaneState state, Note n, long mtime, int judge, long mfast, boolean judgeVanish) {
        updateMicro(state, n, mtime, judge, mfast, judgeVanish, true);
    }

    private void updateMicro(LaneState state, Note n, long mtime, int judge, long mfast, boolean judgeVanish, boolean multiBad) {
        if (n.isFreeZoneInner()) {
            if (judgeVanish) {
                n.setState(judge + 1);
            }
            n.setMicroPlayTime(mfast);
            if (judge <= 3) {
                markFreeZoneScratchedForNote(state, n);
            }
            return;
        }
        if (judgeVanish) {
            if (score.getPassnotes() < ghost.length) {
                ghost[score.getPassnotes()] = judge;
            }
            n.setState(judge + 1);
            score.setPassnotes(score.getPassnotes() + 1);
        }
        if (miss == MissCondition.ONE && judge == 4 && n.getPlayTime() != 0) {
            return;
        }
        n.setMicroPlayTime(mfast);
        score.addJudgeCount(judge, mfast >= 0, 1);

        if (judge < 4) {
            recentJudgesIndex = (recentJudgesIndex + 1) % recentJudges.length;
            recentJudges[recentJudgesIndex] = mfast / 1000;
            microrecentJudges[recentJudgesIndex] = mfast;
        }

        if (combocond[judge] && judge < 5) {
            combo++;
            score.setCombo(Math.max(score.getCombo(), combo));
            coursecombo++;
            coursemaxcombo = coursemaxcombo > coursecombo ? coursemaxcombo : coursecombo;
        }
        if (!combocond[judge]) {
            combo = 0;
            coursecombo = 0;
        }

        if (judge != 4)
            this.judge[state.player][state.offset] = judge == 0 ? 1 : judge * 2 + (mfast > 0 ? 0 : 1);
        if (judge <= ((PlaySkin) main.getSkin()).getJudgetimer()) {
            main.timer.setTimerOn(SkinPropertyMapper.bombTimerId(state.player, state.offset));
        }

        final int lanelength = states.length;
        if (judgenow.length > 0) {
            final int judgeindex = state.lane / (lanelength / judgenow.length);
            main.timer.setTimerOn(JUDGE_TIMER[judgeindex]);
            if (judgenow.length >= 3) {
                for (int i = 0; i < COMBO_TIMER.length; i++) {
                    if (i != judgeindex)
                        main.timer.setTimerOff(COMBO_TIMER[i]);
                }
            }
            main.timer.setTimerOn(COMBO_TIMER[judgeindex]);
            judgenow[judgeindex] = judge + 1;
            judgecombo[judgeindex] = main.getJudgeManager().getCourseCombo();
            judgefast[judgeindex] = mfast / 1000;
            mjudgefast[judgeindex] = mfast;
        }
        main.update(judge, mtime / 1000);
        if (!multiBad) {
            keysound.play(judge, mfast >= 0);
        }

        final PlayerConfig player = main.main.getPlayerConfig();
        final PlayConfig pc = player.getPlayConfig(main.getMode()).getPlayconfig();
        if(player.isNotesDisplayTimingAutoAdjust()) {
            final BMSPlayerMode autoplay = main.resource.getPlayMode();
            if(autoplay.mode == BMSPlayerMode.Mode.PLAY || autoplay.mode == BMSPlayerMode.Mode.PRACTICE) {
                if (judge <= 3) {
                    pressesSinceLastAutoadjust++;
                    if (pressesSinceLastAutoadjust > 9) {
                        if (mfast <= -500 || mfast >= 500) {
                            int nextTiming = pc.getJudgetiming() + (int) (mfast < 0 ? 1 : -1);
                            nextTiming = com.badlogic.gdx.math.MathUtils.clamp(nextTiming, PlayConfig.JUDGETIMING_MIN, PlayConfig.JUDGETIMING_MAX);
                            pc.setJudgetiming(nextTiming);
                            if (main.getLanerender() != null) {
                                main.getLanerender().getPlayConfig().setJudgetiming(nextTiming);
                            }
                        }
                        pressesSinceLastAutoadjust = 0;
                    }
                }
            }           
        }
    }

    public long[] getRecentJudges() {
        return recentJudges;
    }
    
    public long[] getMicroRecentJudges() {
        return microrecentJudges;
    }   

    public int getRecentJudgesIndex() {
        return recentJudgesIndex;
    }

    public long getRecentJudgeTiming(int player) {
        return player >= 0 && player < judgefast.length ? judgefast[player] : 0;
    }

    public long getRecentJudgeMicroTiming(int player) {
        return player >= 0 && player < mjudgefast.length ? mjudgefast[player] : 0;
    }

    public LongNote getProcessingLongNote(int lane) {
        return states[lane].processing;
    }

    public LongNote getPassingLongNote(int lane) {
        return states[lane].passing;
    }

    /**
     * HLNのBODY描画状態インデックスを返す (6: ACTIVE, 7: INACTIVE, 8: REACTIVE, 9: DAMAGE)
     */
    public int getHellLongNoteBodyState(int lane, LongNote ln) {
        final LaneState state = states[lane];
        final LongNote start = ln.isEnd() ? ln.getPair() : ln;
        if (state.hlnStart != start) {
            return 7;
        }
        if (state.hlnHeld || autoplay) {
            return state.hlnReactive ? 8 : 6;
        }
        return 9;
    }

    /**
     * いずれかのレーンでHLNのBODYがまだ終点時刻未到達で有効かどうかを返す
     */
    public boolean hasActiveHellLongNoteBody() {
        if (states == null) {
            return false;
        }
        for (LaneState state : states) {
            if (state.hlnStart != null) {
                return true;
            }
        }
        return false;
    }

    public boolean getHellChargeJudge(int lane) {
        return states[lane].inclease;
    }

    public long[] getAutoPresstime() {
        return auto_presstime;
    }

    /**
     * 現在の1曲内のコンボ数を取得する
     *
     * @return 現在のコンボ数
     */
    public int getCombo() {
        return combo;
    }

    /**
     * 現在のコース内のコンボ数を取得する
     *
     * @return 現在のコンボ数
     */
    public int getCourseCombo() {
        return coursecombo;
    }

    public void setCourseCombo(int combo) {
        this.coursecombo = combo;
    }

    public int getCourseMaxcombo() {
        return coursemaxcombo;
    }

    public void setCourseMaxcombo(int combo) {
        this.coursemaxcombo = combo;
    }

    public long[][] getJudgeTimeRegion(int lane) {
        return states[lane].sckey >= 0 ? smjudge : nmjudge;
    }

    public ScoreData getScoreData() {
        return score;
    }

    /**
     * 指定の判定のカウント数を返す
     *
     * @param judge
     *            0:PG, 1:GR, 2:GD, 3:BD, 4:PR, 5:MS
     * @return 判定のカウント数
     */
    public int getJudgeCount(int judge) {
        return score.getJudgeCount(judge);
    }

    /**
     * 指定の判定のカウント数を返す
     *
     * @param judge
     *            0:PG, 1:GR, 2:GD, 3:BD, 4:PR, 5:MS
     * @param fast
     *            true:FAST, flase:SLOW
     * @return 判定のカウント数
     */
    public int getJudgeCount(int judge, boolean fast) {
        return score.getJudgeCount(judge, fast);
    }

    public int getJudge(int timerId) {
        int player = SkinPropertyMapper.getKeyJudgeValuePlayer(timerId);
        int offset = SkinPropertyMapper.getKeyJudgeValueOffset(timerId);
        if (player >= judge.length || offset >= judge[player].length)
            return -1;
        return judge[player][offset];
    }

    public int getNowJudge(int player) {
        return player >= 0 && player < judgenow.length ? judgenow[player] : 0;
    }

    public int getNowCombo(int player) {
        return player >= 0 && player < judgecombo.length ? judgecombo[player] : 0;
    }

    public long[][] getJudgeTable(boolean sc) {
        return sc ? smjudge : nmjudge;
    }

    public int getPastNotes() {
        return score.getPassnotes();
    }

    public int[] getGhost() {
        return ghost;
    }
    
    /**
     * 指定レーンの指定ロングノート始点が現在アクティブ（保持中・通過中）かどうかを返す
     */
    public boolean isLongNoteActive(int lane, LongNote lnStart) {
        if (states == null || lane < 0 || lane >= states.length || lnStart == null) {
            return false;
        }
        final LaneState state = states[lane];
        return state.processing == lnStart.getPair() || state.passing == lnStart || state.hlnStart == lnStart;
    }

    /**
     * 動的条件分岐による譜面差し替え時の状態更新
     */
    public void onConditionalBranchSwitched(BMSModel model, long evalMicroTime) {
        lastBranchEvalTime = Math.max(lastBranchEvalTime, evalMicroTime);
        if (states != null) {
            final int seekTime = (int) ((prevmtime + mjudgestart - 100000) / 1000);
            for (LaneState state : states) {
                state.lanemodel.update(model, state.lane, seekTime);
            }
        }
        final int newTotalNotes = model.getTotalNotes();
        score.setNotes(newTotalNotes);
        if (ghost != null && ghost.length != newTotalNotes) {
            final int oldLen = ghost.length;
            ghost = Arrays.copyOf(ghost, newTotalNotes);
            if (newTotalNotes > oldLen) {
                Arrays.fill(ghost, oldLen, newTotalNotes, 4);
            }
        }
    }

    private static class LaneState {
        
        public final int lane;
        
        public final int player;
        
        public final int offset;
        
        public final int sckey;
        
        public final int[] laneassign;
        
        public final int timerActive;
        
        public final int timerDamage;
        
        public final Lane lanemodel;
        /**
         * 処理中のLN
         */
        public LongNote processing;
        /**
         * 通過中のHCN
         */
        public LongNote passing;
        /**
         * HCN増加判定
         */
        public boolean inclease;
        public long mpassingcount;
        
        public int lnstartJudge;
        public long lnstartDuration;
        
        public long releasetime = Long.MIN_VALUE;
        public int lnendJudge = Integer.MIN_VALUE;

        /**
         * 現在有効なHLN始点ノーツ
         */
        public LongNote hlnStart;
        /**
         * HLNゲージを前回精算した時刻(us)
         */
        public long hlnLastGaugeTime = Long.MIN_VALUE;
        /**
         * HLN始点音が再生済みかどうか
         */
        public boolean hlnSoundPlayed;
        /**
         * HLNがREACTIVE状態（BAD捕捉、途中離し経験、または見逃しPOOR後）かどうか
         */
        public boolean hlnReactive;
        /**
         * HLNの直近の保持状態
         */
        public boolean hlnHeld;
        /**
         * 終端音を自動再生済みのHLN終端ノーツ
         */
        public final java.util.Set<LongNote> hlnEndSoundPlayed = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        
        public LaneState(int lane, LaneProperty property, Lane lanemodel) {
            this.lane = lane;
            this.lanemodel = lanemodel;
            this.player = property.getLanePlayer()[lane];
            this.offset = property.getLaneSkinOffset()[lane];
            this.sckey = property.getLaneScratchAssign()[lane];
            this.laneassign = property.getLaneKeyAssign()[lane];
            final int offset = property.getLaneSkinOffset()[lane];
            this.timerActive = SkinPropertyMapper.hcnActiveTimerId(property.getLanePlayer()[lane], offset);
            this.timerDamage = SkinPropertyMapper.hcnDamageTimerId(property.getLanePlayer()[lane], offset);
        }
    }

    private final class MultiBadCollector {
        private long[][] mjudge;
        private boolean enabled;

        public Note[] noteList;
        public long[] timeList;
        public int size;
        public int arrayStart;


        public MultiBadCollector() {
            noteList = new Note[256];
            timeList = new long[256];
            size = 0;
            arrayStart = 0;
            enabled = true;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
            if (!this.enabled) {
                clear();
            }
        }

        public void clear() {
            size = 0;
            arrayStart = 0;
        }

        public void setJudge(long[][] mjudge) {
            this.mjudge = mjudge;
        }

        public void add(Note note, long dmtime) {
            if (!enabled) return;
            if (size >= noteList.length) {
                int newLength = noteList.length*2;
                noteList = Arrays.copyOf(noteList, newLength);
                timeList = Arrays.copyOf(timeList, newLength);
            }
            noteList[size] = note;
            timeList[size] = dmtime;
            size++;
        }

        public void filter(Note tnote) {
            if (!enabled || tnote == null) {
                return;
            }
            long tdmtime = -1;
            for (int i=0; i<size; ++i) {
                if (tnote == noteList[i]) {
                    tdmtime = timeList[i];
                }
            }
            if (tdmtime == -1) {
                System.out.println("ERROR: UNABLE TO FIND TNOTE");
            }
            // JUDGE WINDOWS | 2 - GOOD
            // 0 - PGREAT    | 3 - BAD
            // 1 - GREAT     | 4 - MASHPOOR
            // Windows are inclusive (<= and >=)
            long goodStart = mjudge[2][0];
            long goodEnd = mjudge[2][1];
            long badStart = mjudge[3][0];
            long badEnd = mjudge[3][1];

            // 0. Add all notes within range (including LN)
            // 1. Filter out notes not within bad range (include late bad LNs)
            // 2. Remove tnote from the list
            int newIndex = 0;
            for (int i=0; i<size; i++) {
                if (timeList[i] < badStart || timeList[i] > badEnd) continue;
                if (timeList[i] >= goodStart && timeList[i] <= goodEnd) continue;
                if (noteList[i] == tnote) continue;

                noteList[newIndex] = noteList[i];
                timeList[newIndex] = timeList[i];
                newIndex++;
            }
            size = newIndex;

            // Insertion sort to ensure that the array is sorted by dmtime (fast if already sorted)
            for (int i=1; i<size; i++) {
                for (int j=i; j>0 && timeList[j-1] > timeList[j]; j--) {
                    Note temp = noteList[j];
                    noteList[j] = noteList[j-1];
                    noteList[j-1] = temp;
                    long temp2 = timeList[j];
                    timeList[j] = timeList[j-1];
                    timeList[j-1] = temp2;
                }
            }

            // 3. If tnote is an LN or not a bad, remove all notes after tnote in the list.
            boolean tnoteIsBad = (badStart <= tdmtime && tdmtime < goodStart) || (goodEnd < tdmtime && tdmtime <= badEnd);
            if (!tnoteIsBad || (tnote instanceof LongNote)) {
                for (int i=0; i<size; i++) {
                    if (timeList[i] >= tdmtime) {
                        size = i;
                        break;
                    }
                }
            }

            // 4. Remove all preceding LNs before tnote in the list
            arrayStart = size;
            for (int i=0; i<size; i++) {
                if ((timeList[i] >= tdmtime) || !(noteList[i] instanceof LongNote)) {
                    arrayStart = i;
                    break;
                }
            }
        }
    }
}
