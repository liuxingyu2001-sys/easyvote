package com.easyvote;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.Assert.*;

public class MilestoneConfigTest {
    private final Logger logger = Logger.getLogger("MilestoneConfigTest");

    @Test public void defaultRewardsHaveReadableDescriptionsAndConfiguredSlots() {
        var config = YamlConfiguration.loadConfiguration(new InputStreamReader(
            getClass().getResourceAsStream("/cumulative.yml"), StandardCharsets.UTF_8));
        var rewards = MilestoneReward.load(config.getList("milestones"), logger);
        assertEquals(List.of(10, 50, 100), rewards.stream().map(MilestoneReward::count).toList());
        assertTrue(rewards.getFirst().description().contains("钻石"));
        var pages = MilestoneMenu.pages(rewards);
        assertEquals(1, pages.size());
        assertEquals(10, pages.getFirst().get(20).count());
        assertEquals(50, pages.getFirst().get(22).count());
        assertEquals(100, pages.getFirst().get(24).count());
    }

    @Test public void legacyAndCraftEngineDefinitionsRemainCompatible() {
        var rewards = MilestoneReward.load(List.of(
            Map.of("count", 10, "commands", List.of("give %player% diamond 10")),
            Map.of("count", 20, "commands", List.of("ce give %player% test:item"), "display",
                Map.of("craftengine_model", "test:item", "material", "DIAMOND", "name", "&b自定义钻石",
                    "lore", List.of("这行不该出现在提醒里")))
        ), logger);
        assertEquals(2, rewards.size());
        assertEquals("&b自定义钻石", rewards.get(1).description());
        assertEquals("test:item", rewards.get(1).display().get("craftengine_model"));
    }

    @Test public void reminderDescriptionNeverFallsBackToLore() {
        var rewards = MilestoneReward.load(List.of(Map.of("count", 5, "commands", List.of("give"),
            "display", Map.of("lore", List.of("第一行", "第二行")))), logger);
        assertEquals("累计投票 5 次奖励", rewards.getFirst().description());
        assertFalse(rewards.getFirst().description().contains("第一行"));
    }

    @Test public void reminderMessagesSupportListsAndNewlines() {
        assertEquals(List.of("第一行", "第二行", "第三行"),
            MilestoneMenu.lines(List.of("第一行", "第二行\\n第三行"), "fallback"));
        assertEquals(List.of("单行"), MilestoneMenu.lines("单行", "fallback"));
        assertEquals(List.of("回退"), MilestoneMenu.lines("   ", "回退"));
        assertEquals(List.of("a", "", "b"), MilestoneMenu.lines("a\n\nb\n", "fallback"));
    }

    @Test public void invalidOrDuplicateCountsCannotCreateMultipleClaims() {
        var rewards = MilestoneReward.load(List.of(Map.of("count", -1), Map.of("count", 1.5),
            Map.of("count", "10"), Map.of("count", 10, "commands", List.of("first")),
            Map.of("count", 10, "commands", List.of("duplicate"))), logger);
        assertEquals(1, rewards.size());
        assertEquals(List.of("first"), rewards.getFirst().commands());
    }

    @Test public void availableMessageParsesAmpersandCodesWithoutSectionCodes() {
        String message = "&6[投票里程碑] &e已达到 8 次！可领取：圣光普照 &a[点击打开]";
        String rendered = LegacyComponentSerializer.legacySection().serialize(MilestoneMenu.text(message));
        assertEquals("§6[投票里程碑] §e已达到 8 次！可领取：圣光普照 §a[点击打开]", rendered);
    }

    @Test public void mixedColorCodesInAvailableMessageAreBothParsed() {
        String message = "&6[投票里程碑] &e已达到 8 次！可领取：§b圣光普照 &a[点击打开]";
        String rendered = LegacyComponentSerializer.legacySection().serialize(MilestoneMenu.text(message));
        assertFalse(rendered.contains("&6"));
        assertFalse(rendered.contains("&e"));
        assertFalse(rendered.contains("&a"));
        assertTrue(rendered.contains("§6[投票里程碑]"));
        assertTrue(rendered.contains("§e已达到 8 次！可领取："));
        assertTrue(rendered.contains("§b圣光普照"));
        assertTrue(rendered.contains("§a[点击打开]"));
    }

    @Test public void paginationRetainsAllTiersEvenWithConflictingOrReservedSlots() {
        List<MilestoneReward> rewards = new ArrayList<>();
        for (int i = 1; i <= 100; i++) {
            rewards.add(new MilestoneReward(i, List.of("give"), "reward", Map.of("slot", i % 2 == 0 ? 20 : 53)));
        }
        var pages = MilestoneMenu.pages(rewards);
        assertEquals(3, pages.size());
        assertEquals(100, pages.stream().flatMap(page -> page.values().stream()).map(MilestoneReward::count).distinct().count());
        assertTrue(pages.stream().allMatch(page -> page.keySet().stream().allMatch(slot -> slot >= 0 && slot < 45)));
    }
}
