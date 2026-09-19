package com.easyvote;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.Assert.*;

public class VotePlaceholdersTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    private DatabaseManager db;
    private VoteHistory history;
    private MilestoneTracker milestones;
    private VotePlaceholders placeholders;
    private EasyVoteExpansion expansion;

    @Before public void setup() {
        Logger logger = Logger.getAnonymousLogger();
        logger.setLevel(Level.OFF);
        db = new DatabaseManager(folder.getRoot(), logger);
        db.initialize();
        history = new VoteHistory(db);
        milestones = new MilestoneTracker(db);
        placeholders = new VotePlaceholders(history, milestones);
        expansion = new EasyVoteExpansion("test", placeholders, logger);
        history.recordVoteAndQueue("Steve_Test", "site1", "", 1);
        history.recordVoteAndQueue("Steve_Test", "site2", "", 2);
        history.recordVoteAndQueue("Alex", "site1", "", 3);
    }

    @After public void close() { db.close(); }

    @Test public void currentPlayerUsesAllServicesAndIgnoresCase() {
        assertEquals("2", placeholders.resolve("STEVE_TEST", "VoTeS"));
        assertEquals("2", placeholders.resolve("steve_test", "pending"));
        assertEquals("3", placeholders.resolve(null, "total"));
    }

    @Test public void namedOfflinePlayerWorksWithoutContextAndPreservesUnderscores() {
        assertEquals("2", expansion.onRequest(null, "votes_Steve_Test"));
        assertEquals("2", expansion.onRequest(null, "pending_STEVE_TEST"));
        assertEquals("2", placeholders.resolve("Alex", "votes_Steve_Test"));
    }

    @Test public void unknownPlayerHasZeroStatsWithoutCreatingRecords() {
        assertEquals("0", expansion.onRequest(null, "votes_Unknown"));
        assertEquals("0", expansion.onRequest(null, "pending_Unknown"));
        assertEquals("0", expansion.onRequest(null, "milestone_Unknown"));
        assertEquals(3, history.getTotalVotes());
        assertEquals(2, history.getPlayerVoteCounts().size());
    }

    @Test public void missingContextAndUnknownParametersStayUnresolved() {
        assertNull(expansion.onRequest(null, "votes"));
        assertNull(placeholders.resolve(null, "pending"));
        assertNull(placeholders.resolve(null, "milestone"));
        assertNull(placeholders.resolve("Steve_Test", "votes_"));
        assertNull(placeholders.resolve("Steve_Test", "unknown"));
        assertNull(placeholders.resolve("Steve_Test", "total_Alex"));
    }

    @Test public void milestoneMeansHighestReceivedNotVoteCount() {
        assertEquals("0", placeholders.resolve("Steve_Test", "milestone"));
        milestones.markIfNotReceived("Steve_Test", 1, 1);
        milestones.markIfNotReceived("Steve_Test", 2, 2);
        assertEquals("2", expansion.onRequest(null, "milestone_STEVE_TEST"));
    }

    @Test public void rewardCompletionAndClearingAreImmediatelyVisible() {
        history.deletePendingReward(history.getPendingRewards("Steve_Test").getFirst().getId());
        assertEquals("1", placeholders.resolve("Steve_Test", "pending"));
        assertEquals("2", placeholders.resolve("Steve_Test", "votes"));
        history.clearPlayerVotes("Steve_Test");
        assertEquals("0", placeholders.resolve("Steve_Test", "votes"));
        assertEquals("0", placeholders.resolve("Steve_Test", "pending"));
        assertEquals("1", expansion.onRequest(null, "total"));
    }

    @Test public void databaseErrorsDoNotTurnIntoZeroVotes() throws Exception {
        try (var statement = db.getConnection().createStatement()) { statement.execute("DROP TABLE votes"); }
        assertNull(expansion.onRequest(null, "votes_Steve_Test"));
        assertNull(expansion.onRequest(null, "total"));
    }

    @Test public void expansionSurvivesPlaceholderApiReload() {
        assertTrue(expansion.persist());
        assertEquals("easyvote", expansion.getIdentifier());
        assertEquals("test", expansion.getVersion());
    }
}
