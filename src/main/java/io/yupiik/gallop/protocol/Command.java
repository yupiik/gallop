package io.yupiik.gallop.protocol;

import io.yupiik.gallop.json.JsonParser;

import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

import static io.yupiik.gallop.json.JsonParser.Event.END_ARRAY;
import static io.yupiik.gallop.json.JsonParser.Event.START_ARRAY;
import static io.yupiik.gallop.json.JsonParser.Event.START_OBJECT;
import static io.yupiik.gallop.json.JsonParser.Event.VALUE_STRING;
import static io.yupiik.gallop.json.JsonStrings.escape;

public record Command(String name, List<String> args) {
    public String toJson() {
        final var writer = new StringWriter();
        writer.write('{');
        if (args() != null) {
            writer.write("\"args\":");
            writer.write('[');
            final var it = args().iterator();
            while (it.hasNext()) {
                final var next = it.next();
                writer.write(escape(next));
                if (it.hasNext()) {
                    writer.write(',');
                }
            }
            writer.write(']');
        } else {
            writer.write("\"args\":[]");
        }
        if (name() != null) {
            writer.write(",\"name\":");
            writer.write(escape(name()));
        }
        writer.write('}');
        return writer.toString();
    }

    public static Command fromJson(final String json) {
        try (final var parser = new JsonParser(json)) {
            parser.enforceNext(START_OBJECT);

            String name = null;
            List<String> args = null;

            String key = null;
            JsonParser.Event event;
            while (parser.hasNext()) {
                event = parser.next();
                switch (event) {
                    case KEY_NAME -> key = parser.getString();
                    case VALUE_STRING -> {
                        if ("name".equals(key)) {
                            name = parser.getString();
                        }
                        key = null;
                    }
                    case START_ARRAY -> {
                        if ("args".equals(key)) {
                            parser.rewind(event);
                            if (!parser.hasNext()) {
                                throw new IllegalStateException("No more element");
                            }

                            final var next = parser.next();
                            if (next != START_ARRAY) {
                                throw new IllegalStateException("Expected=START_ARRAY, but got " + next);
                            }

                            args = new ArrayList<>();
                            while (parser.hasNext() && (event = parser.next()) != END_ARRAY) {
                                if (event != VALUE_STRING) {
                                    throw new IllegalStateException("Expected VALUE_STRING");
                                }
                                args.add(parser.getString());
                            }
                        } else {
                            parser.skipArray();
                        }
                        key = null;
                    }
                    case END_OBJECT -> {
                        return new Command(name, args);
                    }
                    case START_OBJECT -> {
                        parser.skipObject();
                        key = null;
                    }
                    default -> throw new IllegalArgumentException("Unsupported event: " + event);
                }
            }
            throw new IllegalArgumentException("Object didn't end.");
        }
    }
}