package bms.player.beatoraja.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * キーコンフィグ画面でのプレイモード並び順の単体テスト
 */
class KeyConfigurationModeOrderTest {

    @Test
    @DisplayName("1P/2P独立設定が無効のときは標準の7モード順になること")
    void testStandardModeOrder() {
        int[] actual = new int[7];
        for (int slot = 0; slot < actual.length; slot++) {
            actual[slot] = KeyConfiguration.modeIdAt(slot, false);
        }
        assertArrayEquals(new int[]{0, 1, 2, 3, 4, 5, 6}, actual);
    }

    @Test
    @DisplayName("1P/2P独立設定が有効のときは各1Pモードの直後に2Pモードが配置されること")
    void testIndependentSidesModeOrder() {
        int[] actual = new int[10];
        for (int slot = 0; slot < actual.length; slot++) {
            actual[slot] = KeyConfiguration.modeIdAt(slot, true);
        }
        // 5K(1P)->5K(2P)->7K(1P)->7K(2P)->9K->10K->14K->24K(1P)->24K(2P)->24K DP
        assertArrayEquals(new int[]{0, 7, 1, 8, 2, 3, 4, 5, 12, 6}, actual);
    }
}
