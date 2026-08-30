package dev.nullkitty.cassette.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import dev.nullkitty.cassette.diagnostics.Diagnostic;
import dev.nullkitty.cassette.diagnostics.SourceResolver;
import dev.nullkitty.cassette.parser.CssParser;

/**
 * The renderer, and the one case the whole {@code decode} scheme exists for.
 *
 * <p>These assert <em>where</em> a diagnostic is reported, not what the parser reported, the
 * parser's own behaviour is covered by its tests and by golden fixtures, and asserting it again
 * here would only ever fail in pairs with one of those. For the same reason each case picks its
 * diagnostic by message rather than by index: a parse of malformed CSS produces several, in an
 * order that is the parser's business to change.
 */
class DiagnosticRendererTest {

    @Test
    void rendersFileLineColumnAndTheOffendingLine() {
        String css = "a { color: red }\n.b { background: rgb(0 0 0; }\n";

        assertThat(render("style.css", css, "unclosed function")).isEqualTo("""
            style.css:2:18: error: unclosed function rgb(), which consumed everything after it
            style.css:2:18: note:   .b { background: rgb(0 0 0; }""");
    }

    /**
     * The case a hand-written test forgets, and the reason {@code CssParser.decode} exists.
     *
     * <p>Spans are offsets into the <em>decoded</em> buffer, where §3.3 has collapsed every CRLF
     * to a single character, so an index built over the original bytes runs one character long
     * per preceding line. Thirty short lines puts the drift at thirty, comfortably more than
     * the offending column, so the mistake moves the report to a different <em>line</em> rather
     * than merely a different column, and the assertion fails loudly.
     *
     * <p>Eleven lines, which is what this test used first, is not enough: the drift shifts the
     * column from 18 to 7 and leaves the line right, so a version of this bug would have passed.
     */
    @Test
    void reportsTheRightLineWhenEveryLineEndedWithCrlf() {
        String css = "a{}\r\n".repeat(30) + ".b { background: rgb(0 0 0; }\r\n";

        String rendered = render("crlf.css", css, "unclosed function");

        assertThat(rendered).startsWith("crlf.css:31:18: error: unclosed function");
        assertThat(rendered).contains("note:   .b { background: rgb(0 0 0; }");

        // What an index over the raw bytes would have said. Pinned so that a regression cannot
        // be mistaken for a change of message.
        assertThat(rendered).doesNotContain("crlf.css:28:3");
    }

    /**
     * The charset warning comes out of {@code decode} and not out of {@code parse}, so the
     * renderer has to serve both halves of the split. It does, because it takes a resolver over
     * the decoded text rather than anything a parse handed back.
     */
    @Test
    void rendersWhatDecodeReportsAsWellAsWhatParseDoes() {
        String css = "@charset \"nonsense\";\na { color: red }\n";
        List<Diagnostic> found = new ArrayList<>();

        String text = CssParser.decode(css.getBytes(StandardCharsets.UTF_8), null, found::add);

        assertThat(new DiagnosticRenderer(SourceResolver.of("in.css", text)).render(found.get(0))).isEqualTo("""
            in.css:1:1: warning: @charset "nonsense" names no known encoding; \
            decoded as UTF-8 instead
            in.css:1:1: note:   @charset "nonsense";""");
    }

    @Test
    void countsColumnsInCharactersNotBytes() {
        // Four astral-plane emoji ahead of the defect: eight chars, sixteen UTF-8 bytes. The
        // column is a character count, so it is 27 and not the 35 a byte count would give.
        String css = "/* 😀😀😀😀 */ a { color: rgb(; }";

        assertThat(render("emoji.css", css, "unclosed function")).startsWith("emoji.css:1:27: error:")
                                                                 .doesNotContain("emoji.css:1:35");
    }

    /**
     * The rich form, which draws the line and underlines the span.
     *
     * <p>Four lines and not five: no surrounding lines are drawn, so the blank gutter line that
     * would separate them from the header has nothing to separate.
     */
    @Nested
    class Rich {

        @Test
        void drawsTheLineAndACaretUnderTheSpan() {
            String css = "a { color: red }\n.b { background: rgb(0 0 0; }\n";

            assertThat(rich("style.css", css, "unmatched }")).isEqualTo("""
                error: unmatched }
                 --> style.css:2:29
                2 | .b { background: rgb(0 0 0; }
                  |                             ^""");
        }

        /**
         * The caret's width is the span's length, which is the thing the short form never read
         * even though {@code Location} always carried it.
         */
        @Test
        void widensTheCaretToTheWholeSpan() {
            assertThat(rich("u.css", ".a { b: url(bad url) }\n", "malformed url()")).isEqualTo("""
                error: malformed url()
                 --> u.css:1:9
                1 | .a { b: url(bad url) }
                  |         ^^^^^^^^^^^^""");
        }

        /**
         * A span running past its line gets a mark saying so and nothing more. Saying "to end
         * of input" would be wrong whenever the span ends earlier, and redundant for the two
         * diagnostics that already say it in the message.
         */
        @Test
        void marksASpanThatOutrunsTheLineWithoutClaimingHowFar() {
            String rendered = rich("style.css", ".b { background: rgb(0 0 0; }\n", "unclosed function");

            assertThat(rendered).endsWith("…").doesNotContain("end of input");
        }

        /**
         * Tabs are four cells, so a caret placed by character index would sit three columns left of
         * the glyph per preceding tab. The {@code -->} column stays a character count, which is what
         * a machine reads and what {@code LineIndex} refuses to turn into a display width.
         */
        @Test
        void alignsTheCaretPastTabs() {
            assertThat(rich("t.css", ".y {\n\tbackground: url(bad url) }\n", "malformed url()")).isEqualTo("""
                error: malformed url()
                 --> t.css:2:14
                2 |     background: url(bad url) }
                  |                 ^^^^^^^^^^^^""");
        }

        /**
         * East Asian glyphs are two cells wide, and three of them shift the caret by six.
         */
        @Test
        void alignsTheCaretPastWideGlyphs() {
            assertThat(rich("w.css", ".x { content: \"表表表\"; b: url(bad url) }\n", "malformed url()")).isEqualTo("""
                error: malformed url()
                 --> w.css:1:25
                1 | .x { content: "表表表"; b: url(bad url) }
                  |                            ^^^^^^^^^^^^""");
        }

        /**
         * The case that decides whether this is usable on real input. Bootstrap has a
         * 525-character line and a minified stylesheet is one line of however many megabytes
         * the file is, so drawing whole lines would print one of those to a terminal.
         */
        @Test
        void windowsALineTooLongToDraw() {
            String filler = ".filler { color: red } ".repeat(30);
            String rendered = rich("long.css", filler + ".a { b: url(bad url) }\n", "malformed url()");

            String line = rendered.lines().filter(l -> l.startsWith("1 | ")).findFirst().orElseThrow();
            assertThat(line).startsWith("1 | …").hasSizeLessThan(120).contains("url(bad url)");

            // And the caret still lands under it, which is the part windowing can silently
            // break: the elision marker occupies a cell of its own.
            String carets = rendered.lines().filter(l -> l.contains("^")).findFirst().orElseThrow();
            assertThat(carets.indexOf('^')).isEqualTo(line.indexOf("url(bad url)"));
        }

        /**
         * A run of diagnostics on one line each get their own snippet.
         */
        @Test
        void rendersEveryDiagnosticSeparately() {
            String css = ".b { background: rgb(0 0 0; }\n";
            assertThat(rich("s.css", css, "unmatched }")).startsWith("error: unmatched }");
            assertThat(rich("s.css", css, "unclosed function")).startsWith("error: unclosed function");
        }
    }

    // -----------------------------------------------------------------------

    /**
     * Renders the first diagnostic whose message starts with {@code messagePrefix}.
     */
    private static String render(String sourceId, String css, String messagePrefix) {
        return render(sourceId, css, messagePrefix, false);
    }

    private static String rich(String sourceId, String css, String messagePrefix) {
        return render(sourceId, css, messagePrefix, true);
    }

    private static String render(String sourceId, String css, String messagePrefix, boolean rich) {
        String text = CssParser.decode(css.getBytes(StandardCharsets.UTF_8));
        DiagnosticRenderer renderer = new DiagnosticRenderer(SourceResolver.of(sourceId, text), rich);
        return CssParser.parse(text).diagnostics().stream()
                        .filter(diagnostic -> diagnostic.message().startsWith(messagePrefix)).findFirst()
                        .map(renderer::render)
                        .orElseThrow(() -> new AssertionError("no diagnostic matching " + messagePrefix));
    }
}
