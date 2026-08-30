package dev.nullkitty.cassette.sourcemap;

/**
 * Base64 variable-length quantities, which is how a Source Map v3 spells a number.
 *
 * <p>Five payload bits per character, least significant group first, with the sixth bit set on
 * every group but the last. The sign is the low bit of the first group rather than a leading
 * minus, because every field in a segment is a signed delta.
 *
 * <p>Package-private, because this is one detail of one format rather than a general-purpose codec.
 * Exporting it would freeze a shape at 1.0 that only {@link SourceMap} uses.
 *
 * @see <a href="https://tc39.es/ecma426/#sec-base64-vlq">Source Map (ECMA-426) §6 base64 VLQ</a>
 */
final class Vlq {

    private static final String DIGITS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

    /**
     * Payload bits per character.
     */
    private static final int SHIFT = 5;

    /**
     * The low five bits, which is one group's payload.
     */
    private static final int MASK = (1 << SHIFT) - 1;

    /**
     * The sixth bit, saying another group follows.
     */
    private static final int CONTINUATION = 1 << SHIFT;

    /**
     * Appends one signed value.
     *
     * @param out   where to write
     * @param value the number, which for every field of a segment is a delta against the
     *              previous one and is therefore usually small
     */
    static void encode(StringBuilder out, int value) {
        // The sign moves into the low bit, so -1 encodes as compactly as 1 rather than as a
        // 32-bit two's complement pattern.
        int remaining = value < 0 ? ((-value) << 1) | 1 : value << 1;

        do {
            int digit = remaining & MASK;
            remaining >>>= SHIFT;

            if (remaining != 0) {
                digit |= CONTINUATION;
            }

            out.append(DIGITS.charAt(digit));
        }
        while (remaining != 0);
    }

    private Vlq() {
        // utility class
    }
}
