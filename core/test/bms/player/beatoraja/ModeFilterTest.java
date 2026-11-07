package bms.player.beatoraja;

import bms.model.Mode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ModeFilter の単体テスト
 */
class ModeFilterTest {

    @Test
    void testMatchAll() {
        ModeFilter filter = ModeFilter.ALL;
        assertTrue(filter.match(0));
        assertTrue(filter.match(5));
        assertTrue(filter.match(7));
        assertTrue(filter.match(9));
        assertTrue(filter.match(10));
        assertTrue(filter.match(14));
        assertTrue(filter.match(25));
        assertTrue(filter.match(50));
    }

    @Test
    void testMatch5k7k() {
        ModeFilter filter = ModeFilter.BEAT_5K_7K;
        assertTrue(filter.match(0));
        assertTrue(filter.match(5));
        assertTrue(filter.match(7));
        assertFalse(filter.match(9));
        assertFalse(filter.match(10));
        assertFalse(filter.match(14));
        assertFalse(filter.match(25));
    }

    @Test
    void testMatch10k14k() {
        ModeFilter filter = ModeFilter.BEAT_10K_14K;
        assertTrue(filter.match(0));
        assertFalse(filter.match(5));
        assertFalse(filter.match(7));
        assertFalse(filter.match(9));
        assertTrue(filter.match(10));
        assertTrue(filter.match(14));
        assertFalse(filter.match(25));
    }

    @Test
    void testMatch7k14k() {
        ModeFilter filter = ModeFilter.BEAT_7K_14K;
        assertTrue(filter.match(0));
        assertFalse(filter.match(5));
        assertTrue(filter.match(7));
        assertFalse(filter.match(9));
        assertFalse(filter.match(10));
        assertTrue(filter.match(14));
        assertFalse(filter.match(25));
    }

    @Test
    void testMatch5k10k() {
        ModeFilter filter = ModeFilter.BEAT_5K_10K;
        assertTrue(filter.match(0));
        assertTrue(filter.match(5));
        assertFalse(filter.match(7));
        assertFalse(filter.match(9));
        assertTrue(filter.match(10));
        assertFalse(filter.match(14));
        assertFalse(filter.match(25));
    }

    @Test
    void testMatchBeatKeyboardAll() {
        ModeFilter filter = ModeFilter.BEAT_KEYBOARD_ALL;
        assertTrue(filter.match(0));
        assertTrue(filter.match(5));
        assertTrue(filter.match(7));
        assertFalse(filter.match(9));
        assertTrue(filter.match(10));
        assertTrue(filter.match(14));
        assertFalse(filter.match(25));
        assertFalse(filter.match(50));
    }

    @Test
    void testSkinValues() {
        assertEquals(0, ModeFilter.ALL.skinValue);
        assertEquals(1, ModeFilter.BEAT_5K.skinValue);
        assertEquals(2, ModeFilter.BEAT_7K.skinValue);
        assertEquals(3, ModeFilter.BEAT_10K.skinValue);
        assertEquals(4, ModeFilter.BEAT_14K.skinValue);
        assertEquals(5, ModeFilter.POPN_9K.skinValue);
        assertEquals(6, ModeFilter.KEYBOARD_24K.skinValue);
        assertEquals(7, ModeFilter.KEYBOARD_24K_DOUBLE.skinValue);
        assertEquals(8, ModeFilter.BEAT_5K_7K.skinValue);
        assertEquals(9, ModeFilter.BEAT_10K_14K.skinValue);
        assertEquals(10, ModeFilter.BEAT_7K_14K.skinValue);
        assertEquals(11, ModeFilter.BEAT_5K_10K.skinValue);
        assertEquals(12, ModeFilter.BEAT_KEYBOARD_ALL.skinValue);
    }

    @Test
    void testFromMode() {
        assertEquals(ModeFilter.ALL, ModeFilter.fromMode(null));
        assertEquals(ModeFilter.BEAT_7K, ModeFilter.fromMode(Mode.BEAT_7K));
        assertEquals(ModeFilter.BEAT_5K, ModeFilter.fromMode(Mode.BEAT_5K));
        assertEquals(ModeFilter.BEAT_14K, ModeFilter.fromMode(Mode.BEAT_14K));
        assertEquals(ModeFilter.BEAT_10K, ModeFilter.fromMode(Mode.BEAT_10K));
        assertEquals(ModeFilter.POPN_9K, ModeFilter.fromMode(Mode.POPN_9K));
    }

    @Test
    void testPlayerConfigSync() {
        PlayerConfig config = new PlayerConfig();

        // setModeFilter
        config.setModeFilter(ModeFilter.BEAT_5K_7K);
        assertEquals(ModeFilter.BEAT_5K_7K, config.getModeFilter());
        assertEquals(Mode.BEAT_7K, config.getMode());

        config.setModeFilter(ModeFilter.BEAT_5K_10K);
        assertEquals(ModeFilter.BEAT_5K_10K, config.getModeFilter());
        assertEquals(Mode.BEAT_5K, config.getMode());

        // setMode (旧API互換)
        config.setMode(Mode.BEAT_14K);
        assertEquals(ModeFilter.BEAT_14K, config.getModeFilter());
        assertEquals(Mode.BEAT_14K, config.getMode());

        config.setMode(null);
        assertEquals(ModeFilter.ALL, config.getModeFilter());
        assertNull(config.getMode());
    }
}
