package bms.player.beatoraja.pattern;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import bms.model.BMSModel;
import bms.model.LongNote;
import bms.model.NormalNote;
import bms.model.Note;
import bms.model.TimeLine;

/**
 * 演奏ノートがあるタイムラインに追加ノーツを配置する譜面オプション
 *
 * @author exch
 */
public class ExtraNoteModifier extends PatternModifier {

    // TODO noteType未実装

    /**
     * 追加するノーツ種類
     */
    private int noteType;
    /**
     * 同一タイムラインへの最大ノーツ追加数
     */
    private final int depth;
    /**
     * スクラッチレーンを対象にするかどうか
     */
    private boolean scratch;

    public ExtraNoteModifier(int noteType, int depth, boolean scratch) {
        this.noteType = noteType;
        this.depth = depth;
        this.scratch = scratch;
    }

    private static final int MIN_NOTE_INTERVAL_MS = 120; // 120ms (BPM125 16th note equivalent)

    @Override
    public void modify(BMSModel model) {
        AssistLevel assist = AssistLevel.NONE;
        TimeLine[] tls = model.getAllTimeLines();
        boolean[] lns = new boolean[model.getMode().key];
        boolean[] blank = new boolean[model.getMode().key];
        Random random = new Random(getSeed());

        for (int i = 0; i < tls.length; i++) {
            final TimeLine tl = tls[i];
            long currentTime = tl.getMilliTime();

            // 演奏ノートもBGノートも存在しないタイムラインは対象外
            if (!tl.existNote() && tl.getBackGroundNotes().length == 0) {
                continue;
            }

            for (int key = 0; key < model.getMode().key; key++) {
                final Note note = tl.getNote(key);
                if (note instanceof LongNote ln) {
                    lns[key] = !ln.isEnd();
                }
                blank[key] = !lns[key] && note == null && (scratch || !model.getMode().isScratchKey(key));
            }

            for (int d = 0; d < depth; d++) {
                List<Integer> candidateKeys = new ArrayList<>();

                for (int key = 0; key < model.getMode().key; key++) {
                    if (blank[key]) {
                        // Jack suppression check (縦連発生活用抑止チェック)
                        boolean tooClose = false;
                        // Check backward
                        for (int back = i - 1; back >= 0; back--) {
                            TimeLine prevTl = tls[back];
                            long prevTime = prevTl.getMilliTime();
                            if (currentTime - prevTime >= MIN_NOTE_INTERVAL_MS) {
                                break;
                            }
                            if (prevTl.existNote(key)) {
                                tooClose = true;
                                break;
                            }
                        }
                        if (!tooClose) {
                            // Check forward
                            for (int forward = i + 1; forward < tls.length; forward++) {
                                TimeLine nextTl = tls[forward];
                                long nextTime = nextTl.getMilliTime();
                                if (nextTime - currentTime >= MIN_NOTE_INTERVAL_MS) {
                                    break;
                                }
                                if (nextTl.existNote(key)) {
                                    tooClose = true;
                                    break;
                                }
                            }
                        }

                        if (!tooClose) {
                            candidateKeys.add(key);
                        }
                    }
                }

                if (candidateKeys.isEmpty()) {
                    break;
                }

                // 配置可能なキー候補の中からランダムに選択して無音ノーツを配置
                int chosenKey = candidateKeys.get(random.nextInt(candidateKeys.size()));
                final Note note = new NormalNote(-1);
                tl.setNote(chosenKey, note);
                blank[chosenKey] = false;
                assist = AssistLevel.ASSIST;
            }
        }

        setAssistLevel(assist);
    }
}


