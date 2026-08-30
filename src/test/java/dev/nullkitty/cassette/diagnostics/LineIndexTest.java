package dev.nullkitty.cassette.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Offsets into one source, as lines and columns.
 *
 * <p>Counted from zero, which is the whole of what changed when this moved out of the CLI: a
 * source map is 0-based, a diagnostic is 1-based, and the renderer is what adds the one.
 */
class LineIndexTest {

    private static final String THREE = "a{}\nbb{}\nccc{}";

    @Nested
    class Positions {

        @Test
        void countsTheFirstCharacterAsLineZeroColumnZero() {
            LineIndex index = new LineIndex(THREE);

            assertThat(index.lineOf(0)).isZero();
            assertThat(index.columnOf(0)).isZero();
        }

        @Test
        void countsWithinALine() {
            LineIndex index = new LineIndex(THREE);

            assertThat(index.lineOf(2)).isZero();
            assertThat(index.columnOf(2)).isEqualTo(2);
        }

        @Test
        void putsALineTerminatorOnTheLineItEnds() {
            LineIndex index = new LineIndex(THREE);

            assertThat(index.lineOf(3)).isZero();
            assertThat(index.columnOf(3)).isEqualTo(3);
        }

        @Test
        void startsTheNextLineAfterTheTerminator() {
            LineIndex index = new LineIndex(THREE);

            assertThat(index.lineOf(4)).isEqualTo(1);
            assertThat(index.columnOf(4)).isZero();
        }

        @Test
        void reachesTheLastLine() {
            LineIndex index = new LineIndex(THREE);

            assertThat(index.lineOf(THREE.length() - 1)).isEqualTo(2);
            assertThat(index.columnOf(THREE.length() - 1)).isEqualTo(4);
        }

        @Test
        void answersForTheOffsetOneCharacterPastTheEnd() {
            // Where a zero-width span at the very end sits, which the resolver allows.
            LineIndex index = new LineIndex(THREE);

            assertThat(index.lineOf(THREE.length())).isEqualTo(2);
            assertThat(index.columnOf(THREE.length())).isEqualTo(5);
        }

        @Test
        void handlesEmptyText() {
            LineIndex index = new LineIndex("");

            assertThat(index.lineOf(0)).isZero();
            assertThat(index.columnOf(0)).isZero();
            assertThat(index.lineTextOf(0).toString()).isEmpty();
        }

        @Test
        void countsAnEmptyLineAsALine() {
            LineIndex index = new LineIndex("a\n\nb");

            assertThat(index.lineOf(2)).isEqualTo(1);
            assertThat(index.lineTextOf(2).toString()).isEmpty();
            assertThat(index.lineOf(3)).isEqualTo(2);
        }

        @Test
        void countsColumnsInCharactersAndNotInVisualWidth() {
            // A tab is one and so is a double-width glyph. Whatever draws a caret under a
            // glyph is asking a different question, and answers it itself.
            LineIndex index = new LineIndex("\t中x");

            assertThat(index.columnOf(2)).isEqualTo(2);
        }
    }

    @Nested
    class LineText {

        @Test
        void dropsTheTerminator() {
            LineIndex index = new LineIndex(THREE);

            assertThat(index.lineTextOf(0).toString()).isEqualTo("a{}");
            assertThat(index.lineTextOf(5).toString()).isEqualTo("bb{}");
        }

        @Test
        void returnsTheLastLineWithNoTerminatorToDrop() {
            LineIndex index = new LineIndex(THREE);

            assertThat(index.lineTextOf(10).toString()).isEqualTo("ccc{}");
        }

        @Test
        void returnsAnEmptyLastLineAfterATrailingNewline() {
            LineIndex index = new LineIndex("a{}\n");

            assertThat(index.lineOf(4)).isEqualTo(1);
            assertThat(index.lineTextOf(4).toString()).isEmpty();
        }
    }

    @Test
    void refusesNullText() {
        assertThatThrownBy(() -> new LineIndex(null)).isInstanceOf(NullPointerException.class);
    }
}
