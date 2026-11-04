package bms.player.beatoraja.play;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.ToDoubleFunction;

import bms.model.BMSModel;
import bms.model.BMSModelUtils;
import bms.model.ConditionalBlock;
import bms.model.LongNote;
import bms.model.MineNote;
import bms.model.NormalNote;
import bms.model.Note;
import bms.model.TimeLine;
import bms.player.beatoraja.ScoreData;

/**
 * プレイ中の動的条件分岐（#CONDITIONAL）の評価および譜面差し替えを管理するコントローラ
 */
public class ConditionalBranchController {

    private final BMSPlayer player;
    private final BMSModel model;
    private final int[] selectedBranches;
    private final int[] replayBranches;
    private final int initialTotalNotes;
    private final double initialTotal;
    private final int bestLamp;
    private final boolean isFullAutoplay;
    private final long initialMarginTime;
    private final int freq;
    private final Consumer<BMSModel> modifierApplier;
    private ToDoubleFunction<ConditionalBlock.Variable> customVariableProvider;

    private int nextBlockIndex = 0;

    public ConditionalBranchController(
            BMSPlayer player,
            BMSModel model,
            int[] replayBranches,
            int initialTotalNotes,
            double initialTotal,
            int bestLamp,
            boolean isFullAutoplay,
            long initialMarginTime,
            int freq,
            Consumer<BMSModel> modifierApplier) {
        this.player = player;
        this.model = model;
        final ConditionalBlock[] blocks = model.getConditionalBlocks();
        this.selectedBranches = new int[blocks.length];
        for (int i = 0; i < blocks.length; i++) {
            this.selectedBranches[i] = blocks[i].getDefaultBranchIndex();
        }
        this.replayBranches = replayBranches;
        this.initialTotalNotes = initialTotalNotes;
        this.initialTotal = initialTotal;
        this.bestLamp = bestLamp;
        this.isFullAutoplay = isFullAutoplay;
        this.initialMarginTime = initialMarginTime;
        this.freq = freq;
        this.modifierApplier = modifierApplier;
    }

    public void setCustomVariableProvider(ToDoubleFunction<ConditionalBlock.Variable> customVariableProvider) {
        this.customVariableProvider = customVariableProvider;
    }

    /**
     * 次に評価すべき #CONDITIONAL ブロックの評価時刻（マイクロ秒）を返す。
     * 未評価ブロックが存在しない場合は Long.MAX_VALUE を返す。
     */
    public synchronized long getNextEvalMicroTime() {
        final TimeLine[] evalTls = model.getConditionalEvalTimeLines();
        if (evalTls == null || nextBlockIndex >= evalTls.length) {
            return Long.MAX_VALUE;
        }
        final TimeLine tl = evalTls[nextBlockIndex];
        return tl != null ? tl.getMicroTime() : Long.MAX_VALUE;
    }

    /**
     * 現在選択されている各 #CONDITIONAL ブロックの分岐インデックス配列のコピーを返す
     */
    public synchronized int[] getSelectedBranches() {
        return Arrays.copyOf(selectedBranches, selectedBranches.length);
    }

    /**
     * 指定時刻 mtime（マイクロ秒）に到達しているすべての #CONDITIONAL ブロックを評価し、
     * DEFAULT 以外の分岐が選択された場合は譜面を動的に差し替える。
     */
    public synchronized void evaluateUpTo(long mtime) {
        final ConditionalBlock[] blocks = model.getConditionalBlocks();
        TimeLine[] evalTls = model.getConditionalEvalTimeLines();
        if (blocks == null || evalTls == null) {
            return;
        }

        while (nextBlockIndex < blocks.length && nextBlockIndex < evalTls.length) {
            final TimeLine evalTl = evalTls[nextBlockIndex];
            if (evalTl == null || evalTl.getMicroTime() > mtime) {
                break;
            }
            final long evalMicroTime = evalTl.getMicroTime();
            final double evalSection = evalTl.getSection();
            final ToDoubleFunction<ConditionalBlock.Variable> variableProvider;
            if (customVariableProvider != null) {
                variableProvider = customVariableProvider;
            } else {
                final LiveSnapshot snapshot = captureSnapshot();
                variableProvider = snapshot::getValue;
            }

            boolean branchChanged = false;
            while (nextBlockIndex < blocks.length && nextBlockIndex < evalTls.length) {
                final TimeLine curTl = evalTls[nextBlockIndex];
                if (curTl == null || curTl.getMicroTime() != evalMicroTime) {
                    break;
                }
                final ConditionalBlock block = blocks[nextBlockIndex];
                final int chosen;
                if (replayBranches != null) {
                    if (nextBlockIndex < replayBranches.length
                            && replayBranches[nextBlockIndex] >= 0
                            && replayBranches[nextBlockIndex] < block.getBranches().size()) {
                        chosen = replayBranches[nextBlockIndex];
                    } else {
                        chosen = block.getDefaultBranchIndex();
                    }
                } else {
                    chosen = block.evaluate(variableProvider);
                }
                if (selectedBranches[nextBlockIndex] != chosen) {
                    selectedBranches[nextBlockIndex] = chosen;
                    branchChanged = true;
                }
                nextBlockIndex++;
            }

            if (branchChanged) {
                rebuildAndMergeChart(evalSection, evalMicroTime);
                evalTls = model.getConditionalEvalTimeLines();
            }
        }
    }

    private LiveSnapshot captureSnapshot() {
        final JudgeManager judge = player != null ? player.getJudgeManager() : null;
        final ScoreData score = judge.getScoreData();
        final GrooveGauge gauge = player.getGauge();

        final int exScore = score.getExscore();
        final double rate = initialTotalNotes > 0 ? (exScore * 100.0) / (initialTotalNotes * 2.0) : 100.0;
        final int combo = judge.getCombo();
        final int maxCombo = score.getCombo();
        final int pgreat = judge.getJudgeCount(0);
        final int great = judge.getJudgeCount(1);
        final int good = judge.getJudgeCount(2);
        final int bad = judge.getJudgeCount(3);
        final int poor = judge.getJudgeCount(4);
        final int miss = score.getMinbp();
        final double gaugeValue = gauge != null ? gauge.getValue() : 0.0;

        return new LiveSnapshot(
                exScore, rate, combo, maxCombo,
                pgreat, great, good, bad, poor, miss,
                gaugeValue, bestLamp, isFullAutoplay ? 1.0 : 0.0);
    }

    private void rebuildAndMergeChart(double evalSection, long evalMicroTime) {
        final ConditionalBlock.ChartBuilder builder = model.getConditionalChartBuilder();
        if (builder == null) {
            return;
        }
        final BMSModel branched = builder.createBranchedModel(model, selectedBranches);
        if (initialMarginTime > 0) {
            BMSModelUtils.applyStartNoteTimeMargin(branched, initialMarginTime);
        }
        BMSPlayerRule.validate(branched);
        branched.setTotal(initialTotal);

        final int keys = branched.getMode().key;
        for (TimeLine tl : branched.getAllTimeLines()) {
            for (int lane = 0; lane < keys; lane++) {
                final Note n = tl.getNote(lane);
                if (n instanceof MineNote && n.getWav() == -2) {
                    n.setWav(-1);
                }
            }
        }

        if (freq != 100) {
            BMSModelUtils.changeFrequency(branched, freq / 100f);
        }
        if (modifierApplier != null) {
            modifierApplier.accept(branched);
        }

        final TimeLine[] oldTls = model.getAllTimeLines();
        final TimeLine[] newTls = branched.getAllTimeLines();
        final TimeLine[] mergedTls = mergeTimeLines(oldTls, newTls, keys, evalSection, evalMicroTime);

        model.setAllTimeLine(mergedTls);
        model.setConditionalEvalTimeLines(branched.getConditionalEvalTimeLines());
        if (player != null) {
            player.onConditionalBranchSwitched(evalMicroTime);
        }
    }

    TimeLine[] mergeTimeLines(
            TimeLine[] oldTls,
            TimeLine[] newTls,
            int keys,
            double evalSection,
            long evalMicroTime) {
        final List<TimeLine> pastTls = new ArrayList<>();
        final List<TimeLine> oldFutureTls = new ArrayList<>();
        for (TimeLine tl : oldTls) {
            if (isBeforeEval(tl, evalSection, evalMicroTime)) {
                pastTls.add(tl);
            } else {
                oldFutureTls.add(tl);
            }
        }

        final TreeMap<Long, TimeLine> futureMap = new TreeMap<>();
        for (TimeLine tl : newTls) {
            if (!isBeforeEval(tl, evalSection, evalMicroTime)) {
                // 新分岐側で評価位置より前に始点がある孤立LN終端を除去する
                for (int lane = 0; lane < keys; lane++) {
                    final Note n = tl.getNote(lane);
                    if (n instanceof LongNote) {
                        final LongNote ln = (LongNote) n;
                        if (ln.isEnd() && ln.getPair() != null
                                && ln.getPair().getSection() < evalSection - 1e-9) {
                            tl.setNote(lane, null);
                        }
                    }
                }
                futureMap.put(sectionKey(tl.getSection()), tl);
            }
        }

        final JudgeManager judge = player != null ? player.getJudgeManager() : null;

        // 1. 評価タイミングより前に始点があり、評価タイミング以降に終点があるLNを保護する
        for (TimeLine tl : pastTls) {
            for (int lane = 0; lane < keys; lane++) {
                final Note n = tl.getNote(lane);
                if (n instanceof LongNote) {
                    final LongNote ln = (LongNote) n;
                    if (!ln.isEnd() && ln.getPair() != null) {
                        final LongNote lnEnd = ln.getPair();
                        if (lnEnd.getSection() >= evalSection - 1e-9) {
                            clearNotesInSectionRange(futureMap, lane, evalSection, lnEnd.getSection());
                            final TimeLine targetTl = getOrCreateFutureTimeLine(
                                    futureMap, lnEnd.getSection(), lnEnd.getMicroTime(), tl.getBPM(), keys);
                            lnEnd.setSection(targetTl.getSection());
                            lnEnd.setMicroTime(targetTl.getMicroTime());
                            targetTl.setNote(lane, lnEnd);
                        }
                    }
                }
            }
        }

        // 2. 評価タイミング以降のノーツのうち、早押し等で既に判定済み・保持中のノーツを保護する
        for (TimeLine oldTl : oldFutureTls) {
            for (int lane = 0; lane < keys; lane++) {
                final Note n = oldTl.getNote(lane);
                if (n instanceof NormalNote && n.getState() != 0) {
                    clearNotesInSectionRange(futureMap, lane, oldTl.getSection(), oldTl.getSection());
                    final TimeLine targetTl = getOrCreateFutureTimeLine(
                            futureMap, oldTl.getSection(), oldTl.getMicroTime(), oldTl.getBPM(), keys);
                    n.setSection(targetTl.getSection());
                    n.setMicroTime(targetTl.getMicroTime());
                    targetTl.setNote(lane, n);
                } else if (n instanceof LongNote) {
                    final LongNote ln = (LongNote) n;
                    if (!ln.isEnd() && ln.getPair() != null
                            && (ln.getState() != 0 || (judge != null && judge.isLongNoteActive(lane, ln)))) {
                        final LongNote lnEnd = ln.getPair();
                        clearNotesInSectionRange(futureMap, lane, ln.getSection(), lnEnd.getSection());
                        final TimeLine startTl = getOrCreateFutureTimeLine(
                                futureMap, ln.getSection(), ln.getMicroTime(), oldTl.getBPM(), keys);
                        ln.setSection(startTl.getSection());
                        ln.setMicroTime(startTl.getMicroTime());
                        startTl.setNote(lane, ln);

                        final TimeLine endTl = getOrCreateFutureTimeLine(
                                futureMap, lnEnd.getSection(), lnEnd.getMicroTime(), startTl.getBPM(), keys);
                        lnEnd.setSection(endTl.getSection());
                        lnEnd.setMicroTime(endTl.getMicroTime());
                        endTl.setNote(lane, lnEnd);
                    }
                }
            }
        }

        final List<TimeLine> merged = new ArrayList<>(pastTls.size() + futureMap.size());
        merged.addAll(pastTls);
        merged.addAll(futureMap.values());
        return merged.toArray(new TimeLine[0]);
    }

    private static void clearNotesInSectionRange(
            TreeMap<Long, TimeLine> futureMap,
            int lane,
            double startSection,
            double endSection) {
        final long fromKey = sectionKey(startSection) - 1;
        final long toKey = sectionKey(endSection) + 1;
        for (Map.Entry<Long, TimeLine> entry : futureMap.subMap(fromKey, true, toKey, true).entrySet()) {
            final TimeLine tl = entry.getValue();
            final Note existing = tl.getNote(lane);
            if (existing instanceof LongNote) {
                final LongNote existingLn = (LongNote) existing;
                final LongNote pair = existingLn.getPair();
                if (pair != null) {
                    final TimeLine pairTl = futureMap.get(sectionKey(pair.getSection()));
                    if (pairTl != null && pairTl.getNote(lane) == pair) {
                        pairTl.setNote(lane, null);
                    }
                }
            }
            tl.setNote(lane, null);
        }
    }

    private static TimeLine getOrCreateFutureTimeLine(
            TreeMap<Long, TimeLine> futureMap,
            double section,
            long microTime,
            double bpm,
            int keys) {
        final long key = sectionKey(section);
        TimeLine tl = futureMap.get(key);
        if (tl == null) {
            tl = new TimeLine(section, microTime, keys);
            tl.setBPM(bpm);
            futureMap.put(key, tl);
        }
        return tl;
    }

    private static long sectionKey(double section) {
        return Math.round(section * 1_000_000_000L);
    }

    private static boolean isBeforeEval(TimeLine tl, double evalSection, long evalMicroTime) {
        if (tl.getSection() < evalSection - 1e-9) {
            return true;
        }
        if (Math.abs(tl.getSection() - evalSection) <= 1e-9 && tl.getMicroTime() < evalMicroTime) {
            return true;
        }
        return false;
    }

    private static final class LiveSnapshot {
        private final double score;
        private final double rate;
        private final double combo;
        private final double maxCombo;
        private final double pgreat;
        private final double great;
        private final double good;
        private final double bad;
        private final double poor;
        private final double miss;
        private final double gauge;
        private final double lamp;
        private final double auto;

        private LiveSnapshot(
                double score,
                double rate,
                double combo,
                double maxCombo,
                double pgreat,
                double great,
                double good,
                double bad,
                double poor,
                double miss,
                double gauge,
                double lamp,
                double auto) {
            this.score = score;
            this.rate = rate;
            this.combo = combo;
            this.maxCombo = maxCombo;
            this.pgreat = pgreat;
            this.great = great;
            this.good = good;
            this.bad = bad;
            this.poor = poor;
            this.miss = miss;
            this.gauge = gauge;
            this.lamp = lamp;
            this.auto = auto;
        }

        private double getValue(ConditionalBlock.Variable variable) {
            switch (variable) {
                case SCORE:
                    return score;
                case RATE:
                    return rate;
                case COMBO:
                    return combo;
                case MAXCOMBO:
                    return maxCombo;
                case PGREAT:
                    return pgreat;
                case GREAT:
                    return great;
                case GOOD:
                    return good;
                case BAD:
                    return bad;
                case POOR:
                    return poor;
                case MISS:
                    return miss;
                case GAUGE:
                    return gauge;
                case LAMP:
                    return lamp;
                case AUTO:
                    return auto;
                default:
                    return 0.0;
            }
        }
    }
}
