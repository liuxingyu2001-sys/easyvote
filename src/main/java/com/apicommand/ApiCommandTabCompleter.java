package com.apicommand;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class ApiCommandTabCompleter implements TabCompleter {

    private static final List<String> SUB_COMMANDS = Arrays.asList(
        "reload", "pubkey", "votestats", "testvote", "clearvotes"
    );
    
    private static final List<String> VOTE_SERVICES = Arrays.asList(
        "mczfw", "wdsjfwq", "list.mczfw.com", "www.mczfw.com", "www.wdsjfwq.com"
    );

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();
        
        if (!sender.hasPermission("apicommand.admin")) {
            return completions;
        }
        
        if (args.length == 1) {
            String subCommand = args[0].toLowerCase();
            completions.addAll(SUB_COMMANDS.stream()
                .filter(cmd -> cmd.startsWith(subCommand))
                .collect(Collectors.toList()));
        } else if (args.length == 2) {
            String subCommand = args[0].toLowerCase();
            
            if ("testvote".equals(subCommand) || "clearvotes".equals(subCommand)) {
                String playerName = args[1].toLowerCase();
                for (Player player : sender.getServer().getOnlinePlayers()) {
                    if (player.getName().toLowerCase().startsWith(playerName)) {
                        completions.add(player.getName());
                    }
                }
                VoteHistory voteHistory = ApiCommandPlugin.getInstance().getVoteHistory();
                if (voteHistory != null) {
                    for (String name : voteHistory.getPlayerVoteCounts().keySet()) {
                        if (name.startsWith(playerName) && !completions.contains(name)) {
                            completions.add(name);
                        }
                    }
                }
            }
        } else if (args.length == 3) {
            String subCommand = args[0].toLowerCase();
            
            if ("testvote".equals(subCommand)) {
                String serviceName = args[2].toLowerCase();
                completions.addAll(VOTE_SERVICES.stream()
                    .filter(service -> service.startsWith(serviceName))
                    .collect(Collectors.toList()));
            }
        }
        
        return completions;
    }
}
