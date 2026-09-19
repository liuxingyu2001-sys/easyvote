package com.easyvote;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

final class EasyVoteExpansion extends PlaceholderExpansion {
    private final String version;
    private final VotePlaceholders placeholders;
    private final Logger logger;
    private final AtomicLong lastErrorLog = new AtomicLong();

    EasyVoteExpansion(String version, VotePlaceholders placeholders, Logger logger) {
        this.version = version;
        this.placeholders = placeholders;
        this.logger = logger;
    }

    @Override public @NotNull String getIdentifier() { return "easyvote"; }
    @Override public @NotNull String getAuthor() { return "liuxingyu2001"; }
    @Override public @NotNull String getVersion() { return version; }
    @Override public boolean persist() { return true; }

    @Override public @NotNull List<String> getPlaceholders() {
        return List.of("%easyvote_votes%", "%easyvote_pending%", "%easyvote_milestone%",
            "%easyvote_total%", "%easyvote_votes_<玩家名>%", "%easyvote_pending_<玩家名>%",
            "%easyvote_milestone_<玩家名>%");
    }

    @Override public String onRequest(OfflinePlayer player, @NotNull String params) {
        try {
            return placeholders.resolve(player == null ? null : player.getName(), params);
        } catch (IllegalStateException e) {
            // Scoreboards can request the same placeholder every tick; avoid flooding the log.
            long now = System.currentTimeMillis();
            long previous = lastErrorLog.get();
            if (now - previous >= 60_000L && lastErrorLog.compareAndSet(previous, now)) {
                logger.log(Level.WARNING, "投票变量查询失败，保留原变量而非返回错误的 0", e);
            }
            return null;
        }
    }
}
