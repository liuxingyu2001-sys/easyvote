package com.easyvote;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import java.util.Locale;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class EasyVoteTabCompleter implements TabCompleter {

    private static final List<String> SUB_COMMANDS = Arrays.asList(
        "reload", "pubkey", "votestats", "votes", "testvote", "clearvotes"
    );

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();

        if (args.length == 1 && sender instanceof Player && sender.hasPermission("easyvote.rewards")
                && "rewards".startsWith(args[0].toLowerCase(Locale.ROOT))) completions.add("rewards");
        if (!sender.hasPermission("easyvote.admin")) {
            return completions;
        }

        if (args.length == 1) {
            String subCommand = args[0].toLowerCase(Locale.ROOT);
            completions.addAll(SUB_COMMANDS.stream()
                .filter(cmd -> cmd.startsWith(subCommand))
                .collect(Collectors.toList()));
        } else if (args.length == 2) {
            String subCommand = args[0].toLowerCase(Locale.ROOT);

            if (List.of("testvote", "clearvotes", "votes", "votestats").contains(subCommand)) {
                String playerName = args[1].toLowerCase(Locale.ROOT);
                for (Player player : sender.getServer().getOnlinePlayers()) {
                    if (player.getName().toLowerCase(Locale.ROOT).startsWith(playerName)) {
                        completions.add(player.getName());
                    }
                }
                VoteHistory voteHistory = EasyVotePlugin.getInstance().getVoteHistory();
                if (voteHistory != null) {
                    for (String name : voteHistory.getPlayerVoteCounts().keySet()) {
                        if (name.startsWith(playerName) && !completions.contains(name)) {
                            completions.add(name);
                        }
                    }
                }
            }
        }

        return completions;
    }
}
