package com.easyvote;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.logging.Logger;

import static org.junit.Assert.*;

public class RewardDeliveryTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    private DatabaseManager db;
    private VoteHistory history;
    private RewardDelivery delivery;

    @Before public void open() {
        db = new DatabaseManager(folder.getRoot(), Logger.getLogger("EasyVoteTest"));
        db.initialize();
        history = new VoteHistory(db);
        delivery = new RewardDelivery(history, Logger.getLogger("EasyVoteTest"));
    }

    @After public void close() { db.close(); }

    @Test public void offlineVotesSurviveRestartAndUseCaseInsensitiveCounts() {
        assertTrue(history.recordVoteAndQueue("Steve", "site1", "", 1));
        assertFalse(history.recordVoteAndQueue("STEVE", "site2", "", 2));
        db.close();
        open();
        assertEquals(2, history.getPlayerVoteCount("sTeVe"));
        assertEquals(2, history.getPendingRewardCount("STEVE"));
        assertTrue(history.getPendingRewards("steve").get(0).isFirstVote());
        assertFalse(history.getPendingRewards("steve").get(1).isFirstVote());
        assertEquals(0, history.getPlayerVoteCount("Unknown"));
    }

    @Test public void queueInsertFailureRollsBackVoteAndFirstVoteEligibility() throws Exception {
        try (var stmt = db.getConnection().createStatement()) {
            stmt.execute("CREATE TRIGGER reject_reward BEFORE INSERT ON pending_rewards BEGIN SELECT RAISE(FAIL, 'test'); END");
        }
        assertThrows(IllegalStateException.class, () -> history.recordVoteAndQueue("Steve", "site", "", 1));
        assertEquals(0, history.getPlayerVoteCount("Steve"));
        assertTrue(db.getConnection().getAutoCommit());
        try (var stmt = db.getConnection().createStatement()) { stmt.execute("DROP TRIGGER reject_reward"); }
        assertTrue(history.recordVoteAndQueue("Steve", "site", "", 2));
    }

    @Test public void concurrentVotesHaveExactlyOneFirstReward() throws Exception {
        try (var executor = Executors.newFixedThreadPool(4)) {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                results.add(executor.submit(() -> history.recordVoteAndQueue("Steve", "site", "", 1)));
            }
            int first = 0;
            for (var result : results) if (result.get()) first++;
            assertEquals(1, first);
        }
        assertEquals(20, history.getPlayerVoteCount("Steve"));
        assertEquals(20, history.getPendingRewardCount("Steve"));
    }

    @Test public void failedCommandResumesAfterRestartWithoutRepeatingSuccessfulCommands() {
        List<String> commands = List.of("money Steve", "give Steve", "msg Steve");
        List<String> dispatched = new ArrayList<>();
        assertFalse(delivery.deliver("Steve", "vote:1", commands, () -> true, cmd -> {
            dispatched.add(cmd);
            return !cmd.startsWith("give");
        }));
        assertEquals(List.of("money Steve", "give Steve"), dispatched);
        db.close();
        open();
        dispatched.clear();
        assertTrue(delivery.deliver("STEVE", "vote:1", commands, () -> true, cmd -> dispatched.add(cmd)));
        assertEquals(List.of("give Steve", "msg Steve"), dispatched);
    }

    @Test public void exceptionsAndDisconnectsKeepCommandsRetryable() {
        List<String> commands = List.of("money Steve", "give Steve");
        assertFalse(delivery.deliver("Steve", "vote:1", commands, () -> true,
            cmd -> { throw new IllegalStateException("command failed"); }));
        List<String> dispatched = new ArrayList<>();
        assertFalse(delivery.deliver("Steve", "vote:1", commands, dispatched::isEmpty, dispatched::add));
        assertEquals(List.of("money Steve"), dispatched);
        dispatched.clear();
        assertTrue(delivery.deliver("Steve", "vote:1", commands, () -> true, dispatched::add));
        assertEquals(List.of("give Steve"), dispatched);
    }

    @Test public void missingConfigurationCanBeFixedAndRetried() {
        assertFalse(delivery.deliver("Steve", "vote:1", List.of(), () -> true, cmd -> failCommand()));
        assertTrue(delivery.deliver("Steve", "vote:1", List.of("money Steve"), () -> true, cmd -> true));
    }

    @Test public void repairingFailedCommandKeepsSuccessfulPrefix() {
        assertFalse(delivery.deliver("Steve", "vote:1", List.of("money Steve", "typo"), () -> true,
            cmd -> !cmd.equals("typo")));
        List<String> dispatched = new ArrayList<>();
        assertTrue(delivery.deliver("Steve", "vote:1", List.of("money Steve", "give Steve"), () -> true, dispatched::add));
        assertEquals(List.of("give Steve"), dispatched);
    }

    @Test public void configurationReorderingDoesNotSkipUnexecutedRewards() {
        assertFalse(delivery.deliver("Steve", "vote:1", List.of("money", "give"), () -> true,
            cmd -> cmd.equals("money")));
        List<String> dispatched = new ArrayList<>();
        assertTrue(delivery.deliver("Steve", "vote:1", List.of("give", "money"), () -> true, dispatched::add));
        assertEquals(List.of("give"), dispatched);
    }

    @Test public void milestoneFailureDoesNotConsumeMilestoneAndRetryUsesProgress() {
        var tracker = new MilestoneTracker(db);
        List<String> commands = List.of("money", "give");
        if (delivery.deliver("Steve", "milestone:10", commands, () -> true, cmd -> cmd.equals("money"))) {
            tracker.markIfNotReceived("Steve", 10, 1);
        }
        assertFalse(tracker.hasReceived("Steve", 10));
        List<String> dispatched = new ArrayList<>();
        if (delivery.deliver("Steve", "milestone:10", commands, () -> true, dispatched::add)) {
            tracker.markIfNotReceived("Steve", 10, 2);
        }
        assertTrue(tracker.hasReceived("STEVE", 10));
        assertEquals(List.of("give"), dispatched);
    }

    @Test public void databaseFailureIsNotReportedAsZeroVotes() throws Exception {
        try (var stmt = db.getConnection().createStatement()) { stmt.execute("DROP TABLE votes"); }
        assertThrows(IllegalStateException.class, () -> history.getPlayerVoteCount("Steve"));
    }

    @Test public void failedCsvMigrationPreservesSourceAndRestoresAutoCommit() throws Exception {
        var csv = folder.getRoot().toPath().resolve("votes.csv");
        java.nio.file.Files.writeString(csv, "Steve,site,127.0.0.1,123\nAlex,site,127.0.0.1,invalid\n");
        assertThrows(IllegalStateException.class, db::initialize);
        assertTrue(java.nio.file.Files.exists(csv));
        assertTrue(db.getConnection().getAutoCommit());
        assertEquals(0, history.getTotalVotes());
    }

    @Test public void upgradeKeepsExistingPendingRewards() throws Exception {
        history.recordVoteAndQueue("Steve", "site", "", 1);
        try (var stmt = db.getConnection().createStatement()) { stmt.execute("DROP TABLE reward_progress"); }
        db.close();
        open();
        assertEquals(1, history.getPlayerVoteCount("Steve"));
        assertEquals(1, history.getPendingRewardCount("Steve"));
        assertTrue(delivery.deliver("Steve", "vote:1", List.of("money"), () -> true, cmd -> true));
    }

    @Test public void clearPlayerAlsoClearsRewardProgress() {
        history.recordVoteAndQueue("Steve", "site", "", 1);
        delivery.deliver("Steve", "milestone:10", List.of("money"), () -> true, cmd -> true);
        assertEquals(1, history.clearPlayerVotes("STEVE"));
        assertEquals(0, history.getPendingRewardCount("Steve"));
        List<String> dispatched = new ArrayList<>();
        assertTrue(delivery.deliver("Steve", "milestone:10", List.of("money"), () -> true, dispatched::add));
        assertEquals(List.of("money"), dispatched);
    }

    private boolean failCommand() { fail("No command should run"); return false; }
}
