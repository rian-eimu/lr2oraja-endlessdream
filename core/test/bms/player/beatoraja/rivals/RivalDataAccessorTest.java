package bms.player.beatoraja.rivals;

import bms.player.beatoraja.PlayerInformation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RivalDataAccessorTest {

    private RivalDataAccessor accessor;

    @BeforeEach
    void setUp() {
        accessor = new RivalDataAccessor();
    }

    private PlayerInformation createPlayer(String id, String name) {
        PlayerInformation player = new PlayerInformation();
        player.setId(id);
        player.setName(name);
        return player;
    }

    @Test
    @DisplayName("ライバルが存在しない場合、順送り・逆送りともに常にnullが返ること")
    void testEmptyRivals() {
        assertNull(accessor.nextRival());
        assertFalse(accessor.isRivalSelected());

        assertNull(accessor.previousRival());
        assertFalse(accessor.isRivalSelected());
    }

    @Test
    @DisplayName("ライバルが1人の場合、順送り・逆送りともに「ライバル1 -> null」の順でトグルすること")
    void testSingleRival() {
        var r1 = createPlayer("1", "Rival 1");
        accessor.addRivalForTest(r1);

        // 順送りの確認
        assertSame(r1, accessor.nextRival());
        assertTrue(accessor.isRivalSelected());

        assertNull(accessor.nextRival());
        assertFalse(accessor.isRivalSelected());

        assertSame(r1, accessor.nextRival());
        assertTrue(accessor.isRivalSelected());

        // 逆送りの確認（未選択状態から末尾へ、先頭から未選択へ）
        assertNull(accessor.nextRival()); // 一旦未選択へ
        assertSame(r1, accessor.previousRival());
        assertTrue(accessor.isRivalSelected());

        assertNull(accessor.previousRival());
        assertFalse(accessor.isRivalSelected());
    }

    @Test
    @DisplayName("ライバルが複数人の場合、nextRivalで「未選択 -> 1 -> 2 -> 3 -> 未選択」と循環すること")
    void testMultipleRivalsNextCycle() {
        var r1 = createPlayer("1", "Rival 1");
        var r2 = createPlayer("2", "Rival 2");
        var r3 = createPlayer("3", "Rival 3");
        accessor.addRivalForTest(r1);
        accessor.addRivalForTest(r2);
        accessor.addRivalForTest(r3);

        assertSame(r1, accessor.nextRival());
        assertSame(r2, accessor.nextRival());
        assertSame(r3, accessor.nextRival());
        assertNull(accessor.nextRival());
        assertSame(r1, accessor.nextRival());
    }

    @Test
    @DisplayName("ライバルが複数人の場合、previousRivalで「未選択 -> 3 -> 2 -> 1 -> 未選択」と逆順循環すること")
    void testMultipleRivalsPreviousCycle() {
        var r1 = createPlayer("1", "Rival 1");
        var r2 = createPlayer("2", "Rival 2");
        var r3 = createPlayer("3", "Rival 3");
        accessor.addRivalForTest(r1);
        accessor.addRivalForTest(r2);
        accessor.addRivalForTest(r3);

        assertSame(r3, accessor.previousRival());
        assertSame(r2, accessor.previousRival());
        assertSame(r1, accessor.previousRival());
        assertNull(accessor.previousRival());
        assertSame(r3, accessor.previousRival());
    }

    @Test
    @DisplayName("順送りと逆送りを交互または途中で切り替えても正しく前後のライバルを選択できること")
    void testBidirectionalNavigation() {
        var r1 = createPlayer("1", "Rival 1");
        var r2 = createPlayer("2", "Rival 2");
        var r3 = createPlayer("3", "Rival 3");
        accessor.addRivalForTest(r1);
        accessor.addRivalForTest(r2);
        accessor.addRivalForTest(r3);

        // 未選択から nextRival で r1
        assertSame(r1, accessor.nextRival());
        // r1 から nextRival で r2
        assertSame(r2, accessor.nextRival());
        // r2 から previousRival で r1
        assertSame(r1, accessor.previousRival());
        // r1 から previousRival で未選択 (null)
        assertNull(accessor.previousRival());
        assertFalse(accessor.isRivalSelected());
        // 未選択から nextRival で r1
        assertSame(r1, accessor.nextRival());
    }
}
