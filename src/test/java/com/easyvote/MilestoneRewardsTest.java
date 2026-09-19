package com.easyvote;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.logging.Logger;

import static org.junit.Assert.*;

public class MilestoneRewardsTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    private final Logger logger = Logger.getLogger("MilestoneRewardsTest");
    private DatabaseManager db;
    private VoteHistory history;
    private MilestoneTracker tracker;
    private MilestoneRewards rewards;

    @Before public void open() {
        db = new DatabaseManager(folder.getRoot(), logger);
        db.initialize();
        history = new VoteHistory(db);
        tracker = new MilestoneTracker(db);
        rewards = new MilestoneRewards(db, history, tracker, logger);
        rewards.configure(true, List.of(tier(2, "money %player%", "give %player%")));
    }

    @After public void close() { db.close(); }

    private MilestoneReward tier(int count, String... commands) {
        return new MilestoneReward(count, List.of(commands), "钻石", Map.of());
    }

    private void qualify() {
        history.recordVoteAndQueue("Steve", "first-site", "first-address", 200);
        history.recordVoteAndQueue("Steve", "unlock-site", "unlock-address", 100);
    }

    @Test public void reachingMilestoneOnlyMakesItAvailableUntilExplicitClaim() {
        assertEquals(MilestoneRewards.State.LOCKED, rewards.state("Steve", 2));
        qualify();
        assertEquals(1, rewards.available("STEVE").size());
        assertFalse(tracker.hasReceived("Steve", 2));
        db.close();
        open();
        assertEquals(1, rewards.available("steve").size());
        List<String> dispatched = new ArrayList<>();
        assertEquals(MilestoneRewards.ClaimResult.SUCCESS,
            rewards.claim("Steve", "uuid", 2, () -> true, dispatched::add));
        assertEquals(List.of("money Steve", "give Steve"), dispatched);
        assertEquals(MilestoneRewards.State.RECEIVED, rewards.state("STEVE", 2));
        assertTrue(rewards.available("Steve").isEmpty());
        assertEquals(2, history.getPendingRewardCount("Steve"));
    }

    @Test public void rejectsLockedDisabledMissingAndAlreadyReceivedClaims() {
        assertEquals(MilestoneRewards.ClaimResult.LOCKED,
            rewards.claim("Steve", "uuid", 2, () -> true, cmd -> failDispatch()));
        qualify();
        assertEquals(MilestoneRewards.ClaimResult.MISSING,
            rewards.claim("Steve", "uuid", 3, () -> true, cmd -> failDispatch()));
        rewards.configure(false, rewards.rewards());
        assertTrue(rewards.available("Steve").isEmpty());
        assertEquals(MilestoneRewards.ClaimResult.DISABLED,
            rewards.claim("Steve", "uuid", 2, () -> true, cmd -> failDispatch()));
        rewards.configure(true, rewards.rewards());
        tracker.markIfNotReceived("Steve", 2, 1); // Existing auto-delivered tiers survive the upgrade.
        assertEquals(MilestoneRewards.ClaimResult.RECEIVED,
            rewards.claim("STEVE", "uuid", 2, () -> true, cmd -> failDispatch()));
    }

    @Test public void partialFailureRequiresAnotherClaimAndResumesAfterRestart() {
        qualify();
        assertEquals(MilestoneRewards.ClaimResult.FAILED,
            rewards.claim("Steve", "uuid", 2, () -> true, cmd -> cmd.startsWith("money")));
        assertFalse(tracker.hasReceived("Steve", 2));
        db.close();
        open();
        assertEquals(1, rewards.available("Steve").size());
        List<String> dispatched = new ArrayList<>();
        assertEquals(MilestoneRewards.ClaimResult.SUCCESS,
            rewards.claim("Steve", "uuid", 2, () -> true, dispatched::add));
        assertEquals(List.of("give Steve"), dispatched);
        assertTrue(tracker.hasReceived("Steve", 2));
    }

    @Test public void concurrentClicksDispatchEachCommandOnce() throws Exception {
        qualify();
        List<String> dispatched = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(4)) {
            List<Future<MilestoneRewards.ClaimResult>> results = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                results.add(executor.submit(() -> rewards.claim("Steve", "uuid", 2, () -> true, dispatched::add)));
            }
            int successes = 0;
            for (var result : results) if (result.get() == MilestoneRewards.ClaimResult.SUCCESS) successes++;
            assertEquals(1, successes);
        }
        assertEquals(List.of("money Steve", "give Steve"), dispatched);
    }

    @Test public void staleMenuCannotClaimAfterVotesClearedOrTierRemoved() {
        qualify();
        assertEquals(1, rewards.available("Steve").size());
        history.clearPlayerVotes("Steve");
        assertEquals(MilestoneRewards.ClaimResult.LOCKED,
            rewards.claim("Steve", "uuid", 2, () -> true, cmd -> failDispatch()));
        qualify();
        rewards.configure(true, List.of());
        assertEquals(MilestoneRewards.ClaimResult.MISSING,
            rewards.claim("Steve", "uuid", 2, () -> true, cmd -> failDispatch()));
    }

    @Test public void usesUnlockingVoteForServiceAndAddressVariables() {
        qualify();
        history.recordVoteAndQueue("Steve", "later-site", "later-address", 300);
        rewards.configure(true, List.of(tier(2, "reward %player% %player_name% %uuid% %service% %address%")));
        List<String> dispatched = new ArrayList<>();
        rewards.claim("Steve", "test-uuid", 2, () -> true, dispatched::add);
        assertEquals(List.of("reward Steve Steve test-uuid unlock-site unlock-address"), dispatched);
    }

    @Test public void disconnectDoesNotConsumeMilestone() {
        qualify();
        assertEquals(MilestoneRewards.ClaimResult.FAILED,
            rewards.claim("Steve", "uuid", 2, () -> false, cmd -> failDispatch()));
        assertFalse(tracker.hasReceived("Steve", 2));
    }

    @Test public void missingCommandsRemainClaimableAfterConfigurationRepair() {
        qualify();
        rewards.configure(true, List.of(tier(2)));
        assertEquals(MilestoneRewards.ClaimResult.FAILED,
            rewards.claim("Steve", "uuid", 2, () -> true, cmd -> failDispatch()));
        rewards.configure(true, List.of(tier(2, "repaired")));
        assertEquals(MilestoneRewards.ClaimResult.SUCCESS,
            rewards.claim("Steve", "uuid", 2, () -> true, cmd -> true));
    }

    private boolean failDispatch() { fail("Unexpected automatic or ineligible reward"); return false; }
}
