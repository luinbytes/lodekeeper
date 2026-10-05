package dev.lodekeeper.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Parses command bodies after the Fabric adapter has removed the configured client-only prefix. */
public final class CommandParser {
    public static final int MAX_BODY_LENGTH = 512;
    public static final int MAX_ITEM_NAME_LENGTH = 256;
    public static final int MAX_CONFIG_VALUE_LENGTH = 256;
    public static final int MAX_REQUEST_COUNT = 1_000_000;

    public sealed interface Command permits GetCommand, StopCommand, PauseCommand, ResumeCommand,
            StatusCommand, QueueCommand, ClearCommand, PlanCommand, ConfigCommand { }

    public record GetCommand(String item, int count) implements Command {
        public GetCommand {
            item = validateItem(item);
            validateCount(count);
        }
    }
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

    public static final String USAGE = "Commands: get <item> [count], stop, pause, resume, status, queue, clear, plan [item [count]], config [key [value]]";

    public ParseResult parse(String body) {
        if (body == null) return ParseResult.error("Command is empty.");
        if (body.length() > MAX_BODY_LENGTH) return ParseResult.error("Command is too long (maximum " + MAX_BODY_LENGTH + " characters).");
        List<String> tokens;
        try {
            tokens = tokenize(body.trim());
        } catch (IllegalArgumentException exception) {
            return ParseResult.error(exception.getMessage());
        }
        if (tokens.isEmpty()) return ParseResult.error("Enter a command.");
        if (tokens.size() > 16) return ParseResult.error("Too many command arguments.");
        String name = tokens.get(0).toLowerCase(Locale.ROOT);
        try {
            return switch (name) {
                case "get" -> parseGet(tokens);
                case "stop", "cancel" -> noArguments(tokens, new StopCommand(), "stop takes no arguments.");
                case "pause" -> noArguments(tokens, new PauseCommand(), "pause takes no arguments.");
                case "resume" -> noArguments(tokens, new ResumeCommand(), "resume takes no arguments.");
                case "status" -> noArguments(tokens, new StatusCommand(), "status takes no arguments.");
                case "queue" -> noArguments(tokens, new QueueCommand(), "queue takes no arguments.");
                case "clear" -> noArguments(tokens, new ClearCommand(), "clear takes no arguments.");
                case "plan" -> parsePlan(tokens);
                case "config" -> parseConfig(tokens);
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
