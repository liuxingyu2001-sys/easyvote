package com.easyvote;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.logging.Logger;

/** Eligibility is read-only. Only an explicit claim may dispatch milestone commands. */
final class MilestoneRewards {
    enum State { LOCKED, AVAILABLE, RECEIVED }
    enum ClaimResult { SUCCESS, LOCKED, RECEIVED, DISABLED, MISSING, BUSY, FAILED }

    private final DatabaseManager db;
    private final VoteHistory history;
    private final MilestoneTracker tracker;
    private final RewardDelivery delivery;
    private final Set<String> claiming = new HashSet<>();
    private List<MilestoneReward> rewards = List.of();
    private boolean enabled;

    MilestoneRewards(DatabaseManager db, VoteHistory history, MilestoneTracker tracker, Logger logger) {
        this.db = db;
        this.history = history;
        this.tracker = tracker;
        this.delivery = new RewardDelivery(history, logger);
    }

    void configure(boolean enabled, List<MilestoneReward> rewards) {
        synchronized (db) {
            this.enabled = enabled;
            this.rewards = List.copyOf(rewards);
        }
    }

    boolean enabled() { synchronized (db) { return enabled; } }
    List<MilestoneReward> rewards() { synchronized (db) { return rewards; } }

    State state(String playerName, int count) {
        synchronized (db) {
            if (tracker.hasReceived(playerName, count)) return State.RECEIVED;
            return history.getPlayerVoteCount(playerName) >= count ? State.AVAILABLE : State.LOCKED;
        }
    }

    List<MilestoneReward> available(String playerName) {
        synchronized (db) {
            if (!enabled) return List.of();
            return rewards.stream().filter(reward -> state(playerName, reward.count()) == State.AVAILABLE).toList();
        }
    }

    /** Called on the global scheduler. Rechecks current config, votes and receipt under the shared lock. */
    ClaimResult claim(String playerName, String uuid, int count, BooleanSupplier online,
                      Function<String, Boolean> dispatch) {
        synchronized (db) {
            if (!enabled) return ClaimResult.DISABLED;
            MilestoneReward reward = rewards.stream().filter(r -> r.count() == count).findFirst().orElse(null);
            if (reward == null) return ClaimResult.MISSING;
            State state = state(playerName, count);
            if (state == State.RECEIVED) return ClaimResult.RECEIVED;
            if (state == State.LOCKED) return ClaimResult.LOCKED;
            String playerKey = playerName.toLowerCase(Locale.ROOT);
            if (!claiming.add(playerKey)) return ClaimResult.BUSY;
            try {
                // Use the vote that unlocked this tier, even when it was received offline long ago.
                VoteHistory.VoteRecord vote = history.getPlayerVoteAt(playerName, count);
                List<String> commands = reward.commands().stream().map(command -> command
                    .replace("%player%", playerName).replace("%player_name%", playerName)
                    .replace("%uuid%", uuid).replace("%service%", vote.getServiceName())
                    .replace("%address%", vote.getAddress() == null ? "" : vote.getAddress())).toList();
                if (!delivery.deliver(playerName, "milestone:" + count, commands, online, dispatch)) {
                    return ClaimResult.FAILED;
                }
                tracker.markIfNotReceived(playerName, count, System.currentTimeMillis());
                return ClaimResult.SUCCESS;
            } finally {
                claiming.remove(playerKey);
            }
        }
    }
}
