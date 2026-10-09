package dev.lodekeeper.core;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class TravelCommandParserTest {
    private final CommandParser parser = new CommandParser();

    @Test void parsesFiniteTravelGoalsAndPinsOnlyTheSelector() {
        assertEquals(new CommandParser.GotoCommand(new TravelGoal.PointGoal(-24, 64, 30)), parser.parse("goto -24 64 30").command());
        assertEquals(new CommandParser.ExploreCommand(new TravelGoal.ExploreGoal(64, 4)), parser.parse("explore").command());
        assertEquals(new CommandParser.ExploreCommand(new TravelGoal.ExploreGoal(256, 16)), parser.parse("explore 256 16").command());
        assertEquals(new CommandParser.FollowCommand("Exact_Name", 120), parser.parse("follow Exact_Name").command());
        String id = UUID.fromString("00000000-0000-4000-8000-000000000001").toString();
        assertEquals(new CommandParser.FollowCommand(id, 600), parser.parse("follow " + id + " 600").command());
    }
    @Test void refusesRelativeOverflowAndUnboundedTravel() {
        for (String input : new String[]{"goto ~1 64 1", "goto 01 64 1", "goto +1 64 1", "goto 1.5 64 1",
                "goto 2147483648 64 0", "goto 30000001 64 0", "goto 1 4097 1", "goto 1 64",
                "explore 15", "explore 257", "explore 64 0", "explore 64 17", "explore 64 2 extra",
                "follow Player 0", "follow Player 601", "follow * 30", "follow Player extra"})
            assertFalse(parser.parse(input).success(), input);
    }
    @Test void scopesControlsAndPreservesPlainCancel() {
        assertEquals(new CommandParser.WaypointCommand(CommandParser.WaypointAction.SET, "home-2"), parser.parse("waypoint set Home-2").command());
        assertEquals(new CommandParser.WaypointCommand(CommandParser.WaypointAction.LIST, null), parser.parse("waypoint list").command());
        assertEquals(new CommandParser.CacheCommand(CommandParser.CacheAction.CLEAR), parser.parse("cache clear").command());
        assertEquals(new CommandParser.CancelCommand(Long.MAX_VALUE), parser.parse("cancel 9223372036854775807").command());
        assertInstanceOf(CommandParser.StopCommand.class, parser.parse("cancel\t").command());
        for (String input : new String[]{"cancel 0", "cancel 01", "cancel 9223372036854775808", "stop 1",
                "waypoint set ../../escape", "waypoint list extra", "cache clear extra"})
            assertFalse(parser.parse(input).success(), input);
    }
    @Test void keepsLegacyWhitespaceAndRejectsTravelControls() {
        assertEquals(new CommandParser.GetCommand("oak_log", 2), parser.parse("get\toak_log\n2").command());
        assertEquals(new CommandParser.GetCommand("oak_log", 1), parser.parse("get oak_log\u0001").command());
        assertEquals(new CommandParser.ProjectCommand("starter"), parser.parse("project starter").command());
        assertEquals(new CommandParser.MaintainCommand("oak_log", 2), parser.parse("maintain\toak_log 2").command());
        for (String input : new String[]{"goto\t1 64 1", "follow Player\n30", "waypoint set home\u0001", "cancel\t1"})
            assertFalse(parser.parse(input).success(), input);
    }
    @Test void localPrefixInterceptionRemainsExact() {
        assertEquals("goto 1 64 2", CommandParser.clientCommandBody("!lk goto 1 64 2", "!lk "));
        assertNull(CommandParser.clientCommandBody("hello !lk goto 1 64 2", "!lk "));
        assertEquals("", CommandParser.clientCommandBody("!lk", "!lk "));
    }
    @Test void deferredServicesHaveNoPublishedParserBranch() {
        for (String input : new String[]{"storage list", "retrieve box oak_log 1", "offer 00000000-0000-4000-8000-000000000001 oak_log 1",
                "remote list", "handoff Player oak_log 1"}) assertFalse(parser.parse(input).success(), input);
    }
}
