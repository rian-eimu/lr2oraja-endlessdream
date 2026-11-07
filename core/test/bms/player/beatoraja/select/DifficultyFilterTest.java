package bms.player.beatoraja.select;

import bms.player.beatoraja.PlayerConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DifficultyFilterTest {

    private PlayerConfig config;

    @BeforeEach
    void setUp() {
        config = new PlayerConfig();
    }

    private int nextDifficulty(int current, boolean forward) {
        return (current + (forward ? 1 : 5)) % 6;
    }

    @Test
    @DisplayName("順送り時に 0(ALL) -> 1(BEG) -> 2(NOR) -> 3(HYP) -> 4(ANO) -> 5(INS) -> 0(ALL) と循環すること")
    void testForwardCycle() {
        config.setDifficultyFilter(0);

        int[] expected = {1, 2, 3, 4, 5, 0};
        for (int exp : expected) {
            config.setDifficultyFilter(nextDifficulty(config.getDifficultyFilter(), true));
            assertEquals(exp, config.getDifficultyFilter());
        }
    }

    @Test
    @DisplayName("逆送り時に 0(ALL) -> 5(INS) -> 4(ANO) -> 3(HYP) -> 2(NOR) -> 1(BEG) -> 0(ALL) と逆順循環すること")
    void testBackwardCycle() {
        config.setDifficultyFilter(0);

        int[] expected = {5, 4, 3, 2, 1, 0};
        for (int exp : expected) {
            config.setDifficultyFilter(nextDifficulty(config.getDifficultyFilter(), false));
            assertEquals(exp, config.getDifficultyFilter());
        }
    }

    @Test
    @DisplayName("順送りと逆送りを途中で切り替えても正しく前後の難易度フィルターに遷移すること")
    void testBidirectionalNavigation() {
        config.setDifficultyFilter(0);

        // 0 -> 1 (forward)
        config.setDifficultyFilter(nextDifficulty(config.getDifficultyFilter(), true));
        assertEquals(1, config.getDifficultyFilter());

        // 1 -> 2 (forward)
        config.setDifficultyFilter(nextDifficulty(config.getDifficultyFilter(), true));
        assertEquals(2, config.getDifficultyFilter());

        // 2 -> 1 (backward)
        config.setDifficultyFilter(nextDifficulty(config.getDifficultyFilter(), false));
        assertEquals(1, config.getDifficultyFilter());

        // 1 -> 0 (backward)
        config.setDifficultyFilter(nextDifficulty(config.getDifficultyFilter(), false));
        assertEquals(0, config.getDifficultyFilter());

        // 0 -> 5 (backward)
        config.setDifficultyFilter(nextDifficulty(config.getDifficultyFilter(), false));
        assertEquals(5, config.getDifficultyFilter());

        // 5 -> 0 (forward)
        config.setDifficultyFilter(nextDifficulty(config.getDifficultyFilter(), true));
        assertEquals(0, config.getDifficultyFilter());
    }
}
