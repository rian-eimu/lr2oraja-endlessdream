package bms.table;

import bms.player.beatoraja.TableData;
import bms.player.beatoraja.TableDataAccessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class DifficultyTableParserTest {

    @Test
    void testDecodeJsonUrlDirectly(@TempDir Path tempDir) throws Exception {
        // .json で終わる難易度表URL（sourceURLが空でheadURLのみ設定されるケース）の読み込み検証
        Path headerFile = tempDir.resolve("header.json");
        Path dataFile = tempDir.resolve("score.json");

        Files.writeString(headerFile, """
                {
                  "name": "Test JSON Table",
                  "symbol": "★",
                  "data_url": "score.json",
                  "level_order": ["1", "2"]
                }
                """, StandardCharsets.UTF_8);

        Files.writeString(dataFile, """
                [
                  {
                    "level": "1",
                    "title": "Song 1",
                    "artist": "Artist 1",
                    "md5": "0123456789abcdef0123456789abcdef"
                  },
                  {
                    "level": "2",
                    "title": "Song 2",
                    "artist": "Artist 2",
                    "sha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
                  }
                ]
                """, StandardCharsets.UTF_8);

        String jsonUrl = headerFile.toUri().toURL().toExternalForm();
        assertTrue(jsonUrl.endsWith(".json"));

        TableDataAccessor.DifficultyTableAccessor accessor =
                new TableDataAccessor.DifficultyTableAccessor(tempDir.toString(), jsonUrl);
        TableData td = accessor.read();

        assertNotNull(td, ".jsonで終わる難易度表URLが正常に読み込めること");
        assertEquals("Test JSON Table", td.getName());
        assertEquals("★", td.getTag());
        assertEquals(2, td.getFolder().length);
        assertEquals(1, td.getFolder()[0].getSong().length);
        assertEquals("Song 1", td.getFolder()[0].getSong()[0].getTitle());
        assertEquals(1, td.getFolder()[1].getSong().length);
        assertEquals("Song 2", td.getFolder()[1].getSong()[0].getTitle());
    }
}

