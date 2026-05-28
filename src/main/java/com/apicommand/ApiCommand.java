package com.apicommand;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.UUID;

public class ApiCommand implements CommandExecutor {

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("apicommand.admin")) {
            sender.sendMessage(ChatColor.RED + "你没有权限使用此命令!");
            return true;
        }
        
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }
        
        String subCommand = args[0].toLowerCase();
        
        switch (subCommand) {
            case "reload":
                ApiCommandPlugin.getInstance().reloadPluginConfig();
                sender.sendMessage(ChatColor.GREEN + "配置已重新加载!");
                break;
                
            case "key":
                sender.sendMessage(ChatColor.YELLOW + "当前 API 密钥: " + ApiCommandPlugin.getInstance().getApiKey());
                break;
                
            case "pubkey":
                RSAKeyManager keyManager = ApiCommandPlugin.getInstance().getKeyManager();
                if (keyManager == null) {
                    sender.sendMessage(ChatColor.RED + "Votifier 未启用!");
                    break;
                }
                sender.sendMessage(ChatColor.GOLD + "=== Votifier 公钥 ===");
                sender.sendMessage(ChatColor.YELLOW + "复制以下公钥到投票网站:");
                sender.sendMessage(ChatColor.WHITE + "----------------------------------------");
                String pemKey = keyManager.getPublicKeyPEM();
                for (String line : pemKey.split("\n")) {
                    sender.sendMessage(ChatColor.WHITE + line);
                }
                sender.sendMessage(ChatColor.WHITE + "----------------------------------------");
                break;
                
            case "status":
                sender.sendMessage(ChatColor.YELLOW + "API 状态: " + (ApiCommandPlugin.getInstance().isApiEnabled() ? "已启用" : "已禁用"));
                sender.sendMessage(ChatColor.YELLOW + "API 端口: " + ApiCommandPlugin.getInstance().getApiPort());
                sender.sendMessage(ChatColor.YELLOW + "最大连接数: " + ApiCommandPlugin.getInstance().getMaxConnections());
                sender.sendMessage(ChatColor.YELLOW + "请求超时: " + ApiCommandPlugin.getInstance().getRequestTimeout() + "秒");
                sender.sendMessage(ChatColor.YELLOW + "限流: " + (ApiCommandPlugin.getInstance().isRateLimitEnabled() ? "启用" : "禁用"));
                sender.sendMessage(ChatColor.YELLOW + "每分钟最大请求: " + ApiCommandPlugin.getInstance().getMaxRequestsPerMinute());
                sender.sendMessage(ChatColor.GRAY + "提示: 访问 /api/stats 获取实时统计");
                break;
                
            case "stats":
                sender.sendMessage(ChatColor.GOLD + "=== API 统计信息 ===");
                sender.sendMessage(ChatColor.GRAY + "使用 /apicommand stats 查看详细统计");
                sender.sendMessage(ChatColor.GRAY + "提示: 访问 /api/stats 获取实时统计");
                break;
                
            case "votestats":
                VoteHistory voteHistory = ApiCommandPlugin.getInstance().getVoteHistory();
                if (voteHistory == null) {
                    sender.sendMessage(ChatColor.RED + "Votifier 未启用!");
                    break;
                }
                sender.sendMessage(ChatColor.WHITE + "=== 投票统计 ===");
                sender.sendMessage(ChatColor.YELLOW + "总投票数: " + voteHistory.getTotalVotes());
                sender.sendMessage(ChatColor.GRAY + "提示: 访问 /api/vote/stats 获取详细统计");
                break;
                
            case "testvote":
                if (args.length < 3) {
                    sender.sendMessage(ChatColor.RED + "用法: /apicommand testvote <玩家名> <网站名>");
                    sender.sendMessage(ChatColor.RED + "示例: /apicommand testvote Steve mczfw");
                    break;
                }
                String testPlayer = args[1];
                String testService = args[2];
                sender.sendMessage(ChatColor.YELLOW + "正在模拟投票: " + testPlayer + " 从 " + testService);
                VoteEvent event = new VoteEvent(testPlayer, testService, "127.0.0.1", System.currentTimeMillis());
                Bukkit.getPluginManager().callEvent(event);
                sender.sendMessage(ChatColor.GREEN + "测试投票事件已触发!");
                break;
                
            case "setskin":
                if (args.length < 3) {
                    sender.sendMessage(ChatColor.RED + "用法: /apicommand setskin <玩家名> <皮肤URL> [皮肤类型]");
                    sender.sendMessage(ChatColor.RED + "皮肤类型: classic(标准) 或 slim(纤细)，默认为 classic");
                    break;
                }
                String playerName = args[1];
                String skinUrl = args[2];
                String skinType = args.length > 3 ? args[3].toLowerCase() : "classic";
                setSkinForPlayer(sender, playerName, skinUrl, skinType);
                break;
                
            case "clearskin":
                if (args.length < 2) {
                    sender.sendMessage(ChatColor.RED + "用法: /apicommand clearskin <玩家名>");
                    break;
                }
                clearSkinForPlayer(sender, args[1]);
                break;
                
            case "clearvotes":
                VoteHistory history = ApiCommandPlugin.getInstance().getVoteHistory();
                if (history == null) {
                    sender.sendMessage(ChatColor.RED + "Votifier 未启用!");
                    break;
                }
                if (args.length >= 2) {
                    String targetPlayer = args[1].toLowerCase();
                    int cleared = history.clearPlayerVotes(targetPlayer);
                    if (cleared > 0) {
                        sender.sendMessage(ChatColor.GREEN + "已清除玩家 " + targetPlayer + " 的 " + cleared + " 条投票记录!");
                    } else {
                        sender.sendMessage(ChatColor.YELLOW + "玩家 " + targetPlayer + " 没有投票记录!");
                    }
                } else {
                    history.clear();
                    sender.sendMessage(ChatColor.GREEN + "所有投票数据已清除!");
                }
                break;
                
            default:
                sendHelp(sender);
                break;
        }
        
        return true;
    }

    private void setSkinForPlayer(CommandSender sender, String playerName, String skinUrl, String skinType) {
        Player onlinePlayer = Bukkit.getPlayerExact(playerName);
        if (onlinePlayer == null || !onlinePlayer.isOnline()) {
            sender.sendMessage(ChatColor.RED + "玩家 " + playerName + " 不在线，无法设置皮肤!");
            return;
        }
        
        sender.sendMessage(ChatColor.YELLOW + "正在为玩家 " + playerName + " 设置皮肤...");
        
        Bukkit.getScheduler().runTask(ApiCommandPlugin.getInstance(), () -> {
            onlinePlayer.performCommand("skin url " + skinUrl);
            sender.sendMessage(ChatColor.GREEN + "皮肤设置成功!");
        });
    }
    
    private void clearSkinForPlayer(CommandSender sender, String playerName) {
        Player onlinePlayer = Bukkit.getPlayerExact(playerName);
        if (onlinePlayer == null || !onlinePlayer.isOnline()) {
            sender.sendMessage(ChatColor.RED + "玩家 " + playerName + " 不在线，无法清除皮肤!");
            return;
        }
        
        sender.sendMessage(ChatColor.YELLOW + "正在清除玩家 " + playerName + " 的皮肤...");
        
        Bukkit.getScheduler().runTask(ApiCommandPlugin.getInstance(), () -> {
            onlinePlayer.performCommand("skin clear");
            sender.sendMessage(ChatColor.GREEN + "皮肤已清除!");
        });
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "=== ApiCommand 帮助 ===");
        sender.sendMessage(ChatColor.YELLOW + "/apicommand reload" + ChatColor.WHITE + " - 重新加载配置");
        sender.sendMessage(ChatColor.YELLOW + "/apicommand key" + ChatColor.WHITE + " - 显示当前 API 密钥");
        sender.sendMessage(ChatColor.YELLOW + "/apicommand pubkey" + ChatColor.WHITE + " - 显示 Votifier 公钥");
        sender.sendMessage(ChatColor.YELLOW + "/apicommand status" + ChatColor.WHITE + " - 显示 API 状态");
        sender.sendMessage(ChatColor.YELLOW + "/apicommand stats" + ChatColor.WHITE + " - 显示 API 统计信息");
        sender.sendMessage(ChatColor.YELLOW + "/apicommand votestats" + ChatColor.WHITE + " - 显示投票统计");
        sender.sendMessage(ChatColor.YELLOW + "/apicommand testvote <玩家名> <网站名>" + ChatColor.WHITE + " - 测试投票奖励");
        sender.sendMessage(ChatColor.YELLOW + "/apicommand setskin <玩家名> <皮肤URL> [皮肤类型]" + ChatColor.WHITE + " - 为玩家设置皮肤");
        sender.sendMessage(ChatColor.GRAY + "  皮肤类型: classic(标准) 或 slim(纤细)，默认为 classic");
        sender.sendMessage(ChatColor.YELLOW + "/apicommand clearskin <玩家名>" + ChatColor.WHITE + " - 清除玩家皮肤");
        sender.sendMessage(ChatColor.YELLOW + "/apicommand clearvotes [玩家名]" + ChatColor.WHITE + " - 清除投票数据");
        sender.sendMessage(ChatColor.GRAY + "  不指定玩家名则清除所有数据");
    }
}
