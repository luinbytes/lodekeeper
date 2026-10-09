package dev.lodekeeper.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** Parses command bodies after the Fabric adapter has removed the configured client-only prefix. */
public final class CommandParser {
    public static final int MAX_BODY_LENGTH = 512;
    public static final int MAX_ITEM_NAME_LENGTH = 256;
    public static final int MAX_CONFIG_VALUE_LENGTH = 256;
    public static final int MAX_REQUEST_COUNT = 1_000_000;

    public sealed interface Command permits HelpCommand, GetCommand, StopCommand, PauseCommand, ResumeCommand,
            StatusCommand, QueueCommand, ClearCommand, PlanCommand, ConfigCommand,
            ProjectCommand, ProjectsCommand, MaintainCommand, UnmaintainCommand, MaintainedCommand, ClaimCommand,
            GotoCommand, ExploreCommand, FollowCommand, WaypointCommand, CacheCommand, CancelCommand { }

    public record GotoCommand(TravelGoal.PointGoal goal) implements Command {
        public GotoCommand { Objects.requireNonNull(goal, "goal"); }
    }
    public record ExploreCommand(TravelGoal.ExploreGoal goal) implements Command {
        public ExploreCommand { Objects.requireNonNull(goal, "goal"); }
    }
    public record FollowCommand(String selector, int seconds) implements Command {
        public FollowCommand {
            selector = validatePlayerSelector(selector);
            if (seconds < 1 || seconds > 600) throw new IllegalArgumentException("Follow requires 1..600 seconds.");
        }
    }
    public enum WaypointAction { SET, GOTO, REMOVE, LIST }
    public record WaypointCommand(WaypointAction action, String label) implements Command {
        public WaypointCommand {
            Objects.requireNonNull(action, "action");
            if (action == WaypointAction.LIST) {
                if (label != null) throw new IllegalArgumentException("waypoint list takes no arguments.");
            } else label = validateWaypointLabel(label);
        }
    }
    public enum CacheAction { STATUS, CLEAR }
    public record CacheCommand(CacheAction action) implements Command {
        public CacheCommand { Objects.requireNonNull(action, "action"); }
    }
    public record CancelCommand(long jobToken) implements Command {
        public CancelCommand {
            if (jobToken < 1) throw new IllegalArgumentException("Job token must be positive.");
        }
    }
    public record GetCommand(String item, int count) implements Command {
        public GetCommand {
            item = validateItem(item);
            validateCount(count);
        }
    }
    public record HelpCommand() implements Command { }
    public record StopCommand() implements Command { }
    public record PauseCommand() implements Command { }
    public record ResumeCommand() implements Command { }
    public record StatusCommand() implements Command { }
    public record QueueCommand() implements Command { }
    public record ClearCommand() implements Command { }
    /** Empty item means preview the current active or queued plan. */
    public record PlanCommand(String item, int count) implements Command {
        public PlanCommand {
            if (item != null) item = validateItem(item);
            if (item == null && count != 1) throw new IllegalArgumentException("A count needs a plan item");
            validateCount(count);
        }
    }
    /** Null key/value requests a config summary; null value reads a single key. */
    public record ConfigCommand(String key, String value) implements Command {
        public ConfigCommand {
            if (key != null) {
                key = key.trim();
                if (key.isEmpty() || key.length() > 64) throw new IllegalArgumentException("Config key must be 1..64 characters");
            }
            if (value != null && value.length() > MAX_CONFIG_VALUE_LENGTH) throw new IllegalArgumentException("Config value is too long");
        }
    }
    public record ProjectCommand(String name) implements Command {
        public ProjectCommand { name = ProjectSpec.normalizeName(name); }
    }
    public record ProjectsCommand() implements Command { }
    public record MaintainCommand(String item, int count) implements Command {
        public MaintainCommand {
            item = validateItem(item);
            validateCount(count);
        }
    }
    /** The normalized item is "all" when clearing every maintained target. */
    public record UnmaintainCommand(String item) implements Command {
        public UnmaintainCommand {
            Objects.requireNonNull(item, "item");
            item = item.trim();
            if (item.equalsIgnoreCase("all")) item = "all";
            else item = validateItem(item);
        }
        public boolean all() { return item.equals("all"); }
    }
    public record MaintainedCommand() implements Command { }
    public enum ClaimAction { POS1, POS2, ADD, LIST, REMOVE, PREFER, CLEAR_SELECTION }

    public record ClaimCommand(ClaimAction action, String name, boolean preferred) implements Command {
        public ClaimCommand {
            Objects.requireNonNull(action, "action");
            if (action == ClaimAction.ADD || action == ClaimAction.REMOVE || action == ClaimAction.PREFER) {
                name = validateClaimName(name);
            } else if (name != null) {
                throw new IllegalArgumentException("This claim action takes no name.");
            }
            if (preferred && action != ClaimAction.ADD && action != ClaimAction.PREFER) {
                throw new IllegalArgumentException("This claim action takes no preferred setting.");
            }
        }
    }

    public record ParseError(String message, String usage) {
        public ParseError {
            Objects.requireNonNull(message, "message");
            Objects.requireNonNull(usage, "usage");
        }
    }
    public record ParseResult(Command command, ParseError error) {
        public ParseResult {
            if ((command == null) == (error == null)) throw new IllegalArgumentException("Result must hold command or error");
        }
        public boolean success() { return command != null; }
        public static ParseResult command(Command command) { return new ParseResult(Objects.requireNonNull(command), null); }
        public static ParseResult error(String message) { return new ParseResult(null, new ParseError(message, USAGE)); }
    }

    public static final String USAGE = "Commands: help, get <item> [count], project <name>, projects, maintain <item> <count>, unmaintain <item|all>, maintained, stop, pause, resume, status, queue, clear, plan [item [count]], config [key [value]], claim pos1|pos2|list|clear, claim add <name> [preferred], claim remove <name>, claim prefer <name> <true|false>, goto <x> <feetY> <z>, explore [radius [segments]], follow <exactName|UUID> [seconds], waypoint set|goto|remove <name>, waypoint list, cache status|clear, cancel <jobToken>";

    /** Returns the local command body, or null when chat does not match the exact prefix. */
    public static String clientCommandBody(String message, String prefix) {
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(prefix, "prefix");
        if (prefix.isBlank()) return null;
        if (message.startsWith(prefix)) return message.substring(prefix.length());
        String barePrefix = prefix.stripTrailing();
        if (barePrefix.length() < prefix.length() && message.stripTrailing().equals(barePrefix)) return "";
        return null;
    }

    public ParseResult parse(String body) {
        if (body == null) return ParseResult.error("Command is empty.");
        if (body.length() > MAX_BODY_LENGTH) return ParseResult.error("Command is too long (maximum " + MAX_BODY_LENGTH + " characters).");
        List<String> tokens;
        try {
            tokens = tokenize(body.trim());
        } catch (IllegalArgumentException exception) {
            return ParseResult.error(exception.getMessage());
        }
        if (tokens.isEmpty()) return ParseResult.command(new HelpCommand());
        if (tokens.size() > 16) return ParseResult.error("Too many command arguments.");
        String name = tokens.get(0).toLowerCase(Locale.ROOT);
        boolean restrictedInput = switch (name) {
            case "goto", "explore", "follow", "waypoint", "cache" -> true;
            case "cancel" -> tokens.size() > 1;
            default -> false;
        };
        if (restrictedInput && body.codePoints().anyMatch(Character::isISOControl))
            return ParseResult.error("Command contains control characters.");
        try {
            return switch (name) {
                case "help", "?" -> noArguments(tokens, new HelpCommand(), "help takes no arguments.");
                case "get" -> parseGet(tokens);
                case "goto" -> parseGoto(tokens);
                case "explore" -> parseExplore(tokens);
                case "follow" -> parseFollow(tokens);
                case "waypoint" -> parseWaypoint(tokens);
                case "cache" -> parseCache(tokens);
                case "cancel" -> parseCancel(tokens);
                case "stop" -> noArguments(tokens, new StopCommand(), "stop takes no arguments.");
                case "pause" -> noArguments(tokens, new PauseCommand(), "pause takes no arguments.");
                case "resume" -> noArguments(tokens, new ResumeCommand(), "resume takes no arguments.");
                case "status" -> noArguments(tokens, new StatusCommand(), "status takes no arguments.");
                case "queue" -> noArguments(tokens, new QueueCommand(), "queue takes no arguments.");
                case "clear" -> noArguments(tokens, new ClearCommand(), "clear takes no arguments.");
                case "plan" -> parsePlan(tokens);
                case "config" -> parseConfig(tokens);
                case "claim" -> parseClaim(tokens);
                case "project" -> parseProject(tokens);
                case "projects" -> noArguments(tokens, new ProjectsCommand(), "projects takes no arguments.");
                case "maintain" -> parseMaintain(tokens);
                case "unmaintain" -> parseUnmaintain(tokens);
                case "maintained" -> noArguments(tokens, new MaintainedCommand(), "maintained takes no arguments.");
                default -> ParseResult.error("Unknown command: " + tokens.get(0));
            };
        } catch (IllegalArgumentException exception) {
            return ParseResult.error(exception.getMessage());
        }
    }

    private static ParseResult parseGet(List<String> tokens) {
        if (tokens.size() < 2 || tokens.size() > 3) return ParseResult.error("Usage: get <item> [count]");
        int count = tokens.size() == 3 ? parseCount(tokens.get(2)) : 1;
        return ParseResult.command(new GetCommand(tokens.get(1), count));
    }

    private static ParseResult parseGoto(List<String> tokens) {
        if (tokens.size() != 4) return ParseResult.error("Usage: goto <x> <feetY> <z>");
        return ParseResult.command(new GotoCommand(new TravelGoal.PointGoal(
                parseInteger(tokens.get(1)), parseInteger(tokens.get(2)), parseInteger(tokens.get(3)))));
    }

    private static ParseResult parseExplore(List<String> tokens) {
        if (tokens.size() > 3) return ParseResult.error("Usage: explore [radius [segments]]");
        int radius = tokens.size() > 1 ? parseInteger(tokens.get(1)) : 64;
        int segments = tokens.size() > 2 ? parseInteger(tokens.get(2)) : 4;
        return ParseResult.command(new ExploreCommand(new TravelGoal.ExploreGoal(radius, segments)));
    }

    private static ParseResult parseFollow(List<String> tokens) {
        if (tokens.size() < 2 || tokens.size() > 3) return ParseResult.error("Usage: follow <exactName|UUID> [seconds]");
        int seconds = tokens.size() == 3 ? parseInteger(tokens.get(2)) : 120;
        return ParseResult.command(new FollowCommand(tokens.get(1), seconds));
    }

    private static ParseResult parseWaypoint(List<String> tokens) {
        if (tokens.size() < 2) return ParseResult.error("Usage: waypoint set|goto|remove <name>, waypoint list");
        WaypointAction action = WaypointAction.valueOf(tokens.get(1).toUpperCase(Locale.ROOT));
        if (action == WaypointAction.LIST)
            return tokens.size() == 2 ? ParseResult.command(new WaypointCommand(action, null))
                    : ParseResult.error("waypoint list takes no arguments.");
        return tokens.size() == 3 ? ParseResult.command(new WaypointCommand(action, tokens.get(2)))
                : ParseResult.error("Usage: waypoint set|goto|remove <name>");
    }

    private static ParseResult parseCache(List<String> tokens) {
        if (tokens.size() != 2) return ParseResult.error("Usage: cache status|clear");
        return ParseResult.command(new CacheCommand(CacheAction.valueOf(tokens.get(1).toUpperCase(Locale.ROOT))));
    }

    private static ParseResult parseCancel(List<String> tokens) {
        if (tokens.size() == 1) return ParseResult.command(new StopCommand());
        if (tokens.size() != 2 || !tokens.get(1).matches("[1-9][0-9]{0,18}"))
            return ParseResult.error("Usage: cancel <positive job token>");
        try {
            return ParseResult.command(new CancelCommand(Long.parseLong(tokens.get(1))));
        } catch (NumberFormatException invalid) {
            return ParseResult.error("Job token exceeds the supported range.");
        }
    }

    private static int parseInteger(String value) {
        if (!value.matches("-?(0|[1-9][0-9]{0,9})"))
            throw new IllegalArgumentException("Expected a whole number without relative syntax.");
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("Number exceeds the supported range.");
        }
    }

    private static UUID parseUuid(String value) {
        if (!value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
            throw new IllegalArgumentException("Expected a canonical lowercase UUID.");
        return UUID.fromString(value);
    }

    private static String validatePlayerSelector(String selector) {
        Objects.requireNonNull(selector, "selector");
        if (!selector.matches("[A-Za-z0-9_]{1,16}")) parseUuid(selector);
        return selector;
    }

    private static String validateWaypointLabel(String label) {
        Objects.requireNonNull(label, "label");
        String value = label.toLowerCase(Locale.ROOT);
        if (!value.matches("[a-z0-9][a-z0-9_-]{0,31}"))
            throw new IllegalArgumentException("Waypoint names require 1..32 letters, digits, underscores or hyphens.");
        return value;
    }

    private static ParseResult parsePlan(List<String> tokens) {
        if (tokens.size() == 1) return ParseResult.command(new PlanCommand(null, 1));
        if (tokens.size() > 3) return ParseResult.error("Usage: plan [item [count]]");
        int count = tokens.size() == 3 ? parseCount(tokens.get(2)) : 1;
        return ParseResult.command(new PlanCommand(tokens.get(1), count));
    }

    private static ParseResult parseConfig(List<String> tokens) {
        if (tokens.size() == 1) return ParseResult.command(new ConfigCommand(null, null));
        if (tokens.size() == 2) return ParseResult.command(new ConfigCommand(tokens.get(1), null));
        String value = String.join(" ", tokens.subList(2, tokens.size()));
        if (value.length() > MAX_CONFIG_VALUE_LENGTH) return ParseResult.error("Config value is too long.");
        return ParseResult.command(new ConfigCommand(tokens.get(1), value));
    }

    private static ParseResult parseProject(List<String> tokens) {
        if (tokens.size() != 2) return ParseResult.error("Usage: project <name>");
        return ParseResult.command(new ProjectCommand(tokens.get(1)));
    }

    private static ParseResult parseMaintain(List<String> tokens) {
        if (tokens.size() != 3) return ParseResult.error("Usage: maintain <item> <count>");
        return ParseResult.command(new MaintainCommand(tokens.get(1), parseCount(tokens.get(2))));
    }

    private static ParseResult parseUnmaintain(List<String> tokens) {
        if (tokens.size() != 2) return ParseResult.error("Usage: unmaintain <item|all>");
        return ParseResult.command(new UnmaintainCommand(tokens.get(1)));
    }

    private static ParseResult parseClaim(List<String> tokens) {
        if (tokens.size() < 2) return ParseResult.error("Usage: claim pos1|pos2|add|list|remove|prefer|clear");
        String action = tokens.get(1).toLowerCase(Locale.ROOT);
        return switch (action) {
            case "pos1", "pos2", "list", "clear" -> {
                if (tokens.size() != 2) yield ParseResult.error("claim " + action + " takes no arguments.");
                ClaimAction kind = switch (action) {
                    case "pos1" -> ClaimAction.POS1;
                    case "pos2" -> ClaimAction.POS2;
                    case "list" -> ClaimAction.LIST;
                    default -> ClaimAction.CLEAR_SELECTION;
                };
                yield ParseResult.command(new ClaimCommand(kind, null, false));
            }
            case "add" -> {
                if (tokens.size() < 3 || tokens.size() > 4
                        || tokens.size() == 4 && !tokens.get(3).equalsIgnoreCase("preferred"))
                    yield ParseResult.error("Usage: claim add <name> [preferred]");
                yield ParseResult.command(new ClaimCommand(ClaimAction.ADD, tokens.get(2), tokens.size() == 4));
            }
            case "remove" -> tokens.size() == 3
                    ? ParseResult.command(new ClaimCommand(ClaimAction.REMOVE, tokens.get(2), false))
                    : ParseResult.error("Usage: claim remove <name or id>");
            case "prefer" -> {
                if (tokens.size() != 4 || !tokens.get(3).equalsIgnoreCase("true")
                        && !tokens.get(3).equalsIgnoreCase("false"))
                    yield ParseResult.error("Usage: claim prefer <name> <true|false>, goto <x> <feetY> <z>, explore [radius [segments]], follow <exactName|UUID> [seconds], waypoint set|goto|remove <name>, waypoint list, cache status|clear, cancel <jobToken>");
                yield ParseResult.command(new ClaimCommand(ClaimAction.PREFER, tokens.get(2),
                        Boolean.parseBoolean(tokens.get(3))));
            }
            default -> ParseResult.error("Unknown claim action: " + tokens.get(1));
        };
    }

    private static String validateClaimName(String name) {
        Objects.requireNonNull(name, "name");
        String value = name.strip();
        if (value.isEmpty() || value.length() > ClaimBox.MAX_NAME_LENGTH
                || value.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Claim name must be 1..128 characters without control characters.");
        return value;
    }

    private static ParseResult noArguments(List<String> tokens, Command command, String error) {
        return tokens.size() == 1 ? ParseResult.command(command) : ParseResult.error(error);
    }

    private static int parseCount(String value) {
        if (value.length() > 10) throw new IllegalArgumentException("Count must be between 1 and " + MAX_REQUEST_COUNT + ".");
        final int count;
        try {
            count = Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Count must be a whole number.");
        }
        validateCount(count);
        return count;
    }

    private static void validateCount(int count) {
        if (count < 1 || count > MAX_REQUEST_COUNT) throw new IllegalArgumentException("Count must be between 1 and " + MAX_REQUEST_COUNT + ".");
    }

    private static String validateItem(String item) {
        Objects.requireNonNull(item, "item");
        String value = item.trim();
        if (value.isEmpty() || value.length() > MAX_ITEM_NAME_LENGTH) throw new IllegalArgumentException("Item name must be 1.." + MAX_ITEM_NAME_LENGTH + " characters.");
        return value;
    }

    private static List<String> tokenize(String input) {
        var tokens = new ArrayList<String>();
        var current = new StringBuilder();
        char quote = 0;
        boolean escaping = false;
        boolean tokenStarted = false;
        for (int index = 0; index < input.length(); index++) {
            char character = input.charAt(index);
            if (escaping) {
                current.append(character);
                escaping = false;
                tokenStarted = true;
            } else if (character == '\\' && quote != '\'') {
                escaping = true;
            } else if (quote != 0) {
                if (character == quote) quote = 0;
                else current.append(character);
                tokenStarted = true;
            } else if (character == '\'' || character == '"') {
                quote = character;
                tokenStarted = true;
            } else if (Character.isWhitespace(character)) {
                if (tokenStarted) {
                    tokens.add(current.toString());
                    current.setLength(0);
                    tokenStarted = false;
                }
            } else {
                current.append(character);
                tokenStarted = true;
            }
        }
        if (escaping) throw new IllegalArgumentException("Command ends with an incomplete escape.");
        if (quote != 0) throw new IllegalArgumentException("Command contains an unclosed quote.");
        if (tokenStarted) tokens.add(current.toString());
        return List.copyOf(tokens);
    }
}
