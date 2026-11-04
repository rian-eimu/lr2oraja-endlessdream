package bms.player.beatoraja.play;

import static bms.model.DecodeLog.State.WARNING;
import static bms.player.beatoraja.skin.SkinProperty.TIMER_PLAY;

import bms.model.DecodeLog;
import com.badlogic.gdx.utils.Array;

import bms.model.BMSModel;
import bms.model.Note;
import bms.model.TimeLine;
import bms.player.beatoraja.Config;
import bms.player.beatoraja.audio.AudioDriver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * キー音処理用クラス
 * 
 * @author exch
 */
public class KeySoundProcessor {

	private final BMSPlayer player;
	
	private final AudioDriver audio;
	/**
	 * BGレーン再生用スレッド
	 */
	private AutoplayThread autoThread;
	
	public KeySoundProcessor(BMSPlayer player) {
		this.player = player;
		audio = player.main.getAudioProcessor();
	}

	public void startBGPlay(BMSModel model, long starttime) {
		autoThread = new AutoplayThread(model, starttime);
		autoThread.start();
	}
	
	public void stopBGPlay() {
		if (autoThread != null) {
			autoThread.stop = true;
			autoThread.interrupt();
		}
	}

	public void updateBGPlay(BMSModel model, long evalMicroTime) {
		if (autoThread != null) {
			autoThread.updateModel(model, evalMicroTime);
		}
	}
	
	/**
	 * BGレーン再生用スレッド
	 *
	 * @author exch
	 */
	class AutoplayThread extends Thread {

		private volatile boolean stop = false;

		private final long starttime;
		private TimeLine[] timelines;
		private long lasttime;
		private int p = 0;
		private long lastPlayedMicroTime = Long.MIN_VALUE;

		public AutoplayThread(BMSModel model, long starttime) {
			this.starttime = starttime;
			this.timelines = extractBgTimeLines(model);
			this.lasttime = timelines.length > 0 ? timelines[timelines.length - 1].getMicroTime() : 0;
			this.lastPlayedMicroTime = starttime - 1;
		}

		private TimeLine[] extractBgTimeLines(BMSModel model) {
			Array<TimeLine> tls = new Array<TimeLine>();
			for (TimeLine tl : model.getAllTimeLines()) {
				if (tl.getBackGroundNotes().length > 0) {
					tls.add(tl);
				}
			}
			return tls.toArray(TimeLine.class);
		}

		public synchronized void updateModel(BMSModel model, long evalMicroTime) {
			this.timelines = extractBgTimeLines(model);
			this.lasttime = timelines.length > 0 ? timelines[timelines.length - 1].getMicroTime() : 0;
			final long resumeFrom = Math.min(evalMicroTime, lastPlayedMicroTime + 1);
			int newP = 0;
			while (newP < timelines.length && timelines[newP].getMicroTime() < resumeFrom) {
				newP++;
			}
			this.p = newP;
			this.interrupt();
		}

		@Override
		public void run() {
			final Config config = player.resource.getConfig();
			synchronized (this) {
				for (long time = starttime; p < timelines.length && timelines[p].getMicroTime() < time; p++)
					;
			}

			while (!stop) {
				final long time = player.timer.getNowMicroTime(TIMER_PLAY);
				float volume = player.getAdjustedVolume();
				if (volume < 0) {
					volume = config.getAudioConfig().getBgvolume();
				}
				final long nextEvalTime = player.getNextConditionalEvalMicroTime();
				long sleeptime = 0;
				boolean finished = false;
				synchronized (this) {
					// BGレーン再生（未評価の動的分岐境界より先は評価完了まで待機）
					while (p < timelines.length && timelines[p].getMicroTime() <= time
							&& timelines[p].getMicroTime() < nextEvalTime) {
						for (Note n : timelines[p].getBackGroundNotes()) {
							audio.play(n, volume, 0);
						}
						lastPlayedMicroTime = timelines[p].getMicroTime();
						p++;
					}
					if (p < timelines.length) {
						if (timelines[p].getMicroTime() >= nextEvalTime && time >= nextEvalTime) {
							sleeptime = 1000;
						} else {
							sleeptime = timelines[p].getMicroTime() - time;
						}
					}
					if (time >= lasttime && p >= timelines.length && nextEvalTime == Long.MAX_VALUE) {
						finished = true;
					}
				}
				if (finished) {
					break;
				}
				if (sleeptime > 0) {
					try {
						sleep(Math.max(1, sleeptime / 1000));
					} catch (InterruptedException e) {
					}
				}
			}
		}
	}
}
