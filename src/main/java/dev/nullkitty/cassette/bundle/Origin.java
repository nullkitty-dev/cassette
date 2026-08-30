package dev.nullkitty.cassette.bundle;

import java.util.Objects;

/**
 * A position in one source: which source, and where in it.
 *
 * <p>What a {@link SourceIndex} hands back when a span from a tree spanning several sources is
 * resolved. The offset is local to {@code sourceId}; it is what the span's own offset would
 * have been had that source been parsed on its own, so nothing downstream of a resolve has to
 * know a coordinate space exists.
 *
 * <p>{@code sourceId} is opaque. It is whatever the caller called the source: a canonical path,
 * a URL, a key in a test's map. cassette compares it and prints it and never interprets it.
 *
 * @param sourceId what the source was called
 * @param offset   index into that source's own decoded text, zero-based
 */
public record Origin(String sourceId, //
                     int offset) {

    /**
     * @throws NullPointerException     if {@code sourceId} is null
     * @throws IllegalArgumentException if {@code offset} is negative
     */
    public Origin {
        Objects.requireNonNull(sourceId, "sourceId");

        if (offset < 0) {
            throw new IllegalArgumentException("offset must not be negative: " + offset);
        }
    }

    @Override
    public String toString() {
        return this.sourceId + "@" + this.offset;
    }
}
