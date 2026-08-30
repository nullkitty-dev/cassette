package dev.nullkitty.cassette.diagnostics;

import java.util.Arrays;
import java.util.Objects;

/**
 * Line starts for one source, so an offset can be turned into a line and a column.
 *
 * <p>Built once per source and kept, because counting newlines from offset zero for every
 * question is quadratic in the number of questions, and the text this is pointed at can be
 * megabytes. Two consumers need that and neither can cache for the other: a diagnostic
 * renderer, which asks once per report, and a source-map generator, which asks once per
 * mapping and has nothing bounding how many of those there are.
 *
 * <p>Lines and columns are 0-based here. A source map is 0-based and a diagnostic is 1-based, so
 * one of them has to adjust, and the {@code + 1} belongs at the end that renders for a person
 * rather than a {@code - 1} at the end that generates for a machine.
 *
 * <p>Offsets are the ones a {@link dev.nullkitty.cassette.ast.SourceSpan} carries: indices into
 * the <em>decoded, preprocessed</em> text, which is why the text has to come from
 * {@code CssParser.decode} rather than from the file's bytes. After §3.3 the only line
 * terminator left is {@code \n}, CRLF has been collapsed and a form feed replaced, so this
 * looks for nothing else, and would be wrong if handed raw input.
 *
 * <p>For a tree assembled from several sources, one of these belongs to each source and not to the
 * bundle, keyed on the source id a {@link SourceResolver.Location} names and never on "whichever
 * source the last question used". Mappings arrive in output order, which for a bundle is cascade
 * order, and consecutive ones hop between files.
 *
 * @see <a href="https://www.w3.org/TR/css-syntax-3/#input-preprocessing">CSS Syntax Level 3 §3.3</a>
 * @see <a href="https://tc39.es/ecma426/#sec-mappings">Source Map (ECMA-426) §9.2 Mappings structure</a>
 */
public final class LineIndex {

    /**
     * Offset of the first character of each line; {@code lineStarts[0]} is always 0.
     */
    private final int[] lineStarts;

    private final CharSequence text;

    /**
     * @param text the decoded text of one source, as {@code CssParser.decode} returns it
     * @throws NullPointerException if {@code text} is {@code null}
     */
    public LineIndex(CharSequence text) {
        this.text = Objects.requireNonNull(text, "text");
        this.lineStarts = startsOf(text);
    }

    private static int[] startsOf(CharSequence text) {
        int lines = 1;

        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lines++;
            }
        }

        int[] starts = new int[lines];
        int next = 1;

        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                starts[next++] = i + 1;
            }
        }

        return starts;
    }

    /**
     * The 0-based line an offset falls on.
     *
     * @param offset an index into this source
     * @return the line number, counting from zero
     */
    public int lineOf(int offset) {
        return lineIndexOf(offset);
    }

    /**
     * The 0-based column an offset falls on, counted in characters.
     *
     * <p>Characters, not bytes and not display columns. A tab counts as one, and so does an
     * East Asian glyph twice as wide as the digits beside it. Deciding otherwise means choosing a
     * tab width and a width table, which is a question for whatever draws a caret under a
     * glyph.
     *
     * @param offset an index into this source
     * @return the column number, counting from zero
     */
    public int columnOf(int offset) {
        return offset - this.lineStarts[lineIndexOf(offset)];
    }

    /**
     * The text of the line an offset falls on, without its terminator.
     *
     * @param offset an index into this source
     * @return the line
     */
    public CharSequence lineTextOf(int offset) {
        int line = lineIndexOf(offset);
        int start = this.lineStarts[line];
        int end = line + 1 < this.lineStarts.length ? this.lineStarts[line + 1] - 1 : this.text.length();

        return this.text.subSequence(start, end);
    }

    private int lineIndexOf(int offset) {
        int found = Arrays.binarySearch(this.lineStarts, offset);

        // A hit means the offset is a line's first character; a miss returns -(insertion) - 1,
        // and the line it belongs to is the one starting before it.
        return found >= 0 ? found : -found - 2;
    }
}
