package com.easyvote;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class VoteListener implements Listener {

    private final EasyVotePlugin plugin;
    private final VoteHistory voteHistory;
    private final Map<String, List<String>> rewardCommands;

    public VoteListener(EasyVotePlugin plugin, VoteHistory voteHistory) {
        this.plugin = plugin;
        this.voteHistory = voteHistory;
        this.rewardCommands = new HashMap<>();
        loadRewards();
    }

    private void loadRewards() {
        rewardCommands.clear();
        
        var rewardsSection = plugin.getConfig().getConfigurationSection("votifier.rewards");
        if (rewardsSection == null) {
            plugin.getLogger().warning("未找到默认奖励配置 (default)");
            return;
        }
        
        for (String serviceName : rewardsSection.getKeys(false)) {
            List<?> rawList = rewardsSection.getList(serviceName);
            if (rawList != null) {
                List<String> commands = rawList.stream()
                    .map(Object::toString)
                    .collect(java.util.stream.Collectors.toList());
                rewardCommands.put(serviceName.toLowerCase(), commands);
                plugin.getLogger().info("加载奖励配置: " + serviceName + " (" + commands.size() + " 条命令)");
            }
        }
        
        if (!rewardCommands.containsKey("default")) {
            plugin.getLogger().warning("未找到默认奖励配置 (default)");
        }
        
        plugin.getLogger().info("已加载 " + rewardCommands.size() + " 个投票奖励配置");
    }

    public void reloadRewards() {
        loadRewards();
    }

    @EventHandler
    public void onVote(VoteEvent event) {
        String playerName = event.getPlayerName();
        String serviceName = event.getServiceName();
        String address = event.getAddress();
        long timestamp = event.getTimestamp();
        
        if (plugin.isDebugEnabled()) {
            saveDebugVoteInfo(playerName, serviceName, address, timestamp);
        }
        
        boolean isFirstVote = voteHistory.isFirstVoteForService(playerName, serviceName);
        
        voteHistory.addVote(playerName, serviceName, address, timestamp);
        
        Player player = Bukkit.getPlayerExact(playerName);
        if (player == null || !player.isOnline()) {
            plugin.getLogger().info("玩家 " + playerName + " 不在线，跳过奖励");
            return;
        }
        
        String serviceKey = mapServiceName(serviceName);
        String firstVoteKey = "first-vote-" + serviceKey;
        plugin.getLogger().info("[投票奖励] 玩家: " + playerName + ", 网站: " + serviceName + ", 映射后Key: " + serviceKey + ", 首次Key: " + firstVoteKey);
        
        List<String> commands = null;
        String rewardType = "";
        
        if (isFirstVote) {
            commands = rewardCommands.get(firstVoteKey);
            if (commands != null && !commands.isEmpty()) {
                rewardType = "首次奖励";
            }
        }
        
        if (commands == null || commands.isEmpty()) {
            commands = rewardCommands.get(serviceKey);
            if (commands != null && !commands.isEmpty()) {
                rewardType = "常规奖励";
            }
        }
        
        if (commands == null || commands.isEmpty()) {
            commands = rewardCommands.get("default");
            rewardType = "默认奖励";
        }
        
        if (commands != null && !commands.isEmpty()) {
            plugin.getLogger().info(playerName + " 从 " + serviceName + " 投票，发放" + rewardType);
            for (String command : commands) {
                String processedCommand = replaceVariables(command, playerName, serviceName, address, player);
                final String cmd = processedCommand;
                Bukkit.getGlobalRegionScheduler().run(plugin, task -> {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
                });
            }
        } else {
            plugin.getLogger().warning("未找到 " + playerName + " 的投票奖励配置!");
        }
    }
    
    private String mapServiceName(String serviceName) {
        String lower = serviceName.toLowerCase();
        
        if (lower.contains("mczfw")) {
            return "mczfw";
        }
        if (lower.contains("服务器站") || lower.contains("wdsjfwq")) {
            return "wdsjfwq";
        }
        if (lower.contains(".")) {
            return lower.split("\\.")[0];
        }
        
        return lower;
    }

    private String replaceVariables(String command, String playerName, String serviceName, String address, Player player) {
        return command
            .replace("%player%", playerName)
            .replace("%player_name%", playerName)
            .replace("%service%", serviceName)
            .replace("%address%", address)
            .replace("%uuid%", player.getUniqueId().toString());
    }
    
    private void saveDebugVoteInfo(String playerName, String serviceName, String address, long timestamp) {
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
                writer.write("是否为首次投票: " + (voteHistory.isFirstVoteForService(playerName, serviceName) ? "是" : "否"));
                writer.newLine();
                writer.write("玩家总投票数: " + voteHistory.getPlayerVoteCount(playerName));
                writer.newLine();
                writer.write("该网站总投票数: " + voteHistory.getServiceVoteCount(serviceName));
                writer.newLine();
                writer.write("总投票数: " + voteHistory.getTotalVotes());
            }
            
            plugin.getLogger().info("[DEBUG] 投票信息已保存: " + debugFile.getName());
        } catch (IOException e) {
            plugin.getLogger().warning("[DEBUG] 保存投票信息失败: " + e.getMessage());
        }
    }
}
