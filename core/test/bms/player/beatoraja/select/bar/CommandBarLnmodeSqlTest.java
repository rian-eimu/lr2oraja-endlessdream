package bms.player.beatoraja.select.bar;

import bms.model.Mode;
import bms.player.beatoraja.PlayerConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CommandBar が生成する KEY モード毎の LN MODE 条件式の単体テスト
 */
class CommandBarLnmodeSqlTest {

    @Test
    void sqlExpressionSwitchesLnmodeBySongMode() {
        PlayerConfig config = new PlayerConfig();
        config.validate();
        config.setLnmode(Mode.BEAT_5K, 3);
        config.setLnmode(Mode.BEAT_7K, 1);
        config.setLnmode(Mode.BEAT_14K, 2);

        String sql = CommandBar.lnmodeSqlExpression(config);

        assertTrue(sql.startsWith("(CASE song.mode"), sql);
        assertTrue(sql.contains(" WHEN 5 THEN 3"), sql);
        assertTrue(sql.contains(" WHEN 7 THEN 1"), sql);
        assertTrue(sql.contains(" WHEN 10 THEN 0"), sql);
        assertTrue(sql.contains(" WHEN 14 THEN 2"), sql);
        assertTrue(sql.contains(" WHEN 9 THEN 0"), sql);
        assertTrue(sql.contains(" WHEN 25 THEN 0"), sql);
        assertTrue(sql.contains(" WHEN 50 THEN 0"), sql);
        // モードID 9 (POPN_5K/POPN_9K) は 1 回だけ
        assertEquals(sql.indexOf(" WHEN 9 "), sql.lastIndexOf(" WHEN 9 "), sql);
        assertTrue(sql.endsWith(" ELSE 1 END)"), sql);
    }
}
