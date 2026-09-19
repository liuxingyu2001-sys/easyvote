package com.easyvote;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public class VoteListener implements Listener {

    private final EasyVotePlugin plugin;
    private final VoteHistory voteHistory;
    private final RewardDelivery rewardDelivery;
    private volatile List<String> firstVoteCommands = List.of();
    private volatile List<String> voteCommands = List.of();
    private final Set<String> pendingRewardDispatches = ConcurrentHashMap.newKeySet();

    public VoteListener(EasyVotePlugin plugin, VoteHistory voteHistory) {
        this.plugin = plugin;
        this.voteHistory = voteHistory;
        this.rewardDelivery = new RewardDelivery(voteHistory, plugin.getLogger());
        loadRewards();
    }

    private void loadRewards() {
        var rewardsSection = plugin.getConfig().getConfigurationSection("votifier.rewards");
        if (rewardsSection == null) {
            firstVoteCommands = List.of();
            voteCommands = List.of();
            plugin.getLogger().warning("未找到投票奖励配置 (votifier.rewards)");
            return;
        }

        List<String> newFirstVote = loadCommandList(rewardsSection, "first-vote");
        List<String> newVote = loadCommandList(rewardsSection, "vote");

        if (newFirstVote.isEmpty()) {
            plugin.getLogger().warning("未找到首次投票奖励配置 (first-vote)");
        }
        if (newVote.isEmpty()) {
            plugin.getLogger().warning("未找到普通投票奖励配置 (vote)");
        }

        this.firstVoteCommands = newFirstVote;
        this.voteCommands = newVote;

        plugin.getLogger().info("已加载投票奖励配置: 首次 " + newFirstVote.size() + " 条, 普通 " + newVote.size() + " 条");
    }

    private List<String> loadCommandList(org.bukkit.configuration.ConfigurationSection section, String key) {
        List<?> rawList = section.getList(key);
        if (rawList == null) {
            return List.of();
        }
        List<String> commands = new ArrayList<>();
        for (Object obj : rawList) {
            if (obj instanceof String command && !command.isBlank()) commands.add(command.trim());
        }
        return List.copyOf(commands);
    }

    public void reloadRewards() {
        loadRewards();
    }

    @EventHandler
    public synchronized void onVote(VoteEvent event) {
        String playerName = event.getPlayerName();
        String serviceName = event.getServiceName();
        String address = event.getAddress();
        long timestamp = event.getTimestamp();

        try {
            int dailyLimit = plugin.getDailyVoteLimit();
            VoteHistory.VoteResult result = voteHistory.recordVoteAndQueue(
                playerName, serviceName, address, timestamp, dailyLimit);
            if (result == VoteHistory.VoteResult.DAILY_LIMIT_REACHED) {
                plugin.getLogger().info("玩家 " + playerName + " 已达到每日投票上限 " + dailyLimit
                    + " 次，忽略来自 " + serviceName + " 的投票（不计数、不发奖励）");
                return;
            }
            boolean isFirstVote = result == VoteHistory.VoteResult.FIRST_VOTE;
            // A join delay also covers votes that arrive just after the player joins.
            if (!pendingRewardDispatches.contains(playerName.toLowerCase(Locale.ROOT))) {
                scheduleRewards(playerName, 1L);
            }
            if (plugin.isDebugEnabled()) {
                saveDebugVoteInfo(playerName, serviceName, address, timestamp, isFirstVote);
            }
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.SEVERE, "处理投票失败: " + playerName, e);
        }
    }

    private Player findOnlinePlayer(String name) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getName().equalsIgnoreCase(name)) {
                return p;
            }
        }
        return null;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        long delaySeconds = Math.max(0L,
            plugin.getConfig().getLong("votifier.join-reward-delay-seconds", 5L));
        long delayTicks = delaySeconds > Long.MAX_VALUE / 20L ? Long.MAX_VALUE : delaySeconds * 20L;
        scheduleRewards(event.getPlayer().getName(), Math.max(1L, delayTicks));
    }

    public void retryOnlineRewards() {
        Bukkit.getGlobalRegionScheduler().run(plugin, task -> {
            for (Player player : Bukkit.getOnlinePlayers()) scheduleRewards(player.getName(), 1L);
        });
    }

    private void scheduleRewards(String playerName, long delayTicks) {
        String playerKey = playerName.toLowerCase(Locale.ROOT);
        if (!pendingRewardDispatches.add(playerKey)) return;
        try {
            Bukkit.getGlobalRegionScheduler().runDelayed(plugin, task -> {
                synchronized (VoteListener.this) {
                    try {
                        Player player = findOnlinePlayer(playerName);
                        if (player != null) deliverPendingRewards(player);
                    } catch (RuntimeException e) {
                        plugin.getLogger().log(Level.SEVERE, "发放奖励失败，未完成奖励保留待发: " + playerName, e);
                    } finally {
                        pendingRewardDispatches.remove(playerKey);
                    }
                }
            }, Math.max(1L, delayTicks));
        } catch (RuntimeException e) {
            pendingRewardDispatches.remove(playerKey);
            throw e;
        }
    }

    private synchronized void deliverPendingRewards(Player player) {
        String playerName = player.getName();
        List<VoteHistory.PendingReward> pending = voteHistory.getPendingRewards(playerName);
        try {
            for (VoteHistory.PendingReward reward : pending) {
                List<String> commands = reward.isFirstVote() ? firstVoteCommands : voteCommands;
                if (!deliverCommands(player, "vote:" + reward.getId(), commands,
                        reward.getServiceName(), reward.getAddress())) return;
                voteHistory.deletePendingReward(reward.getId());
            }
        } finally {
            // Milestones only notify here, including when an ordinary reward fails.
            plugin.getMilestoneMenu().notifyAvailable(player);
        }
    }

    private boolean deliverCommands(Player player, String rewardKey, List<String> commands,
                                    String serviceName, String address) {
        List<String> processed = commands.stream()
            .map(command -> replaceVariables(command, player.getName(), serviceName, address, player))
            .toList();
        return rewardDelivery.deliver(player.getName(), rewardKey, processed, player::isOnline,
            command -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command));
    }

    private String replaceVariables(String command, String playerName, String serviceName, String address, Player player) {
        return command
            .replace("%player%", playerName)
            .replace("%player_name%", playerName)
            .replace("%service%", serviceName)
            .replace("%address%", address == null ? "" : address)
            .replace("%uuid%", player.getUniqueId().toString());
    }

    private void saveDebugVoteInfo(String playerName, String serviceName, String address, long timestamp, boolean isFirstVote) {
        try {
            File debugDir = new File(plugin.getDataFolder(), "debug");
            if (!debugDir.exists()) {
                debugDir.mkdirs();
            }

            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss");
            String fileName = "vote_" + playerName + "_" + sdf.format(new Date(timestamp)) + ".txt";
            File debugFile = new File(debugDir, fileName);

            try (BufferedWriter writer = new BufferedWriter(new FileWriter(debugFile))) {
                writer.write("=== 投票调试信息 ===");
                writer.newLine();
                writer.newLine();
                writer.write("玩家名称: " + playerName);
                writer.newLine();
                writer.write("投票网站: " + serviceName);
                writer.newLine();
                writer.write("投票来源IP: " + address);
                writer.newLine();
                writer.write("时间戳: " + timestamp);
                writer.newLine();
                writer.write("时间: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(timestamp)));
                writer.newLine();
                writer.newLine();
                writer.write("是否为首次投票: " + (isFirstVote ? "是" : "否"));
                writer.newLine();
                writer.write("玩家总投票数: " + voteHistory.getPlayerVoteCount(playerName));
                writer.newLine();
                writer.write("服务器总投票数: " + voteHistory.getTotalVotes());
            }

            plugin.getLogger().info("[DEBUG] 投票信息已保存: " + debugFile.getName());
        } catch (IOException e) {
            plugin.getLogger().warning("[DEBUG] 保存投票信息失败: " + e.getMessage());
        }
    }
}
