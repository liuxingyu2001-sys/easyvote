package com.easyvote;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Called on the global scheduler; persists progress after each accepted command. */
final class RewardDelivery {
    private final VoteHistory history;
    private final Logger logger;

    RewardDelivery(VoteHistory history, Logger logger) {
        this.history = history;
        this.logger = logger;
    }

    boolean deliver(String playerName, String rewardKey, List<String> commands,
                    BooleanSupplier online, Function<String, Boolean> dispatch) {
        // An absent configuration must not become a permanently empty snapshot.
        if (commands.isEmpty()) {
            logger.warning(playerName + " 未配置奖励 " + rewardKey + "，保留待发状态");
            return false;
        }
        VoteHistory.RewardProgress progress = history.getRewardProgress(playerName, rewardKey, commands);
        for (int i = progress.nextCommand(); i < progress.commands().size(); i++) {
            if (!online.getAsBoolean()) return false;
            String command = progress.commands().get(i);
            try {
                if (!dispatch.apply(command)) {
                    logger.warning(playerName + " 奖励命令失败，保留待发状态: " + command);
                    return false;
                }
            } catch (RuntimeException e) {
                logger.log(Level.WARNING, playerName + " 奖励命令异常，保留待发状态: " + command, e);
                return false;
            }
            history.saveRewardProgress(playerName, rewardKey, i + 1);
        }
        return true;
    }
}
