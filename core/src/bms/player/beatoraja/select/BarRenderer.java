package bms.player.beatoraja.select;

import static bms.player.beatoraja.SystemSoundManager.SoundType.*;

import java.util.stream.*;

import bms.player.beatoraja.input.BMSPlayerInputProcessor;
import bms.player.beatoraja.input.KeyBoardInputProcesseor.ControlKeys;
import bms.player.beatoraja.select.MusicSelectKeyProperty.MusicSelectKey;
import bms.player.beatoraja.select.bar.*;
import bms.player.beatoraja.skin.*;
import bms.player.beatoraja.skin.Skin.SkinObjectRenderer;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.utils.IntSet;
import com.badlogic.gdx.utils.IntSet.IntSetIterator;

import bms.player.beatoraja.CourseData.TrophyData;
import bms.player.beatoraja.song.*;

import bms.player.beatoraja.modmenu.DownloadTaskState;

/**
 * 楽曲バー描画用クラス
 *
 * @author exch
 */
public final class BarRenderer {

	private final MusicSelector select;

	private final BarManager manager;
	
	private final String[] TROPHY = { "bronzemedal", "silvermedal", "goldmedal" };

	/** お気に入りマーカー。ソースの文字コードに依存しないようコードポイントで定義 */
	private static final char FAVORITE_MARK = 0x2605;
	private static final String FAVORITE_MARK_TEXT = String.valueOf(FAVORITE_MARK);
	/**
	 * マーカーの右端をバーの右端からどれだけ内側に置くか。文字の高さに対する倍率。
	 * 描画されたマーカーの幅は取得できないが、★はほぼ正方形なので高さを幅の目安に使う。
	 * 倍率はスキンがスクロールバー等の余白情報を持たないため実測から決めた値
	 */
	private static final float FAVORITE_MARK_MARGIN = 2f;
	/** マーカー描画時の色の退避先。フレーム毎に生成しないよう使い回す */
	private final Color orgcolor = new Color();

	private final int durationlow;
	private final int durationhigh;
	/**
	 * バー移動中のカウンタ
	 */
	private long duration;
	/**
	 * バーの移動方向
	 */
	private int angle;
	private boolean keyinput;

	/**
	 * バー移動中のカウンタ（アナログスクロール）
	 */
	private int analogScrollBuffer = 0;
	private final int analogTicksPerScroll;

	private final int barlength = 60;
	private final BarArea[] bararea;

	private boolean bartextupdate = false;
//	private final boolean[] bartextCharset = new boolean[Character.MAX_VALUE + 1];
	private final IntSet bartextCharset = new IntSet(1024);

	private static class BarArea {
		public Bar sd;
		public float x;
		public float y;
		public int value = -1;
		public int text;
	}

	public BarRenderer(MusicSelector select, BarManager manager) {
		this.select = select;
		this.manager = manager;
		
		durationlow = select.resource.getConfig().getScrollDurationLow();
		durationhigh = select.resource.getConfig().getScrollDurationHigh();
		analogTicksPerScroll = select.resource.getConfig().getAnalogTicksPerScroll();

		manager.init();
		
		bararea = Stream.generate(BarArea::new).limit(barlength).toArray(BarArea[]::new);
	}

	public boolean mousePressed(SkinBar baro, int button, int x, int y) {
		for (int i : ((MusicSelectSkin) select.getSkin()).getClickableBar()) {
			boolean on = (i == ((MusicSelectSkin) select.getSkin()).getCenterBar());
			if (baro.getBarImages(on, i) == null) {
				continue;
			}
			int index = (int) (manager.selectedindex + manager.currentsongs.length * 100 + i
					- ((MusicSelectSkin) select.getSkin()).getCenterBar()) % manager.currentsongs.length;
			Bar sd = manager.currentsongs[index];

			Rectangle r = baro.getBarImages(on, i).getDestination(select.timer.getNowTime(), select);
			if (r != null) {
				if (r != null && r.x <= x && r.x + r.width >= x && r.y <= y && r.y + r.height >= y) {
					if (button == 0) {
						select.select(sd);
					} else {
						manager.close();
					}
					return true;
				}
			}
		}
		return false;
	}

	private long time;
	
	public void prepare(SkinBar baro, long time) {
		final MusicSelectSkin skin = (MusicSelectSkin) select.getSkin();
		if (skin == null) {
			return;
		}
		this.time = time;		
		final long timeMillis = System.currentTimeMillis();
		boolean applyMovement = duration != 0 && duration > timeMillis;
		float angleLerp = 0;
		if (applyMovement) {
			angleLerp = angle < 0 ? ((float) (timeMillis - duration)) / angle
				: ((float) (duration - timeMillis)) / angle;
		}

		for (int i = 0; i < barlength; i++) {
			// calcurate song bar position
			final BarArea ba = bararea[i];
			boolean on = (i == skin.getCenterBar());
			if (baro.getBarImages(on, i) == null) {
				continue;
			}
			float dx = 0;
			float dy = 0;
			final SkinImage si1 = baro.getBarImages(on, i);
			if (si1.draw) {
				if (applyMovement) {
					int nextindex = i + (angle >= 0 ? 1 : -1);
					SkinImage si2 = nextindex >= 0 ? baro.getBarImages(nextindex == skin.getCenterBar(), nextindex)
							: null;
					if (si2 != null && si2.draw) {
						dx = (si2.region.x - si1.region.x) * Math.max(Math.min(angleLerp, 1), -1);
						dy = (si2.region.y - si1.region.y) * angleLerp;
					}
				}
				ba.x = (int) (si1.region.x + dx);
				ba.y = (int) (si1.region.y + dy + (baro.getPosition() == 1 ? si1.region.height : 0));

				// set song bar type
				int index = (manager.selectedindex + manager.currentsongs.length * 100 + i - skin.getCenterBar())
						% manager.currentsongs.length;
				Bar sd = manager.currentsongs[index];
				ba.sd = sd;

				if (sd instanceof TableBar || sd instanceof HashBar || sd instanceof ExecutableBar) {
					ba.value = 2;
				} else if (sd instanceof GradeBar) {
					ba.value = ((GradeBar) sd).existsAllSongs() ? 3 : 4;
				} else if (sd instanceof RandomCourseBar) {
					ba.value = ((RandomCourseBar) sd).existsAllSongs() ? 2 : 4;
				} else if (sd instanceof FolderBar) {
					ba.value = 1;
				} else if (sd instanceof SongBar) {
					ba.value = ((SongBar) sd).existsSong() ? 0 : 4;
				} else if (sd instanceof SearchWordBar) {
					ba.value = 6;
				} else if (sd instanceof CommandBar || sd instanceof ContainerBar) {
					ba.value = 5;
				} else if (sd instanceof FunctionBar) {
					var fn = ((FunctionBar) sd);
					ba.value = fn.getDisplayBarType();
					ba.text = fn.getDisplayTextType();
                } else {
					ba.value = -1;
				}
			} else {
				ba.value = -1;
			}

			if(ba.value != -1 && !(ba.sd instanceof FunctionBar)) {
				// Barの種類によってテキストを変える
				// SongBarかFolderBarの場合は新規かどうかでさらに変える
				// songstatus最終値 =
				// 0:通常 1:新規 2:SongBar(通常) 3:SongBar(新規) 4:FolderBar(通常) 5:FolderBar(新規) 6:TableBar or HashBar
				// 7:GradeBar(曲所持) 8:(SongBar or GradeBar)(曲未所持) 9:CommandBar or ContainerBar 10:SearchWordBar
				// 3以降で定義されてなければ0か1を用いる
				int songstatus = ba.value;
				if(songstatus >= 2) {
					songstatus += 4;
					//定義されてなければ0:通常を用いる
					if(baro.getText(songstatus) == null) songstatus = 0;
				} else {
					if (songstatus == 0) {
						SongData song = ((SongBar) ba.sd).getSongData();
						songstatus = song == null || System.currentTimeMillis() / 1000 > song.getAdddate() + 3600 * 24 ? 2 : 3;
						//定義されてなければ0:通常か1:新規を用いる
						if(baro.getText(songstatus) == null) songstatus = songstatus == 3 ? 1 : 0;
					} else {
						FolderData data = ((FolderBar) ba.sd).getFolderData();
						songstatus = data == null || System.currentTimeMillis() / 1000 > data.getAdddate() + 3600 * 24 ? 4 : 5;
						//定義されてなければ0:通常か1:新規を用いる
						if(baro.getText(songstatus) == null) songstatus = songstatus == 5 ? 1 : 0;
					}
				}
				ba.text = songstatus;
			}
		}
	}

	public void render(SkinObjectRenderer sprite, SkinBar baro) {
		final MusicSelectSkin skin = (MusicSelectSkin) select.getSkin();
		if (skin == null) {
			return;
		}

		if (bartextupdate) {
			bartextupdate = false;
			
			bartextCharset.clear();
			for (Bar song : manager.currentsongs) {
				for (char c : song.getTitle().toCharArray()) {
					bartextCharset.add(c);
				}
			}
			// お気に入りマーカーは曲名に含まれないため、常にフォント生成対象に加える
			bartextCharset.add(FAVORITE_MARK);
			char[] chars = new char[bartextCharset.size];
			int i = 0;
			for (IntSetIterator iterator = bartextCharset.iterator();iterator.hasNext;) {
				chars[i++] = (char) iterator.next();
			}
//			Arrays.fill(bartextCharset, false);
//			int charCount = 0;
//			for (Bar song : manager.currentsongs) {
//				final String title = song.getTitle();
//				for(int index = title.length() - 1;index >= 0;index--) {
//					final char c = title.charAt(index);
//					if(!bartextCharset[c]) {
//						bartextCharset[c] = true;
//						charCount++;
//					}
//				}
//			}
//			char[] chars = new char[charCount];
//			int count = 0;
//			for (char c = (char) (bartextCharset.length - 1) ;;c--) {
//				if(bartextCharset[c]) {
//					chars[count++] = c;
//				}
//				if(c == 0) {
//					break;
//				}
//			}
			
			for(int index = 0;index < SkinBar.BARTEXT_COUNT;index++) {
				if(baro.getText(index) != null) {
					baro.getText(index).prepareFont(String.valueOf(chars));
				}				
			}
		}

		// check terminated loader thread and load song images
		if(manager.loader != null && manager.loader.getState() == Thread.State.TERMINATED){
			select.loadSelectedSongImages();
			manager.loader = null;
		}

		// draw song bar
		for (int i = 0; i < barlength; i++) {
			final BarArea ba = bararea[i];
			boolean on = (i == skin.getCenterBar());
			final SkinImage si = baro.getBarImages(on, i);
			if (si == null) {
				continue;
			}

			if (si.draw) {
				float orgx = si.region.x;
				float orgy = si.region.y;
				si.draw(sprite, time, select, ba.value, ba.x - si.region.x, ba.y - si.region.y - (baro.getPosition() == 1 ? si.region.height : 0));
				si.region.x = orgx;
				si.region.y = orgy;
			} else {
				ba.value = -1;
			}
		}

		for (int i = 0; i < barlength; i++) {
			final BarArea ba = bararea[i];
			if(ba.value == -1) {
				continue;
			}
			// folder graph
			if (ba.sd instanceof DirectoryBar) {
				final SkinDistributionGraph graph = baro.getGraph();
				if (graph != null && graph.draw) {
					graph.draw(sprite, (DirectoryBar)ba.sd, ba.x, ba.y);
				}
			}
			else if (ba.sd instanceof FunctionBar) {
				final SkinDistributionGraph graph = baro.getGraph();
				if (graph != null && graph.draw) {
					graph.draw(sprite, (FunctionBar)ba.sd, ba.x, ba.y);
				}
			}
		}

        var downloadTasks = DownloadTaskState.runningDownloadTasks;
        if (!downloadTasks.isEmpty()) {
            // download progress bars
            for (int i = 0; i < barlength; i++) {
                final BarArea ba = bararea[i];
                if (ba.value == -1) { continue; }
                if (!(ba.sd instanceof SongBar)) { continue; }

                var songBar = (SongBar)ba.sd;
				var songMd5 = songBar.getSongData().getMd5();
                for (var task : downloadTasks.values()) {
                    String md5 = task.getHash();
                    if (!md5.equals(songMd5)) { continue; }
                    final SkinDistributionGraph graph = baro.getGraph();
                    if (graph != null && graph.draw) {
                        graph.draw(sprite, songBar, task, ba.x, ba.y);
                    }
                }
            }
        }

		for (int i = 0; i < barlength; i++) {
			final BarArea ba = bararea[i];
			if(ba.value == -1) {
				continue;
			}
			final SkinText text = baro.getText(ba.text);
			if(text != null) {
				drawBarTitle(sprite, text, ba, baro.getBarImages(i == skin.getCenterBar(), i),
						skin.getWidth());
			}
		}

		for (int i = 0; i < barlength; i++) {
			final BarArea ba = bararea[i];
			if(ba.value == -1) {
				continue;
			}
			// trophy
			if (ba.sd instanceof GradeBar) {
				final TrophyData trophy = ((GradeBar) ba.sd).getTrophy();
				if (trophy != null) {
					for (int j = 0; j < TROPHY.length; j++) {
						if (TROPHY[j].equals(trophy.getName())) {
							final SkinImage trophyImage = baro.getTrophy(j);
							if(trophyImage != null) {
								trophyImage.draw(sprite, ba.x, ba.y);								
							}
							break;
						}
					}
				}
			}
		}

		for (int i = 0; i < barlength; i++) {
			final BarArea ba = bararea[i];
			if(ba.value == -1) {
				continue;
			}
			// lamp
			if(select.getRival() != null) {
				final SkinImage playerLamp = baro.getPlayerLamp(ba.sd.getLamp(true));
				if (playerLamp != null) {
					playerLamp.draw(sprite, ba.x, ba.y);
				}
				final SkinImage rivalLamp = baro.getRivalLamp(ba.sd.getLamp(false));
				if (rivalLamp != null) {
					rivalLamp.draw(sprite, ba.x, ba.y);
				}
			} else {
				final SkinImage lamp = baro.getLamp(ba.sd.getLamp(true));
				if (lamp != null) {
					lamp.draw(sprite, ba.x, ba.y);
				}
			}
		}

		for (int i = 0; i < barlength; i++) {
			final BarArea ba = bararea[i];
			if(ba.value == -1) {
				continue;
			}
			// level
			if (ba.sd instanceof SongBar && ((SongBar) ba.sd).existsSong()) {
				final SongData song = ((SongBar) ba.sd).getSongData();
				final SkinNumber leveln = baro.getBarlevel(song.getDifficulty() >= 0 && song.getDifficulty() < 7
						? song.getDifficulty() : 0);
				if (leveln != null) {
					leveln.draw(sprite, time, song.getLevel(), select, ba.x, ba.y);
				}
			}
			else if (ba.sd instanceof FunctionBar
					 && ((FunctionBar) ba.sd).getLevel() != null) {
				final int level = ((FunctionBar) ba.sd).getLevel();
				final SkinNumber leveln = baro.getBarlevel(0);
				if (leveln != null) {
					leveln.draw(sprite, time, level, select, ba.x, ba.y);
				}
			}
		}

		for (int i = 0; i < barlength; i++) {
			final BarArea ba = bararea[i];
			if(ba.value == -1) {
				continue;
			}

			int flag = 0;
			if (ba.sd instanceof SongBar songbar && songbar.existsSong()) {
				SongData song = songbar.getSongData();
				flag |= song.getFeature();
			}

			if (ba.sd instanceof GradeBar gb && gb.existsAllSongs()) {
				for (SongData song : gb.getSongDatas()) {
					flag |= song.getFeature();
				}
			}

			// LN
			int ln = -1;
			if((flag & SongData.FEATURE_UNDEFINEDLN) != 0) {
				ln = select.main.getPlayerConfig().getLnmode();
			}
			if((flag & SongData.FEATURE_LONGNOTE) != 0) {
				ln = ln > 0 ? ln : 0;
			}
			if((flag & SongData.FEATURE_CHARGENOTE) != 0) {
				ln = ln > 1 ? ln : 1;
			}
			if((flag & SongData.FEATURE_HELLCHARGENOTE) != 0) {
				ln = ln > 2 ? ln : 2;
			}

			if(ln >= 0) {
				// LNラベル描画分岐
				final int[] lnindex = {0,3,4};
				if (baro.getLabel(lnindex[ln]) != null) {
					baro.getLabel(lnindex[ln]).draw(sprite, ba.x, ba.y);
				} else if (baro.getLabel(0) != null) {
					baro.getLabel(0).draw(sprite, ba.x, ba.y);
				}

			}
			// MINE
			if ((flag & SongData.FEATURE_MINENOTE) != 0 && baro.getLabel(2) != null) {
				baro.getLabel(2).draw(sprite, ba.x, ba.y);
			}
			// RANDOM
			if ((flag & SongData.FEATURE_RANDOM) != 0 && baro.getLabel(1) != null) {
				baro.getLabel(1).draw(sprite, ba.x, ba.y);
			}
		}
	}

	/**
	 * 楽曲バーのタイトルを描画し、お気に入り譜面にはバーの右端にマーカーを添える。
	 * 曲名の文字列自体は変更しない(getTitle()はBarManager#setSelectedのバー特定や
	 * タイトルソートの比較に使われるため)。
	 * SkinTextはバー間で共有されるので、変更した状態は必ず元に戻すこと。
	 */
	private void drawBarTitle(SkinObjectRenderer sprite, SkinText text, BarArea ba, SkinImage bar,
			float skinwidth) {
		final SongData song = ba.sd instanceof SongBar ? ((SongBar) ba.sd).getSongData() : null;
		if (bar != null && song != null
				&& (song.getFavorite() & (SongData.FAVORITE_SONG | SongData.FAVORITE_CHART)) != 0) {
			final int orgalign = text.getAlign();
			// バー本体もタイトル枠も画面外まで伸びる値を設定するスキンがあるため、
			// バーの右端と画面の右端のうち手前を採る
			final float right = Math.min(ba.x + bar.region.width, skinwidth)
					- text.region.height * FAVORITE_MARK_MARGIN;
			orgcolor.set(text.color);
			text.setAlign(SkinText.ALIGN_RIGHT);
			// アルファは元の値を引き継ぐ。曲名をフェードさせるスキンでマーカーだけ残らないよう
			text.color.set(Color.GOLD.r, Color.GOLD.g, Color.GOLD.b, orgcolor.a);
			text.setText(FAVORITE_MARK_TEXT);
			// 右寄せ時は描画された文字の右端が region.x + オフセット に来る。region.xはバー
			// 相対座標なので、絶対座標のrightから引いた値を渡すとrightが右端になる
			text.draw(sprite, right - text.region.x, ba.y);
			text.setAlign(orgalign);
			text.color.set(orgcolor);
		}
		// 長い曲名がマーカーに重なったとき曲名側を優先するため、マーカーより後に描く
		text.setText(ba.sd.getTitle());
		text.draw(sprite, ba.x, ba.y);
	}

	public void input() {
		BMSPlayerInputProcessor input = select.main.getInputProcessor();

        final MusicSelectKeyProperty property = MusicSelectKeyProperty.values[select.resource.getPlayerConfig().getMusicselectinput()];

		// song bar scroll on mouse wheel
		int mov = -input.getScroll();
		input.resetScroll();

		analogScrollBuffer += property.getAnalogChange(input, MusicSelectKey.UP) - property.getAnalogChange(input, MusicSelectKey.DOWN);
		mov += analogScrollBuffer/analogTicksPerScroll;
		analogScrollBuffer %= analogTicksPerScroll;
		if (mov != 0) {
			// set duration and angle for smooth song bar scroll animation
			long l = System.currentTimeMillis();
			int remainingScroll = angle == 0 ? 0 : (int)Math.max(0, duration - l)/angle;
			remainingScroll = Math.max(Math.min(remainingScroll + mov, 2), -2);
			if (remainingScroll == 0) {
				angle = 0;
				duration = l;
			} else {
				final int scrollDuration = 120/remainingScroll/remainingScroll;
				angle = scrollDuration/remainingScroll;
				duration = l + scrollDuration;
			}
		}

		// song bar scroll
		if (property.isNonAnalogPressed(input, MusicSelectKey.UP, false) || input.getControlKeyState(ControlKeys.DOWN)) {
			long l = System.currentTimeMillis();
			if (duration == 0) {
				keyinput = true;
				mov = 1;
				duration = l + durationlow;
				angle = durationlow;
			}
			if (l > duration && keyinput == true) {
				duration = l + durationhigh;
				mov = 1;
				angle = durationhigh;
			}
		} else if (property.isNonAnalogPressed(input, MusicSelectKey.DOWN, false) || input.getControlKeyState(ControlKeys.UP)) {
			long l = System.currentTimeMillis();
			if (duration == 0) {
				keyinput = true;
				mov = -1;
				duration = l + durationlow;
				angle = -durationlow;
			}
			if (l > duration && keyinput == true) {
				duration = l + durationhigh;
				mov = -1;
				angle = -durationhigh;
			}
		} else {
			keyinput = false;
		}
		long l = System.currentTimeMillis();
		if (l > duration && keyinput == false) {
			duration = 0;
		}
		while(mov > 0) {
			manager.move(true);
			select.stop(SCRATCH);
			select.play(SCRATCH);
			mov--;
		}
		while(mov < 0) {
			manager.move(false);
			select.stop(SCRATCH);
			select.play(SCRATCH);
			mov++;
		}
	}

	public void resetInput() {
		long l = System.currentTimeMillis();
		if (l > duration) {
			duration = 0;
		}
	}

	public void updateBarText() {
		bartextupdate = true;
	}
	
	public void dispose() {
		// favorite書き込み
//		CourseData course = new CourseData();
//		course.setName(favorites[0].getTitle());
//		List<String> l = new ArrayList<>();
//		for(TableData.TableSongData element : favorites[0].getElements()) {
//			l.add(element.getHash());
//		}
//		course.setHash(l.toArray(new String[l.size()]));
//		CourseDataAccessor cda = new CourseDataAccessor("favorite");
//		if(!Files.exists(Paths.get("favorite"))) {
//			try {
//				Files.createDirectory(Paths.get("favorite"));
//			} catch (IOException e) {
//				e.printStackTrace();
//			}
//		}
//		cda.write("default", course);
	}

}
