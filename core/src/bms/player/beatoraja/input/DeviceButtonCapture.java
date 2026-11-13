package bms.player.beatoraja.input;

import java.util.Arrays;

import bms.player.beatoraja.input.BMControllerInputProcessor.BMKeys;
import com.badlogic.gdx.controllers.Controller;

/** キー割り当てとは独立した、コントローラー選択専用のスナップショット入力キャプチャ。 */
final class DeviceButtonCapture {
	private final boolean[] previous = new boolean[BMKeys.HAT1_BOTTOM + 1];
	private final boolean[] current = new boolean[BMKeys.HAT1_BOTTOM + 1];
	private boolean baseline = true;
	private int pressed = -1;

	void reset() {
		baseline = true;
		pressed = -1;
	}

	void sample(Controller controller) {
		Arrays.fill(current, false);
		for (int i = 0; i < current.length; i++) {
			current[i] = controller.getButton(i);
			if (!baseline && pressed < 0 && current[i] && !previous[i]) pressed = i;
			previous[i] = current[i];
		}
		baseline = false;
	}

	int take() {
		int result = pressed;
		pressed = -1;
		return result;
	}

	boolean anyDown() {
		for (boolean down : current) if (down) return true;
		return false;
	}
}

