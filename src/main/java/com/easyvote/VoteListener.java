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
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class VoteListener implements Listener {

    private final EasyVotePlugin plugin;
    private final VoteHistory voteHistory;
    private final MilestoneTracker milestoneTracker;
    private volatile List<String> firstVoteCommands = List.of();
    private volatile List<String> voteCommands = List.of();
    private final Set<String> pendingRewardDispatches = ConcurrentHashMap.newKeySet();

    public VoteListener(EasyVotePlugin plugin, VoteHistory voteHistory, MilestoneTracker milestoneTracker) {
        this.plugin = plugin;
        this.voteHistory = voteHistory;
        this.milestoneTracker = milestoneTracker;
        loadRewards();
    }

    private void loadRewards() {
        var rewardsSection = plugin.getConfig().getConfigurationSection("votifier.rewards");
        if (rewardsSection == null) {
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
            commands.add(obj.toString());
        }
        return commands;
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

        boolean isFirstVote = voteHistory.getPlayerVoteCount(playerName) == 0;

        voteHistory.addVote(playerName, serviceName, address, timestamp);

        if (plugin.isDebugEnabled()) {
            saveDebugVoteInfo(playerName, serviceName, address, timestamp, isFirstVote);
        }

        Bukkit.getGlobalRegionScheduler().run(plugin, task -> {
            Player player = findOnlinePlayer(playerName);
            if (player == null) {
                plugin.getLogger().info("玩家 " + playerName + " 不在线，奖励已存入待发放队列");
                voteHistory.addPendingReward(playerName, serviceName, address, timestamp, isFirstVote);
                return;
            }

            dispatchRewards(player, serviceName, address, isFirstVote);
            checkCumulativeMilestones(player.getName(), serviceName, address, player);
        });
    }

    private Player findOnlinePlayer(String name) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getName().equalsIgnoreCase(name)) {
                return p;
            }
        }
        return null;
    }

    private void dispatchRewards(Player player, String serviceName, String address, boolean isFirstVote) {
        List<String> commands = isFirstVote ? firstVoteCommands : voteCommands;
        String rewardType = isFirstVote ? "首次投票奖励" : "投票奖励";

        if (!commands.isEmpty()) {
            plugin.getLogger().info(player.getName() + " 投票，发放" + rewardType);
            for (String command : commands) {
                String processedCommand = replaceVariables(command, player.getName(), serviceName, address, player);
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), processedCommand);
            }
        } else {
            plugin.getLogger().warning("未配置" + rewardType + "!");
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        String playerName = event.getPlayer().getName();
        String playerKey = playerName.toLowerCase(Locale.ROOT);
        if (!pendingRewardDispatches.add(playerKey)) {
            return;
        }

        long delaySeconds = Math.max(0L,
            plugin.getConfig().getLong("votifier.join-reward-delay-seconds", 5L));
        long delayTicks = delaySeconds > Long.MAX_VALUE / 20L
            ? Long.MAX_VALUE
            : delaySeconds * 20L;

        Bukkit.getGlobalRegionScheduler().runDelayed(plugin, task -> {
            try {
                Player player = findOnlinePlayer(playerName);
                if (player == null) {
                    return;
                }

                // Read at execution time so votes received during the join delay are not lost.
                List<VoteHistory.PendingReward> pending = voteHistory.getPendingRewards(playerName);
                if (pending.isEmpty()) {
                    return;
                }

                plugin.getLogger().info("玩家 " + playerName + " 上线，补发 " + pending.size() + " 条离线投票奖励");

                for (VoteHistory.PendingReward reward : pending) {
                    dispatchRewards(player, reward.getServiceName(), reward.getAddress(), reward.isFirstVote());
                    checkCumulativeMilestones(playerName, reward.getServiceName(), reward.getAddress(), player);
                    voteHistory.deletePendingReward(reward.getId());
                }
            } finally {
                pendingRewardDispatches.remove(playerKey);
            }
        }, delayTicks);
    }

    private void checkCumulativeMilestones(String playerName, String serviceName, String address, Player player) {
        if (!plugin.getConfig().getBoolean("votifier.cumulative.enabled", true)) {
            return;
        }

        var milestonesSection = plugin.getConfig().getList("votifier.cumulative.milestones");
        if (milestonesSection == null || milestonesSection.isEmpty()) {
            return;
        }

        int totalVotes = voteHistory.getPlayerVoteCount(playerName);
        long timestamp = System.currentTimeMillis();

        for (Object obj : milestonesSection) {
            if (!(obj instanceof Map)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> milestone = (Map<String, Object>) obj;

            int count;
            Object countObj = milestone.get("count");
            if (countObj instanceof Number number) {
                count = number.intValue();
            } else {
                continue;
            }

            if (totalVotes < count) {
                continue;
            }

            Object commandsObj = milestone.get("commands");
            if (!(commandsObj instanceof List<?> rawCommands) || rawCommands.isEmpty()) {
                // Do not consume a milestone that has no usable reward commands.
                continue;
            }

            if (!milestoneTracker.markIfNotReceived(playerName, count, timestamp)) {
                continue;
            }

            plugin.getLogger().info(playerName + " 达到累计投票里程碑 " + count + " 次！");

            for (Object cmdObj : rawCommands) {
                String command = cmdObj.toString();
                String processedCommand = replaceVariables(command, playerName, serviceName, address, player);
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), processedCommand);
            }
        }
    }

    private String replaceVariables(String command, String playerName, String serviceName, String address, Player player) {
        return command
            .replace("%player%", playerName)
            .replace("%player_name%", playerName)
            .replace("%service%", serviceName)
            .replace("%address%", address)
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
