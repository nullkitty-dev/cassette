package dev.nullkitty.cassette.cli;

import java.util.HashMap;
import java.util.Map;

import dev.nullkitty.cassette.diagnostics.Diagnostic;
import dev.nullkitty.cassette.diagnostics.LineIndex;
import dev.nullkitty.cassette.diagnostics.Severity;
import dev.nullkitty.cassette.diagnostics.SourceResolver;

/**
 * Renders diagnostics, in one of two shapes.
 *
 * <p>The short one is {@code file:line:col: severity: message}, the conventional shape, chosen
 * because every editor and CI log scraper already parses it. Each diagnostic prints twice: once
 * for the message and once as a {@code note} carrying the source line it landed on.
 *
 * <pre>{@code
 * style.css:12:3: error: unclosed ( ) block, which consumed everything after it
 * style.css:12:3: note:   background: rgb(0 0 0;
 * }</pre>
 *
 * <p>The rich one draws the line with a caret under the span:
 *
 * <pre>{@code
 * error: unclosed function rgb(), which consumed everything after it
 *  --> style.css:2:18
 * 2 | .b { background: rgb(0 0 0; }
 *   |                  ^^^^^^^^^^^^
 * }</pre>
 *
 * <p>The caret width costs nothing: a {@link SourceResolver.Location} carries the span's
 * {@code length} beside its offset, which the short form does not read. Real spans are tight, an
 * unmatched {@code }} being one character and a malformed {@code url()} exactly its own text, so
 * underlining one is informative rather than decorative.
 *
 * <p>No surrounding lines, and therefore no blank gutter line. A {@link Diagnostic} carries one
 * span, so context lines would be padding, and dropping them takes the rich form from five lines
 * to four, which against a default {@code --max-diagnostics} of 100 is a hundred lines of
 * terminal.
 *
 * <p>Nothing says how far a long span reaches. A span outrunning its line may end three lines
 * down or at end of input, and the diagnostics that most often do this say which in the message,
 * <em>which consumed everything after it</em>. A trailing {@code …} claims only that there is more
 * than is drawn.
 *
 * <p>Everything it knows about where a span came from arrives through a {@link SourceResolver},
 * never as a bare string, so the same renderer serves a single file and a bundle, where one tree's
 * spans cover several files and only the resolver can say which.
 */
public final class DiagnosticRenderer {

    /**
     * Visual cells a drawn source line may occupy before it is windowed around the span.
     *
     * <p>Not a guess about terminals so much as a guard against the input: Bootstrap has a
     * 525-character line and minified CSS is one line of however many megabytes the file is, so
     * a renderer that draws whole lines would print one of those to a terminal.
     */
    private static final int MAX_CELLS = 100;

    /**
     * Marks that the drawn text stops short of the real text, at either end.
     */
    private static final char ELISION = '…';

    private final SourceResolver resolver;

    private final boolean rich;

    /**
     * One index per source id, since consecutive diagnostics usually share a source.
     */
    private final Map<String, LineIndex> indexes = new HashMap<>();

    /**
     * A renderer in the short form.
     *
     * @param resolver where spans come from
     */
    public DiagnosticRenderer(SourceResolver resolver) {
        this(resolver, false);
    }

    /**
     * @param resolver where spans come from
     * @param rich     whether to draw a snippet with a caret
     */
    public DiagnosticRenderer(SourceResolver resolver, //
                              boolean rich) {
        this.resolver = resolver;
        this.rich = rich;
    }

    /**
     * Renders one diagnostic.
     *
     * @param diagnostic what to render
     * @return two lines in the short form and four in the rich one, newline-separated, without
     *         a trailing newline
     */
    public String render(Diagnostic diagnostic) {
        SourceResolver.Location at = this.resolver.locate(diagnostic.span());
        LineIndex index = this.indexes.computeIfAbsent(at.sourceId(), id -> new LineIndex(at.sourceText()));

        // LineIndex counts from zero, because a source map does and a diagnostic is the only
        // thing here that does not. The whole of the difference is these two additions.
        int line = index.lineOf(at.offset()) + 1;
        int column = index.columnOf(at.offset()) + 1;

        if (!this.rich) {
            String where = at.sourceId() + ":" + line + ":" + column + ": ";
            return where
                   + label(diagnostic.severity())
                   + ": "
                   + diagnostic.message()
                   + '\n'
                   + where
                   + "note:   "
                   + index.lineTextOf(at.offset());
        }

        // The line's own start, without reaching into LineIndex for it: the column is counted
        // in characters, which is exactly the distance back to it.
        int lineStart = at.offset() - column + 1;
        Snippet snippet = window(index.lineTextOf(at.offset()).toString(), lineStart, at.offset(), at.length());
        String number = String.valueOf(line);
        String gutter = " ".repeat(number.length());

        return label(diagnostic.severity())
               + ": "
               + diagnostic.message()
               + '\n'
               + gutter
               + "--> "
               + at.sourceId()
               + ":"
               + line
               + ":"
               + column
               + '\n'
               + number
               + " | "
               + snippet.text()
               + '\n'
               + gutter
               + " | "
               + " ".repeat(snippet.caretAt())
               + "^".repeat(snippet.caretWidth())
               + (snippet.truncated() ? ELISION : "");
    }

    /**
     * The drawn line, and where the caret goes under it.
     *
     * @param text        what to draw, tabs expanded and ends elided
     * @param caretAt     cells to skip before the caret run
     * @param caretWidth  cells the caret run covers, never less than one
     * @param truncated   whether the span reaches past what is drawn
     */
    private record Snippet(String text, int caretAt, int caretWidth, boolean truncated) {
    }

    /**
     * Draws one line, windowed to {@link #MAX_CELLS} around the span.
     *
     * <p>Everything here is in <em>visual cells</em> rather than characters, which is the one place
     * in the CLI that distinction is drawn. {@link LineIndex#columnOf} refuses it, because a column
     * is a character count and picking a tab width and a width table is not a CSS tool's business.
     * That refusal is about the number a machine reads. A caret has to land under the glyph a person
     * is looking at, which is a different question with a different answer, and it is answered here
     * and nowhere else.
     */
    private static Snippet window(String line, int lineStart, int offset, int length) {
        int spanStart = Math.max(0, offset - lineStart);

        // A span may run past this line, and often does: the unclosed-construct diagnostics
        // deliberately cover everything they consumed.
        int spanEnd = Math.min(line.length(), spanStart + Math.max(length, 1));

        int[] cells = cellsOf(line);

        int total = 0;
        for (int width : cells) {
            total += width;
        }

        int from = 0;
        int to = line.length();
        if (total > MAX_CELLS) {
            // Keep the span's start in view with a quarter of the window ahead of it, so there
            // is context on the left without pushing the caret off the right.
            int lead = MAX_CELLS / 4;
            from = backFrom(cells, spanStart, lead);
            to = forwardFrom(cells, from, MAX_CELLS - (from > 0 ? 1 : 0));
        }

        StringBuilder drawn = new StringBuilder();
        if (from > 0) {
            drawn.append(ELISION);
        }

        int caretAt = from > 0 ? 1 : 0;
        int caretWidth = 0;

        for (int i = from; i < to; i++) {
            char c = line.charAt(i);
            drawn.append(c == '\t' ? "    " : String.valueOf(c));

            if (i < spanStart) {
                caretAt += cells[i];
            }
            else if (i < spanEnd) {
                caretWidth += cells[i];
            }
        }

        boolean truncated = spanStart + Math.max(length, 1) > to;

        if (to < line.length()) {
            drawn.append(ELISION);
        }

        // A zero-width span, and one starting at the very end of its line, still get a mark:
        // there is a position to point at even when there is no text occupying it.
        return new Snippet(drawn.toString(), caretAt, Math.max(caretWidth, 1), truncated);
    }

    /**
     * How many cells each character of a line occupies once drawn.
     */
    private static int[] cellsOf(String line) {
        int[] cells = new int[line.length()];

        for (int i = 0; i < line.length(); i++) {
            cells[i] = widthOf(line, i);
        }

        return cells;
    }

    /**
     * The width of one character, in terminal cells.
     *
     * <p>A tab is four, which is a choice rather than a measurement. Each half of a surrogate
     * pair counts one, summing to the two cells an emoji occupies, so the pair needs no special
     * case. East Asian scripts are two.
     */
    private static int widthOf(String line, int at) {
        char c = line.charAt(at);
        if (c == '\t') {
            return 4;
        }

        if (Character.isSurrogate(c)) {
            return 1;
        }

        return isWide(c) ? 2 : 1;
    }

    private static boolean isWide(char c) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
               || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
               || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
               || block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
               || block == Character.UnicodeBlock.HIRAGANA
               || block == Character.UnicodeBlock.KATAKANA
               || block == Character.UnicodeBlock.HANGUL_SYLLABLES
               || block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS;
    }

    /**
     * The index at most {@code budget} cells before {@code target}.
     */
    private static int backFrom(int[] cells, int target, int budget) {
        int at = Math.min(target, cells.length);
        int spent = 0;

        while (at > 0 && spent + cells[at - 1] <= budget) {
            spent += cells[--at];
        }

        return at;
    }

    /**
     * The index at most {@code budget} cells after {@code from}.
     */
    private static int forwardFrom(int[] cells, int from, int budget) {
        int at = from;
        int spent = 0;

        while (at < cells.length && spent + cells[at] <= budget) {
            spent += cells[at++];
        }

        return at;
    }

    private static String label(Severity severity) {
        return severity.isError() ? "error" : "warning";
    }
}
