package dev.nullkitty.cassette.cli;

import dev.nullkitty.cassette.serializer.Formatting;

/**
 * What the invocation is asking for.
 *
 * <p>The verb owns the formatting axis, which is why {@code Formatting} is not a flag:
 * {@code format} and {@code minify} take an identical flag set and differ in exactly that one
 * option, so there is no {@code format --minified}, that is spelled {@code minify}.
 */
enum Verb {

    /**
     * Pretty-printed output.
     */
    FORMAT("format", Formatting.PRETTY),

    /**
     * Whitespace and comments stripped, and nothing else.
     *
     * <p>Semantic transforms are {@code -O} and are opt-in under every verb. Letting this verb
     * quietly imply them would undo, in the most visible place available, the separation
     * between dropping whitespace and rewriting what a value says.
     */
    MINIFY("minify", Formatting.MINIFIED),

    /**
     * Diagnostics only: parse everything, report, write nothing anywhere.
     */
    CHECK("check", Formatting.PRETTY);

    private final String name;

    private final Formatting formatting;

    Verb(String name, Formatting formatting) {
        this.name = name;
        this.formatting = formatting;
    }

    String verbName() {
        return this.name;
    }

    /**
     * Meaningless for {@link #CHECK}, which writes nothing.
     */
    Formatting formatting() {
        return this.formatting;
    }

    /**
     * Whether this verb produces CSS, and therefore accepts a destination.
     */
    boolean writesOutput() {
        return this != CHECK;
    }

    static Verb parse(String token) {
        for (Verb verb : values()) {
            if (verb.name.equals(token)) {
                return verb;
            }
        }

        return null;
    }

    static String names() {
        return "format, minify, check";
    }
}
