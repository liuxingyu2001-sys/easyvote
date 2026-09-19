package com.easyvote;

import org.junit.Test;
import static org.junit.Assert.assertArrayEquals;

public class VotifierServerTest {
    @Test public void standardFieldsPreserveSpacesInWebsiteName() {
        assertArrayEquals(new String[] {"Minecraft Server List", "Steve", "127.0.0.1", "123"},
            VotifierServer.parseVoteFields("VOTE\nMinecraft Server List\nSteve\n127.0.0.1\n123\n"));
    }

    @Test public void standardFormatAllowsEmptyAddress() {
        assertArrayEquals(new String[] {"site", "Steve", "", "123"},
            VotifierServer.parseVoteFields("VOTE\r\nsite\r\nSteve\r\n\r\n123\r\n"));
    }

    @Test public void legacySpaceSeparatedFormatStillWorks() {
        assertArrayEquals(new String[] {"site", "Steve", "127.0.0.1", "123"},
            VotifierServer.parseVoteFields("VOTE site Steve 127.0.0.1 123"));
        assertArrayEquals(new String[0], VotifierServer.parseVoteFields("invalid"));
    }
}
