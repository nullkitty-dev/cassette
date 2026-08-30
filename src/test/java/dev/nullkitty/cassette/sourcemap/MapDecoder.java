package dev.nullkitty.cassette.sourcemap;

import java.util.ArrayList;
import java.util.List;

/**
 * A VLQ decoder and a readable dump, for tests only.
 *
 * <p>Deliberately not in {@code src/main}: reading a map is a feature this library does not
 * have. What it is for is checking that what the encoder wrote says what it meant, so it is
 * written against the format rather than against the encoder, a hand-written expected VLQ
 * string only asserts that the encoder still does what it did.
 *
 * <p>It is also what makes a golden fixture reviewable. A {@code mappings} string is an
 * unreadable diff, and the diff is the review artifact.
 */
public final class MapDecoder {

    private static final String DIGITS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

    /**
     * One line per segment, as {@code outLine:outCol -> sourceIndex:line:col}.
     *
     * @param mappings the encoded segments
     * @return the decoded positions, in order
     */
    public static List<String> segments(String mappings) {
        List<String> out = new ArrayList<>();
        int generatedLine = 0;
        int sourceIndex = 0;
        int sourceLine = 0;
        int sourceColumn = 0;

        for (String line : mappings.split(";", -1)) {
            int generatedColumn = 0;

            if (!line.isEmpty()) {
                for (String segment : line.split(",")) {
                    int[] cursor = { 0 };
                    generatedColumn += next(segment, cursor);
                    sourceIndex += next(segment, cursor);
                    sourceLine += next(segment, cursor);
                    sourceColumn += next(segment, cursor);

                    out.add(generatedLine
                            + ":"
                            + generatedColumn
                            + " -> "
                            + sourceIndex
                            + ":"
                            + sourceLine
                            + ":"
                            + sourceColumn);
                }
            }

            generatedLine++;
        }

        return out;
    }

    /**
     * One line per segment, naming the source and quoting what it points at.
     *
     * <p>{@code outLine:outCol -> file:line:col «text»}, where the text is what the source
     * holds from that position to the end of its line, shortened. That is the assertion worth
     * making: not that a number is a number, but that the position names the construct a person
     * would expect to land on.
     *
     * @param map the map to render
     * @return one line per mapping
     */
    public static List<String> dump(SourceMap map) {
        List<String> out = new ArrayList<>();

        for (String segment : segments(map.mappings())) {
            String[] halves = segment.split(" -> ");
            String[] where = halves[1].split(":");
            int sourceIndex = Integer.parseInt(where[0]);
            int line = Integer.parseInt(where[1]);
            int column = Integer.parseInt(where[2]);

            out.add(halves[0]
                    + " -> "
                    + map.sources().get(sourceIndex)
                    + ":"
                    + line
                    + ":"
                    + column
                    + "  «"
                    + excerpt(map, sourceIndex, line, column)
                    + "»");
        }

        return out;
    }

    private static String excerpt(SourceMap map, int sourceIndex, int line, int column) {
        if (map.sourcesContent() == null) {
            return "";
        }

        String[] lines = map.sourcesContent().get(sourceIndex).split("\n", -1);
        if (line >= lines.length || column > lines[line].length()) {
            return "<out of range>";
        }

        String rest = lines[line].substring(column);

        return rest.length() <= 24 ? rest : rest.substring(0, 24) + "…";
    }

    private static int next(String segment, int[] cursor) {
        int result = 0;
        int shift = 0;
        boolean more;

        do {
            int digit = DIGITS.indexOf(segment.charAt(cursor[0]++));
            more = (digit & 0x20) != 0;
            result += (digit & 0x1f) << shift;
            shift += 5;
        }
        while (more);

        return (result & 1) != 0 ? -(result >> 1) : result >> 1;
    }

    private MapDecoder() {
    }
}
