package com.easyvote;

import java.util.Locale;

/** Read-only queries; no online-player lookup or vote/reward mutation. */
final class VotePlaceholders {
    private final VoteHistory history;
    private final MilestoneTracker milestones;

    VotePlaceholders(VoteHistory history, MilestoneTracker milestones) {
        this.history = history;
        this.milestones = milestones;
    }

    String resolve(String contextPlayerName, String params) {
        if (params == null) return null;
        String key = params.toLowerCase(Locale.ROOT);
        if (key.equals("total")) return Integer.toString(history.getTotalVotes());

        // Split only once: Minecraft player names can themselves contain underscores.
        int separator = key.indexOf('_');
        String metric = separator < 0 ? key : key.substring(0, separator);
        if (!metric.equals("votes") && !metric.equals("pending") && !metric.equals("milestone")) return null;
        String playerName = separator < 0 ? contextPlayerName : params.substring(separator + 1);
        if (playerName == null || playerName.isBlank()) return null;

        return switch (metric) {
            case "votes" -> Integer.toString(history.getPlayerVoteCount(playerName));
            case "pending" -> Integer.toString(history.getPendingRewardCount(playerName));
            case "milestone" -> Integer.toString(milestones.getPlayerHighestMilestone(playerName));
            default -> null;
        };
    }
}
