package bms.player.beatoraja.skin.property;

import bms.player.beatoraja.*;
import bms.player.beatoraja.skin.SkinProperty;
import bms.player.beatoraja.MainController.IRStatus;
import bms.player.beatoraja.config.KeyConfiguration;
import bms.player.beatoraja.config.SkinConfiguration;
import bms.player.beatoraja.decide.MusicDecide;
import bms.player.beatoraja.ir.IRScoreData;
import bms.player.beatoraja.ir.RankingData;
import bms.player.beatoraja.play.BMSPlayer;
import bms.player.beatoraja.play.TargetProperty;
import bms.player.beatoraja.result.AbstractResult;
import bms.player.beatoraja.result.CourseResult;
import bms.player.beatoraja.result.MusicResult;
import bms.player.beatoraja.select.MusicSelector;
import bms.player.beatoraja.select.bar.*;
import bms.player.beatoraja.song.SongData;
import bms.player.beatoraja.song.SongDatabaseAccessor;
import com.badlogic.gdx.utils.IntMap;

import java.math.BigDecimal;
import java.util.*;
import java.util.function.IntFunction;

/**
 * StringProperty/StringWriterのFactoryクラス
 * 
 * @author exch
 */
public class StringPropertyFactory {

	/**
	 * property IDに対応するStringPropertyを返す
	 * 
	 * @param id property ID
	 * @return 対応するStringProperty
	 */
	public static StringProperty getStringProperty(final int id) {
		StringProperty property = StringPropertyPattern.get(id);
		if (property != null) {
			return property;
		}
		StringType type = StringType.get(id);
		return type != null ? type.property : null;
	}
	
	/**
	 * property nameに対応するStringPropertyを返す
	 * 
	 * @param name property name
	 * @return 対応するStringProperty
	 */
	public static StringProperty getStringProperty(final String name) {
		StringProperty property = StringPropertyPattern.get(name);
		if (property != null) {
			return property;
		}
		for(StringType t : StringType.VALUES) {
			if(t.name().equals(name)) {
				return t.property;
			}
		}
		return null;
	}

	/**
	 * property IDに対応するStringWriterを返す
	 * 
	 * @param id property ID
	 * @return 対応するStringWriter
	 */
	public static StringWriter getStringWriter(final int id) {
		StringType type = StringType.get(id);
		return type != null ? type.writer : null;
	}
	
	/**
	 * property nameに対応するStringWriterを返す
	 * 
	 * @param name property name
	 * @return 対応するStringWriter
	 */
	public static StringWriter getStringWriter(final String name) {
		for(StringType t : StringType.VALUES) {
			if(t.name().equals(name)) {
				return t.writer;
			}
		}
		return null;
	}

	private static SongData selectedReviewChart(MainState state) {
		if (state instanceof MusicResult result) {
			var review = result.getEditableLevelReview();
			return review != null ? review.song() : null;
		}
		return state instanceof MusicSelector selector && selector.getSelectedBar() instanceof SongBar bar
				&& bar.existsSong() && !bar.getSongData().getSha256().isEmpty() ? bar.getSongData() : null;
	}

	private static void writeReviewLevel(MainController main, SongDatabaseAccessor database, SongData song, int lnmode, String input) {
		if (input == null) return;
		String value = input.strip();
		if (value.length() > 1024) return;
		try {
			BigDecimal decimal = value.isEmpty() ? BigDecimal.ZERO : new BigDecimal(value);
			double level = decimal.doubleValue();
			if (decimal.signum() >= 0 && Double.isFinite(level) && (level != 0 || decimal.signum() == 0)
					&& level != song.getSongReview().getLevelreview()
					&& database.setSongReviewLevel(song, level) && main != null) {
				main.syncReviewLevel(song.getSha256(), level, lnmode);
			}
		} catch (NumberFormatException ignored) {
		}
	}

	private static final class ReviewLevelWriter implements StringWriter {
		@Override
		public void set(MainState state, String value) {
			StringWriter bound = bind(state);
			if (bound != null) bound.set(state, value);
		}

		@Override
		public StringWriter bind(MainState state) {
			if (state instanceof MusicResult result) {
				var target = result.getEditableLevelReview();
				if (target == null) return null;
				MainController main = result.main;
				return (owner, value) -> writeReviewLevel(main, main.getSongDatabase(), target.song(), target.lnmode(), value);
			}
			SongData song = selectedReviewChart(state);
			if (song == null) return null;
			SongDatabaseAccessor database = ((MusicSelector) state).getSongDatabase();
			if (database == null) return null;
			MainController main = state.main;
			int lnmode = main != null ? main.getReviewLnMode(song) : 0;
			return (owner, value) -> writeReviewLevel(main, database, song, lnmode, value);
		}
	}

	/**
	 * Groups numbered String properties by their ID range and skin-facing name pattern.
	 */
	private enum StringPropertyPattern {
		KEY(keyIds(), numberedNames("key", 54), StringType::createKeyname),
		SKIN_CATEGORY(numberedIds(100, 10), numberedNames("skincategory", 10), StringType::createSkincategory),
		SKIN_ITEM(numberedIds(110, 10), numberedNames("skinitem", 10), StringType::createSkinitem),
		RANKING_NAME(numberedIds(120, 10), numberedNames("rankingname", 10), StringType::createRankingname),
		COURSE_TITLE(numberedIds(150, 10), numberedNames("coursetitle", 10), StringType::createCoursetitle),
		TARGET_NAME_P(numberedIds(200, 10), targetNames("targetnamep", 10, true), index -> StringType.createTargetname(index - 10)),
		TARGET_NAME_N(numberedIds(210, 10), targetNames("targetnamen", 10, false), index -> StringType.createTargetname(index + 1)),
		PRACTICE_ITEM(numberedIds(SkinProperty.STRING_PRACTICE_ITEM1, 16), numberedNames("practice_item", 16), StringType::createPracticeItem),
		PRACTICE_ITEM_LABEL(numberedIds(SkinProperty.STRING_PRACTICE_ITEM_LABEL1, 16), numberedNames("practice_item_label", 16), StringType::createPracticeItemLabel),
		PRACTICE_ITEM_VALUE(numberedIds(SkinProperty.STRING_PRACTICE_ITEM_VALUE1, 16), numberedNames("practice_item_value", 16), StringType::createPracticeItemValue);

		private final int[] ids;
		private final String[] names;
		private final StringProperty[] properties;

		StringPropertyPattern(int[] ids, String[] names, IntFunction<StringProperty> propertyFactory) {
			this.ids = ids;
			this.names = names;
			this.properties = new StringProperty[ids.length];
			for (int index = 0; index < ids.length; index++) {
				properties[index] = propertyFactory.apply(index);
			}
		}

		private StringProperty getById(int id) {
			for (int index = 0; index < ids.length; index++) {
				if (ids[index] == id) {
					return properties[index];
				}
			}
			return null;
		}

		private StringProperty getByName(String name) {
			if (name != null) {
				for (int index = 0; index < names.length; index++) {
					if (names[index].equals(name)) {
						return properties[index];
					}
				}
				if (this == PRACTICE_ITEM_LABEL && name.startsWith("practice_item") && name.endsWith("_label")) {
					try {
						int idx = Integer.parseInt(name.substring("practice_item".length(), name.length() - "_label".length())) - 1;
						if (idx >= 0 && idx < properties.length) {
							return properties[idx];
						}
					} catch (NumberFormatException ignored) {
					}
				} else if (this == PRACTICE_ITEM_VALUE && name.startsWith("practice_item") && name.endsWith("_value")) {
					try {
						int idx = Integer.parseInt(name.substring("practice_item".length(), name.length() - "_value".length())) - 1;
						if (idx >= 0 && idx < properties.length) {
							return properties[idx];
						}
					} catch (NumberFormatException ignored) {
					}
				}
			}
			return null;
		}

		private static StringProperty get(int id) {
			for (StringPropertyPattern pattern : values()) {
				StringProperty property = pattern.getById(id);
				if (property != null) {
					return property;
				}
			}
			return null;
		}

		private static StringProperty get(String name) {
			for (StringPropertyPattern pattern : values()) {
				StringProperty property = pattern.getByName(name);
				if (property != null) {
					return property;
				}
			}
			return null;
		}

		private static int[] numberedIds(int firstId, int count) {
			int[] ids = new int[count];
			for (int index = 0; index < count; index++) {
				ids[index] = firstId + index;
			}
			return ids;
		}

		private static int[] keyIds() {
			int[] ids = new int[54];
			for (int index = 0; index < 10; index++) {
				ids[index] = 40 + index;
			}
			for (int index = 10; index < ids.length; index++) {
				ids[index] = 230 + index;
			}
			return ids;
		}

		private static String[] numberedNames(String prefix, int count) {
			String[] names = new String[count];
			for (int index = 0; index < count; index++) {
				names[index] = prefix + (index + 1);
			}
			return names;
		}

		private static String[] targetNames(String prefix, int count, boolean descending) {
			String[] names = new String[count];
			for (int index = 0; index < count; index++) {
				names[index] = prefix + (descending ? count - index : index + 1);
			}
			return names;
		}
	}
	
	public enum StringType {
		
		rival(1, (state) -> {
			if (state instanceof MusicSelector selector) {
				final PlayerInformation rival = selector.getRival();
				return rival != null ? rival.getName() : "";
			} else {
				final ScoreData rival = state.resource.getTargetScoreData();
				return rival != null ? rival.getPlayer() : "";
			}
		}),
		player(2, (state) -> (state.resource.getPlayerConfig().getName())),
		target(3, (state) -> {
			if (state instanceof MusicSelector) {
				return TargetProperty.getTargetName(state.resource.getPlayerConfig().getTargetid());					
			} else {
				final ScoreData target = state.resource.getTargetScoreData();
				return target != null ? target.getPlayer() : "";
			}
		}),
		title(10, (state) -> {
			if (state instanceof MusicSelector selector && selector.getSelectedBar() instanceof DirectoryBar) {
				return selector.getSelectedBar().getTitle();
			} else if ((state instanceof MusicDecide || state instanceof CourseResult) && state.resource.getCoursetitle() != null) {
				return state.resource.getCoursetitle();						
			} else if (state instanceof MusicSelector selector && selector.getSelectedBar() instanceof FunctionBar) {
				return ((FunctionBar) selector.getSelectedBar()).getTitle();
			}
			final SongData song = state.resource.getSongdata();
			if (song != null && (state instanceof AbstractResult || state instanceof BMSPlayer) && state.resource.isFreqOn()) {
				return state.resource.getFreqString() + " " + song.getTitle();
			}
			return song != null ? song.getTitle() : "";
		}),
		subtitle(11, (state) -> {
			if (state instanceof MusicSelector selector && selector.getSelectedBar() instanceof FunctionBar) {
				return ((FunctionBar) selector.getSelectedBar()).getSubtitle();
			}
			final SongData song = state.resource.getSongdata();
			return song != null ? song.getSubtitle() : "";
		}),
		fulltitle(12, (state) -> {
			if (state instanceof MusicSelector selector && selector.getSelectedBar() instanceof DirectoryBar) {
				return selector.getSelectedBar().getTitle();
			} else if ((state instanceof MusicDecide || state instanceof CourseResult) && state.resource.getCoursetitle() != null) {
				return state.resource.getCoursetitle();
			} else if (state instanceof MusicSelector selector && selector.getSelectedBar() instanceof FunctionBar) {
				return ((FunctionBar) selector.getSelectedBar()).getTitle();
			}
			final SongData song = state.resource.getSongdata();
			if (song != null && (state instanceof AbstractResult || state instanceof BMSPlayer) && state.resource.isFreqOn()) {
				return state.resource.getFreqString() + " " + song.getFullTitle();
			}
			return song != null ? song.getFullTitle() : "";
		}),
		genre(13, (state) -> {
			final SongData song = state.resource.getSongdata();
			return song != null ? song.getGenre() : "";
		}),
		artist(14, (state) -> {
			final SongData song = state.resource.getSongdata();
			return song != null ? song.getArtist() : "";
		}),
		subartist(15, (state) -> {
			final SongData song = state.resource.getSongdata();
			return song != null ? song.getSubartist() : "";
		}),
		fullartist(16, (state) -> {
			final SongData song = state.resource.getSongdata();
			return song != null ? song.getFullArtist() : "";
		}),
		levelreview(SkinProperty.STRING_LEVELREVIEW, state -> {
			SongData song = selectedReviewChart(state);
			return song != null ? song.getSongReview().getLevelreviewText() : "";
		}, new ReviewLevelWriter()),
		searchword(30, (state) -> "", (state, value) -> {
			if (state instanceof MusicSelector selector) {
				selector.search(value);
			}
		}),
		sort(61, (state) -> state.resource.getPlayerConfig().getSortid()),
		mode(62, (state) -> state.resource.getPlayerConfig().getModeFilter().name),

		chartreplication(86, (state) -> state.resource.getPlayerConfig().getChartReplicationMode()),

		skinname(50, (state) -> {
			if (state instanceof SkinConfiguration skinconfig) {
				return skinconfig.getSelectedSkinHeader() != null ? skinconfig.getSelectedSkinHeader().getName() : "";
			} else if(state.getSkin() != null && state.getSkin().header != null) {
				return state.getSkin().header.getName();
			}
			return "";
		}),
		skinauthor(51, (state) -> {
			if (state instanceof SkinConfiguration skinconfig) {
				return skinconfig.getSelectedSkinHeader() != null ? skinconfig.getSelectedSkinHeader().getAuthor() : "";
			} else if(state.getSkin() != null && state.getSkin().header != null) {
				return state.getSkin().header.getAuthor();
			}
			return "";
		}),

		directory(1000, (state) -> ((state instanceof MusicSelector selector) ? selector.getBarManager().getDirectoryString() : "")),
		tablename(1001, (state) -> (state.resource.getTablename())),
		tablelevel(1002, (state) -> (state.resource.getTablelevel())),
		tablefull(1003, (state) -> (state.resource.getTableFullname())),
		version(1010, (state) -> (state.main.getVersion())),
		irname(1020, (state) -> {
			final IRConfig[] irconfig = state.resource.getPlayerConfig().getIrconfig();
			if (irconfig.length > 0) {
				return irconfig[0].getIrname();
			}
			return "";
		}),
		irUserName(1021, (state) -> {
			final IRStatus[] ir = state.main.getIRStatus();
			if (ir.length > 0) {
				return ir[0].player.name;
			}
			return "";
		}),
		songhashmd5(1030, (state) -> {
			final SongData song = state.resource.getSongdata();
			return song != null ? song.getMd5() : "";
		}),
		songhashsha256(1031, (state) -> {
			final SongData song = state.resource.getSongdata();
			return song != null ? song.getSha256() : "";
		}),
		;
		
		/**
		 * property ID
		 */
		private final int id;
		/**
		 * StringProperty
		 */
		private final StringProperty property;
		/**
		 * StringWriter
		 */
		private final StringWriter writer;

		public static final List<StringType> VALUES = Collections.unmodifiableList(Arrays.asList(StringType.values()));

		private static final IntMap<StringType> ID_MAP;

		static {
			ID_MAP = new IntMap<>(VALUES.size());
			for (StringType type : VALUES) {
				ID_MAP.put(type.id, type);
			}
		}

		public static StringType get(int id) {
			return ID_MAP.get(id);
		}
		
		private StringType(int id, StringProperty property) {
			this(id, property, null);
		}

		private StringType(int id, StringProperty property, StringWriter writer) {
			this.id = id;
			this.property = property;
			this.writer = writer;
		}
		
		private static StringProperty createSkincategory(final int index) {
			return (state) -> {
				if (state instanceof SkinConfiguration) {
					return ((SkinConfiguration)state).getCategoryName(index);
				}
				return "";
			};
		}
		
		private static StringProperty createSkinitem(final int index) {
			return (state) -> {
				if (state instanceof SkinConfiguration) {
					return ((SkinConfiguration)state).getDisplayValue(index);
				}
				return "";
			};
		}
		
		private static StringProperty createRankingname(final int index) {
			return (state) -> {
				RankingData irc = null;
				int rankingOffset = 0;
				if (state instanceof MusicSelector selector) {
					irc = selector.getCurrentRankingData();
					rankingOffset = selector.getRankingOffset();
				}
				if (state instanceof AbstractResult result) {
					irc = result.getRankingData();
					rankingOffset = result.getRankingOffset();
				}
				IRScoreData score = irc != null ? irc.getScore(index + rankingOffset) : null;
				String name = "YOU";
				IRStatus[] status = state.main.getIRStatus();
				if(status != null && status.length > 0 && status[0] != null && status[0].player != null) {
					name = status[0].player.name;
				}
				return score != null ? (score.player.length() > 0 ? score.player : name) : "";
			};

		}
		
		private static StringProperty createTargetname(final int index) {
			return (state) -> {
				String[] targets = TargetProperty.getTargets();
				int id = -1;
				for(int i = 0;i < targets.length;i++) {
					if(targets[i].equals(state.resource.getPlayerConfig().getTargetid())) {
						id = i;
						break;
					}
				}
				int offset = index >= 0 ? index : (targets.length + index);
				return id >= 0 ? TargetProperty.getTargetName(targets[(id + offset) % targets.length]) : "";
			};
		}
		
		private static StringProperty createCoursetitle(final int index) {
			return (state) -> {
				if (state instanceof MusicSelector selector) {
					final Bar bar = selector.getSelectedBar();
					if (bar instanceof GradeBar coursebar) {
						if (coursebar.getSongDatas().length > index) {
							SongData song = coursebar.getSongDatas()[index];
							final String songname = song != null && song.getTitle() != null ? song.getTitle()
									: "----";
							return song != null && song.getPath() != null ? songname : "(no song) " + songname;
						}
					} else if (bar instanceof RandomCourseBar randomcoursebar) {
						if (randomcoursebar.getCourseData().getStage().length > index) {
							RandomStageData stage = randomcoursebar.getCourseData().getStage()[index];
							final String stagename = stage != null && stage.getTitle() != null ? stage.getTitle()
									: "----";
							return stage != null ? stagename : "(no song) " + stagename;
						}
					}
				} else {
					CourseData course = state.resource.getCourseData();
					if (course != null && course.getSong().length > index && course.getSong()[index] != null) {
						return course.getSong()[index].getTitle();
					}
				}
				return "";
			};
		}
		
		private static StringProperty createKeyname(final int index) {
			return (state) -> {
				if (state instanceof KeyConfiguration keyconfig) {
					return keyconfig.getKeyAssign(index);
				}
				return "";
			};
		}

		private static StringProperty createPracticeItem(final int index) {
			return (state) -> (state instanceof BMSPlayer player)
					? player.getPracticeConfiguration().getVisibleItemText(index)
					: "";
		}

		private static StringProperty createPracticeItemLabel(final int index) {
			return (state) -> {
				if (state instanceof BMSPlayer player) {
					return player.getPracticeConfiguration().getVisibleItemLabel(index);
				}
				// STRING_MODE_FILTER (1060) との後方互換維持
				if (index == 0 && state.resource != null && state.resource.getPlayerConfig() != null) {
					return state.resource.getPlayerConfig().getModeFilter().name;
				}
				return "";
			};
		}

		private static StringProperty createPracticeItemValue(final int index) {
			return (state) -> (state instanceof BMSPlayer player)
					? player.getPracticeConfiguration().getVisibleItemValue(index)
					: "";
		}
	}
}
