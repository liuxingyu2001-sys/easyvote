package com.easyvote;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.Assert.*;

public class CumulativeConfigTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    private final Logger logger = Logger.getLogger("CumulativeConfigTest");

    @Test public void freshInstallCreatesStandaloneDefaults() {
        var main = new YamlConfiguration();
        var config = CumulativeConfig.load(folder.getRoot(), main, logger);
        assertTrue(new File(folder.getRoot(), "cumulative.yml").isFile());
        assertTrue(config.getBoolean("enabled"));
        assertEquals(3, config.getMapList("milestones").size());
        assertFalse(main.contains("votifier.cumulative"));
        assertFalse(config.contains("votifier"));
    }

    @Test public void migratesCustomRewardsCeDisplayMessagesAndDisabledFlag() throws Exception {
        var main = new YamlConfiguration();
        main.set("votifier.port", 10022);
        main.set("votifier.rewards.vote", List.of("ordinary reward"));
        main.set("votifier.cumulative.enabled", false);
        main.set("votifier.cumulative.gui-title", "自定义标题");
        main.set("votifier.cumulative.messages.available", "自定义提醒");
        main.set("votifier.cumulative.states.received.material", "BOOK");
        var tiers = List.of(Map.of("count", 7, "commands", List.of("ce give %player% custom:item"),
            "display", Map.of("craftengine_model", "custom:item", "lore", List.of("自定义奖励"))));
        main.set("votifier.cumulative.milestones", tiers);
        File mainFile = new File(folder.getRoot(), "config.yml");
        main.save(mainFile);

        var config = CumulativeConfig.load(folder.getRoot(), main, logger);
        assertFalse(config.getBoolean("enabled"));
        assertEquals("自定义标题", config.getString("gui-title"));
        assertEquals("自定义提醒", config.getString("messages.available"));
        assertEquals("BOOK", config.getString("states.received.material"));
        assertEquals(tiers, config.getMapList("milestones"));
        var persisted = new YamlConfiguration();
        persisted.load(new File(folder.getRoot(), "cumulative.yml"));
        assertEquals(tiers, persisted.getMapList("milestones"));
        var migratedMain = new YamlConfiguration();
        migratedMain.load(mainFile);
        assertFalse(migratedMain.contains("votifier.cumulative"));
        assertFalse(main.contains("votifier.cumulative"));
        assertEquals(10022, migratedMain.getInt("votifier.port"));
        assertEquals(List.of("ordinary reward"), migratedMain.getStringList("votifier.rewards.vote"));
    }

    @Test public void existingStandaloneFileWinsAndReloadReadsEdits() throws Exception {
        File file = new File(folder.getRoot(), "cumulative.yml");
        var main = new YamlConfiguration();
        main.set("votifier.cumulative.enabled", false);
        Files.writeString(file.toPath(), "enabled: true\nmilestones: []\ngui-title: first\n");
        var first = CumulativeConfig.load(folder.getRoot(), main, logger);
        assertTrue(first.getBoolean("enabled"));
        assertTrue(first.getMapList("milestones").isEmpty());
        assertEquals("first", first.getString("gui-title"));
        Files.writeString(file.toPath(), "enabled: false\nmilestones: []\ngui-title: second\n");
        var second = CumulativeConfig.load(folder.getRoot(), main, logger);
        assertFalse(second.getBoolean("enabled"));
        assertEquals("second", second.getString("gui-title"));
        assertEquals("first", first.getString("gui-title"));
    }

    @Test public void malformedStandaloneDoesNotOverwriteFileOrConsumeLegacyConfig() throws Exception {
        File file = new File(folder.getRoot(), "cumulative.yml");
        String invalid = "milestones: [broken\n";
        Files.writeString(file.toPath(), invalid);
        var main = new YamlConfiguration();
        main.set("votifier.cumulative.enabled", false);
        assertThrows(IllegalStateException.class, () -> CumulativeConfig.load(folder.getRoot(), main, logger));
        assertEquals(invalid, Files.readString(file.toPath()));
        assertTrue(main.contains("votifier.cumulative"));
    }

    @Test public void emptyLegacyMilestonesStayEmpty() {
        var main = new YamlConfiguration();
        main.set("votifier.cumulative.milestones", List.of());
        var config = CumulativeConfig.load(folder.getRoot(), main, logger);
        assertTrue(config.getMapList("milestones").isEmpty());
    }

    @Test public void failedDestinationLeavesLegacyConfigurationIntact() throws Exception {
        folder.newFolder("cumulative.yml");
        var main = new YamlConfiguration();
        main.set("votifier.cumulative.milestones", List.of(Map.of("count", 7, "commands", List.of("custom"))));
        File file = new File(folder.getRoot(), "config.yml");
        main.save(file);
        String original = Files.readString(file.toPath());
        assertThrows(IllegalStateException.class, () -> CumulativeConfig.load(folder.getRoot(), main, logger));
        assertTrue(main.contains("votifier.cumulative.milestones"));
        assertEquals(original, Files.readString(file.toPath()));
    }
}
