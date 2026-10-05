package bms.player.beatoraja;

import bms.model.Mode;

/**
 * 選曲画面におけるモードフィルター（KEYフィルター）
 */
public enum ModeFilter {

	ALL("ALL KEYS", null, null, 0),
	BEAT_7K("7KEYS", Mode.BEAT_7K, new Mode[] { Mode.BEAT_7K }, 2),
	BEAT_14K("14KEYS", Mode.BEAT_14K, new Mode[] { Mode.BEAT_14K }, 4),
	POPN_9K("9KEYS", Mode.POPN_9K, new Mode[] { Mode.POPN_9K }, 5),
	BEAT_5K("5KEYS", Mode.BEAT_5K, new Mode[] { Mode.BEAT_5K }, 1),
	BEAT_10K("10KEYS", Mode.BEAT_10K, new Mode[] { Mode.BEAT_10K }, 3),
	KEYBOARD_24K("24KEYS", Mode.KEYBOARD_24K, new Mode[] { Mode.KEYBOARD_24K }, 6),
	KEYBOARD_24K_DOUBLE("24KEYS DOUBLE", Mode.KEYBOARD_24K_DOUBLE, new Mode[] { Mode.KEYBOARD_24K_DOUBLE }, 7),
	BEAT_5K_7K("5KEY & 7KEY", Mode.BEAT_7K, new Mode[] { Mode.BEAT_5K, Mode.BEAT_7K }, 8),
	BEAT_10K_14K("10KEY & 14KEY", Mode.BEAT_14K, new Mode[] { Mode.BEAT_10K, Mode.BEAT_14K }, 9),
	BEAT_7K_14K("7KEY & 14KEY", Mode.BEAT_7K, new Mode[] { Mode.BEAT_7K, Mode.BEAT_14K }, 10),
	BEAT_5K_10K("5KEY & 10KEY", Mode.BEAT_5K, new Mode[] { Mode.BEAT_5K, Mode.BEAT_10K }, 11),
	BEAT_KEYBOARD_ALL("5KEY & 7KEY & 10KEY & 14KEY", Mode.BEAT_7K, new Mode[] { Mode.BEAT_5K, Mode.BEAT_7K, Mode.BEAT_10K, Mode.BEAT_14K }, 12);

	/**
	 * モードフィルター表示名
	 */
	public final String name;

	/**
	 * 代表プレイモード（PlayConfig取得等で使用）
	 */
	public final Mode defaultMode;

	/**
	 * フィルター対象のMode配列（nullの場合は全モード対象）
	 */
	public final Mode[] modes;

	/**
	 * スキン用インデックス値（IntegerProperty 11）
	 */
	public final int skinValue;

	private ModeFilter(String name, Mode defaultMode, Mode[] modes, int skinValue) {
		this.name = name;
		this.defaultMode = defaultMode;
		this.modes = modes;
		this.skinValue = skinValue;
	}

	/**
	 * 指定された楽曲のモードIDが本フィルターに合致するか判定
	 *
	 * @param songMode 判定対象のモードID
	 * @return 合致する場合または全対象の場合はtrue
	 */
	public boolean match(int songMode) {
		if (modes == null || songMode == 0) {
			return true;
		}
		for (Mode m : modes) {
			if (m.id == songMode) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Modeから対応するModeFilterを取得（後方互換性用）
	 *
	 * @param mode 対象Mode
	 * @return 対応するModeFilter
	 */
	public static ModeFilter fromMode(Mode mode) {
		if (mode == null) {
			return ALL;
		}
		for (ModeFilter filter : values()) {
			if (filter.modes != null && filter.modes.length == 1 && filter.modes[0] == mode) {
				return filter;
			}
		}
		return ALL;
	}

	/**
	 * スキンインデックス値からModeFilterを取得
	 *
	 * @param skinValue スキンインデックス値
	 * @return 対応するModeFilter
	 */
	public static ModeFilter fromSkinValue(int skinValue) {
		for (ModeFilter filter : values()) {
			if (filter.skinValue == skinValue) {
				return filter;
			}
		}
		return ALL;
	}
}
