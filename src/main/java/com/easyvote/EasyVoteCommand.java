package com.easyvote;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

public class EasyVoteCommand implements CommandExecutor {

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("easyvote.admin")) {
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
                EasyVotePlugin.getInstance().reloadPluginConfig();
                sender.sendMessage(ChatColor.GREEN + "配置已重新加载!");
                break;
                
            case "pubkey":
                RSAKeyManager keyManager = EasyVotePlugin.getInstance().getKeyManager();
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
                
            case "votestats":
                VoteHistory voteHistory = EasyVotePlugin.getInstance().getVoteHistory();
                if (voteHistory == null) {
                    sender.sendMessage(ChatColor.RED + "Votifier 未启用!");
                    break;
                }
                sender.sendMessage(ChatColor.GOLD + "=== 投票统计 ===");
                sender.sendMessage(ChatColor.YELLOW + "总投票数: " + voteHistory.getTotalVotes());
                break;
                
            case "testvote":
                if (args.length < 3) {
                    sender.sendMessage(ChatColor.RED + "用法: /easyvote testvote <玩家名> <网站名>");
                    sender.sendMessage(ChatColor.RED + "示例: /easyvote testvote Steve mczfw");
                    break;
                }
                String testPlayer = args[1];
                String testService = args[2];
                sender.sendMessage(ChatColor.YELLOW + "正在模拟投票: " + testPlayer + " 从 " + testService);
                VoteEvent event = new VoteEvent(testPlayer, testService, "127.0.0.1", System.currentTimeMillis());
                Bukkit.getPluginManager().callEvent(event);
                sender.sendMessage(ChatColor.GREEN + "测试投票事件已触发!");
                break;
                
            case "clearvotes":
                VoteHistory history = EasyVotePlugin.getInstance().getVoteHistory();
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

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "=== EasyVote 帮助 ===");
        sender.sendMessage(ChatColor.YELLOW + "/easyvote reload" + ChatColor.WHITE + " - 重新加载配置");
        sender.sendMessage(ChatColor.YELLOW + "/easyvote pubkey" + ChatColor.WHITE + " - 显示 Votifier 公钥");
        sender.sendMessage(ChatColor.YELLOW + "/easyvote votestats" + ChatColor.WHITE + " - 显示投票统计");
        sender.sendMessage(ChatColor.YELLOW + "/easyvote testvote <玩家名> <网站名>" + ChatColor.WHITE + " - 测试投票奖励");
        sender.sendMessage(ChatColor.YELLOW + "/easyvote clearvotes [玩家名]" + ChatColor.WHITE + " - 清除投票数据");
        sender.sendMessage(ChatColor.GRAY + "  不指定玩家名则清除所有数据");
    }
}
