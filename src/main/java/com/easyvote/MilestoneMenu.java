package com.easyvote;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/** Inventory work stays on the player's region; reward commands run on the global scheduler. */
final class MilestoneMenu implements Listener {
    private static final String ROOT = "votifier.cumulative.";
    private final EasyVotePlugin plugin;
    private final MilestoneRewards rewards;
    private final Map<UUID, Set<Integer>> notified = new ConcurrentHashMap<>();
    private final Set<UUID> processing = ConcurrentHashMap.newKeySet();
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    MilestoneMenu(EasyVotePlugin plugin, MilestoneRewards rewards) {
        this.plugin = plugin;
        this.rewards = rewards;
    }

    void reload() {
        rewards.configure(plugin.getConfig().getBoolean(ROOT + "enabled", true),
            MilestoneReward.load(plugin.getConfig().getList(ROOT + "milestones"), plugin.getLogger()));
        notified.clear();
        warned.clear();
    }

    void notifyAvailable(Player player) {
        player.getScheduler().run(plugin, task -> {
            try {
                Set<Integer> sent = notified.computeIfAbsent(player.getUniqueId(), key -> new HashSet<>());
                List<MilestoneReward> available = rewards.available(player.getName());
                sent.retainAll(available.stream().map(MilestoneReward::count).toList());
                int votes = plugin.getVoteHistory().getPlayerVoteCount(player.getName());
                for (MilestoneReward reward : available) {
                    if (sent.contains(reward.count())) continue;
                    ItemStack item = render(reward, MilestoneRewards.State.AVAILABLE, votes);
                    Component message = text(format(plugin.getConfig().getString(ROOT + "messages.available",
                        "&6[投票里程碑] &e已达到 %count% 次！可领取：%rewards% &a[点击打开]"), reward, votes))
                        .clickEvent(ClickEvent.runCommand("/easyvote rewards"))
                        .hoverEvent(item.asHoverEvent());
                    player.sendMessage(message);
                    sent.add(reward.count());
                }
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.WARNING, "发送里程碑提醒失败: " + player.getName(), e);
            }
        }, null);
    }

    void open(Player player) {
        open(player, 0, null);
    }

    private void open(Player player, int page, Session expected) {
        player.getScheduler().run(plugin, task -> {
            // A claim may finish after the player has closed this menu or opened another inventory.
            if (expected != null && player.getOpenInventory().getTopInventory().getHolder() != expected) return;
            try {
                if (!rewards.enabled()) {
                    player.sendMessage(text("&c累计投票奖励未启用。"));
                    return;
                }
                if (!player.hasPermission("easyvote.rewards")) return;
                List<Map<Integer, MilestoneReward>> pages = pages(rewards.rewards());
                int currentPage = Math.max(0, Math.min(page, pages.size() - 1));
                Session session = new Session(player.getUniqueId(), currentPage);
                String title = plugin.getConfig().getString(ROOT + "gui-title", "&6累计投票奖励")
                    + " &7(" + (currentPage + 1) + "/" + pages.size() + ")";
                session.inventory = Bukkit.createInventory(session, 54, text(title));
                int votes = plugin.getVoteHistory().getPlayerVoteCount(player.getName());
                for (var entry : pages.get(currentPage).entrySet()) {
                    MilestoneReward reward = entry.getValue();
                    session.counts.put(entry.getKey(), reward.count());
                    session.inventory.setItem(entry.getKey(), render(reward, rewards.state(player.getName(), reward.count()), votes));
                }
                if (currentPage > 0) session.inventory.setItem(45, button(Material.ARROW, "&e上一页"));
                session.inventory.setItem(49, button(Material.BOOK, "&e累计投票：" + votes + " 次"));
                if (currentPage + 1 < pages.size()) session.inventory.setItem(53, button(Material.ARROW, "&e下一页"));
                session.hasNext = currentPage + 1 < pages.size();
                player.openInventory(session.inventory);
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.WARNING, "打开里程碑界面失败", e);
                player.sendMessage(text("&c无法打开里程碑界面，请联系管理员。"));
            }
        }, null);
    }

    static List<Map<Integer, MilestoneReward>> pages(List<MilestoneReward> rewards) {
        List<Map<Integer, MilestoneReward>> pages = new ArrayList<>();
        Map<Integer, MilestoneReward> page = new HashMap<>();
        pages.add(page);
        for (MilestoneReward reward : rewards) {
            if (page.size() == 45) {
                page = new HashMap<>();
                pages.add(page);
            }
            int slot = reward.display().get("slot") instanceof Number number ? number.intValue() : -1;
            if (slot < 0 || slot >= 45 || page.containsKey(slot)) {
                slot = 0;
                while (page.containsKey(slot)) slot++;
            }
            page.put(slot, reward);
        }
        return pages;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Session session)) return;
        event.setCancelled(true); // Also blocks shift-click, number keys, double-click and offhand swaps.
        if (!(event.getWhoClicked() instanceof Player player) || !session.owner.equals(player.getUniqueId())) return;
        if (!player.hasPermission("easyvote.rewards") || event.getClickedInventory() != session.inventory
                || processing.contains(player.getUniqueId())) return;
        int slot = event.getRawSlot();
        if (slot == 45 && session.page > 0) { open(player, session.page - 1, session); return; }
        if (slot == 53 && session.hasNext) { open(player, session.page + 1, session); return; }
        Integer count = session.counts.get(slot);
        if (count == null || !processing.add(player.getUniqueId())) return;
        try {
            Bukkit.getGlobalRegionScheduler().run(plugin, task -> {
                MilestoneRewards.ClaimResult result;
                try {
                    result = rewards.claim(player.getName(), player.getUniqueId().toString(), count,
                        () -> player.isOnline() && player.hasPermission("easyvote.rewards"),
                        command -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command));
                } catch (RuntimeException e) {
                    plugin.getLogger().log(Level.SEVERE, "领取里程碑失败: " + player.getName(), e);
                    result = MilestoneRewards.ClaimResult.FAILED;
                } finally {
                    processing.remove(player.getUniqueId());
                }
                String fallback = switch (result) {
                    case SUCCESS -> "&a已领取累计投票 %count% 次奖励！";
                    case LOCKED -> "&c尚未达到 %count% 次投票。";
                    case RECEIVED -> "&7该里程碑奖励已经领取过了。";
                    case DISABLED, MISSING -> "&c该奖励已关闭或配置已变更，请重新打开界面。";
                    case BUSY -> "&e奖励正在发放，请稍候。";
                    case FAILED -> "&c奖励未全部发放，请稍后再次点击领取；已成功的命令不会重复执行。";
                };
                String message = plugin.getConfig().getString(ROOT + "messages." + result.name().toLowerCase(Locale.ROOT), fallback)
                    .replace("%count%", count.toString());
                player.getScheduler().run(plugin, reply -> player.sendMessage(text(message)), null);
                open(player, session.page, session);
            });
        } catch (RuntimeException e) {
            processing.remove(player.getUniqueId());
            throw e;
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Session) event.setCancelled(true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        notified.remove(event.getPlayer().getUniqueId());
    }

    private ItemStack render(MilestoneReward reward, MilestoneRewards.State state, int votes) {
        Map<String, Object> display = new HashMap<>();
        reward.display().forEach((key, value) -> display.put(key.toString(), value));
        var override = plugin.getConfig().getConfigurationSection(ROOT + "states." + state.name().toLowerCase(Locale.ROOT));
        if (override != null) {
            // A state with an explicit vanilla material can replace a CE icon.
            if (override.contains("material") && !override.contains("craftengine_model")) display.remove("craftengine_model");
            for (String key : List.of("material", "craftengine_model", "name")) {
                if (override.contains(key)) display.put(key, override.get(key));
            }
        }
        ItemStack stack = baseItem(display);
        var meta = stack.getItemMeta();
        String name = MilestoneReward.string(display, "name", "");
        if (!name.isBlank()) meta.displayName(text(format(name, reward, votes)));
        else if (!meta.hasDisplayName()) meta.displayName(text("&6累计投票 " + reward.count() + " 次奖励"));
        List<Component> lore = new ArrayList<>();
        if (meta.lore() != null) lore.addAll(meta.lore());
        List<String> configuredLore = MilestoneReward.lore(reward.display().get("lore"));
        if (configuredLore.isEmpty()) configuredLore = List.of("&7可领取：" + reward.description());
        for (String line : configuredLore) lore.add(text(format(line, reward, votes)));
        lore.add(text("&7投票进度：" + votes + "/" + reward.count()));
        List<String> stateLore = override == null ? List.of() : override.getStringList("lore");
        if (stateLore.isEmpty()) stateLore = List.of(switch (state) {
            case LOCKED -> "&c尚未达标";
            case AVAILABLE -> "&a点击领取奖励";
            case RECEIVED -> "&7已领取";
        });
        for (String line : stateLore) lore.add(text(format(line, reward, votes)));
        meta.lore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack baseItem(Map<?, ?> display) {
        String model = MilestoneReward.string(display, "craftengine_model", "");
        if (!model.isBlank()) {
            try {
                var craftEngine = Bukkit.getPluginManager().getPlugin("CraftEngine");
                if (craftEngine == null || !craftEngine.isEnabled()) throw new IllegalStateException("CraftEngine 未启用");
                // Optional API: do not require or shade a particular CraftEngine jar at build time.
                Class<?> api = Class.forName("net.momirealms.craftengine.bukkit.api.CraftEngineItems", true,
                    craftEngine.getClass().getClassLoader());
                Object definition = api.getMethod("byId", String.class).invoke(null, model);
                if (definition == null) throw new IllegalArgumentException("未知物品 ID");
                ItemStack item = (ItemStack) definition.getClass().getMethod("buildBukkitItem").invoke(definition);
                if (item != null && !item.getType().isAir()) return item.clone();
                throw new IllegalArgumentException("物品为空");
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                if (warned.add(model)) plugin.getLogger().warning("CE 物品显示失败，回退原版材质 [" + model + "]: " + e);
            }
        }
        Material material = Material.matchMaterial(MilestoneReward.string(display, "material", "CHEST"));
        return new ItemStack(material == null || !material.isItem() || material.isAir() ? Material.CHEST : material);
    }

    private static String format(String value, MilestoneReward reward, int votes) {
        return value.replace("%rewards%", reward.description()).replace("%count%", Integer.toString(reward.count()))
            .replace("%votes%", Integer.toString(votes));
    }

    static Component text(String value) {
        Component component = value.indexOf('§') >= 0 ? LegacyComponentSerializer.legacySection().deserialize(value)
            : value.indexOf('&') >= 0 ? LegacyComponentSerializer.legacyAmpersand().deserialize(value)
            : MiniMessage.miniMessage().deserialize(value);
        return component.decoration(TextDecoration.ITALIC, false);
    }

    private static ItemStack button(Material material, String name) {
        ItemStack item = new ItemStack(material);
        var meta = item.getItemMeta();
        meta.displayName(text(name));
        item.setItemMeta(meta);
        return item;
    }

    private static final class Session implements InventoryHolder {
        private final UUID owner;
        private final int page;
        private final Map<Integer, Integer> counts = new HashMap<>();
        private Inventory inventory;
        private boolean hasNext;

        private Session(UUID owner, int page) { this.owner = owner; this.page = page; }
        @Override public Inventory getInventory() { return inventory; }
    }
}
