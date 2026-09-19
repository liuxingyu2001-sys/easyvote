package com.easyvote;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.easyvote.VoteHistory.VoteResult.*;
import static org.junit.Assert.*;

public class DailyVoteLimitTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    private DatabaseManager db;
    private VoteHistory history;
    private final Instant today = Instant.parse("2026-09-19T12:00:00Z");

    @Before public void open() {
        Logger logger = Logger.getAnonymousLogger();
        logger.setLevel(Level.OFF);
        db = new DatabaseManager(folder.getRoot(), logger);
        db.initialize();
        history = at(today, "Asia/Shanghai");
    }

    @After public void close() { db.close(); }

    private VoteHistory at(Instant time, String zone) {
        return new VoteHistory(db, Clock.fixed(time, ZoneId.of(zone)));
    }

    @Test public void oneVoteAcrossSitesAndCaseVariantsWithIndependentPlayers() {
        assertEquals(FIRST_VOTE, history.recordVoteAndQueue("Steve", "site1", "", 1, 1));
        assertEquals(DAILY_LIMIT_REACHED, history.recordVoteAndQueue("STEVE", "site2", "", 2, 1));
        assertEquals(1, history.getPlayerVoteCount("Steve"));
        assertEquals(1, history.getPendingRewardCount("Steve"));
        assertEquals(FIRST_VOTE, history.recordVoteAndQueue("Alex", "site2", "", 2, 1));
    }

    @Test public void receiveTimeCannotBeBypassedWithWebsiteTimestamp() {
        assertEquals(FIRST_VOTE, history.recordVoteAndQueue("Steve", "site", "", 0, 1));
        assertEquals(DAILY_LIMIT_REACHED,
            history.recordVoteAndQueue("Steve", "site", "", Long.MAX_VALUE, 1));
    }

    @Test public void newLocalDayResetsAtMidnightWithoutResettingFirstVoteOrPendingRewards() {
        history = at(Instant.parse("2026-09-19T15:59:59Z"), "Asia/Shanghai");
        assertEquals(FIRST_VOTE, history.recordVoteAndQueue("Steve", "site", "", 1, 1));
        history = at(Instant.parse("2026-09-19T16:00:00Z"), "Asia/Shanghai");
        assertEquals(ACCEPTED, history.recordVoteAndQueue("Steve", "site", "", 1, 1));
        assertEquals(DAILY_LIMIT_REACHED, history.recordVoteAndQueue("Steve", "site", "", 1, 1));
        assertEquals(2, history.getPendingRewardCount("Steve"));
        assertFalse(history.getPendingRewards("Steve").get(1).isFirstVote());
    }

    @Test public void daylightSavingDayEndsAtLocalMidnightInsteadOfAfter24Hours() {
        history = at(Instant.parse("2026-03-08T05:00:00Z"), "America/New_York");
        assertEquals(FIRST_VOTE, history.recordVoteAndQueue("Steve", "site", "", 1, 1));
        history = at(Instant.parse("2026-03-09T03:59:59Z"), "America/New_York");
        assertEquals(DAILY_LIMIT_REACHED, history.recordVoteAndQueue("Steve", "site", "", 1, 1));
        history = at(Instant.parse("2026-03-09T04:00:00Z"), "America/New_York");
        assertEquals(ACCEPTED, history.recordVoteAndQueue("Steve", "site", "", 1, 1));
    }

    @Test public void changedLimitUsesExistingVotesAndZeroDisablesLimit() {
        assertEquals(FIRST_VOTE, history.recordVoteAndQueue("Steve", "site", "", 1, 1));
        assertEquals(ACCEPTED, history.recordVoteAndQueue("Steve", "site", "", 1, 2));
        assertEquals(DAILY_LIMIT_REACHED, history.recordVoteAndQueue("Steve", "site", "", 1, 2));
        assertEquals(ACCEPTED, history.recordVoteAndQueue("Steve", "site", "", 1, 0));
        assertEquals(DAILY_LIMIT_REACHED, history.recordVoteAndQueue("Steve", "site", "", 1, 1));
    }

    @Test public void restartAndRewardDeliveryDoNotRestoreQuota() {
        history.recordVoteAndQueue("Steve", "site", "", 1, 1);
        history.deletePendingReward(history.getPendingRewards("Steve").getFirst().getId());
        db.close();
        open();
        assertEquals(DAILY_LIMIT_REACHED, history.recordVoteAndQueue("Steve", "site", "", 1, 1));
        assertEquals(0, history.getPendingRewardCount("Steve"));
        history.clearPlayerVotes("Steve");
        assertEquals(FIRST_VOTE, history.recordVoteAndQueue("Steve", "site", "", 1, 1));
    }

    @Test public void concurrentVotesCannotExceedLimit() throws Exception {
        try (var executor = Executors.newFixedThreadPool(4)) {
            var results = new ArrayList<Future<VoteHistory.VoteResult>>();
            for (int i = 0; i < 20; i++) {
                results.add(executor.submit(() -> history.recordVoteAndQueue("Steve", "site", "", 1, 1)));
            }
            int accepted = 0;
            for (var result : results) if (result.get() != DAILY_LIMIT_REACHED) accepted++;
            assertEquals(1, accepted);
        }
        assertEquals(1, history.getTotalVotes());
        assertEquals(1, history.getPendingRewardCount("Steve"));
    }

    @Test public void failedRewardInsertDoesNotConsumeQuota() throws Exception {
        try (var statement = db.getConnection().createStatement()) {
            statement.execute("CREATE TRIGGER reject_reward BEFORE INSERT ON pending_rewards BEGIN SELECT RAISE(FAIL, 'test'); END");
        }
        assertThrows(IllegalStateException.class,
            () -> history.recordVoteAndQueue("Steve", "site", "", 1, 1));
        try (var statement = db.getConnection().createStatement()) { statement.execute("DROP TRIGGER reject_reward"); }
        assertEquals(FIRST_VOTE, history.recordVoteAndQueue("Steve", "site", "", 1, 1));
    }

    @Test public void upgradesCountLegacySecondsAndMillisecondsOnTheirRecordedDate() throws Exception {
        try (var statement = db.getConnection().createStatement()) {
            statement.execute("DROP INDEX idx_votes_player_received");
            statement.execute("ALTER TABLE votes DROP COLUMN received_at");
            statement.execute("INSERT INTO votes (player_name, service_name, timestamp) VALUES "
                + "('steve', 'site', " + today.getEpochSecond() + "),"
                + "('alex', 'site', " + today.toEpochMilli() + ")");
        }
        db.initialize();
        assertEquals(DAILY_LIMIT_REACHED, history.recordVoteAndQueue("Steve", "site", "", 1, 1));
        assertEquals(DAILY_LIMIT_REACHED, history.recordVoteAndQueue("Alex", "site", "", 1, 1));
        assertEquals(2, history.getTotalVotes());
    }
}
