package dev.nullkitty.cassette.lexer;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The character classes, checked exhaustively rather than by sampling.
 *
 * <p>{@code isIdent} and {@code isIdentStart} answer from a bit table for ASCII, and a table is a
 * second statement of a rule that can drift from the first without anything failing. It is built
 * from the predicates it replaces so it cannot, and these tests are the belt: every code point
 * from {@code EOF} to {@link CodePoints#MAX_CODE_POINT}, against the definition spelled out
 * independently here.
 */
class CodePointsTest {

    /**
     * §4.2, written out again rather than reused, so agreement means something.
     */
    private static boolean isNameStartByDefinition(int c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_' || CodePoints.isNonAsciiIdent(c);
    }

    private static boolean isNameByDefinition(int c) {
        return isNameStartByDefinition(c) || (c >= '0' && c <= '9') || c == '-';
    }

    @Nested
    class Exhaustive {

        @Test
        void identStartAgreesWithTheDefinitionForEveryCodePoint() {
            for (int c = CodePoints.EOF; c <= CodePoints.MAX_CODE_POINT; c++) {
                if (CodePoints.isIdentStart(c) != isNameStartByDefinition(c)) {
                    assertThat(CodePoints.isIdentStart(c)).as("isIdentStart(U+%04X)", c)
                                                          .isEqualTo(isNameStartByDefinition(c));
                }
            }
        }

        @Test
        void identAgreesWithTheDefinitionForEveryCodePoint() {
            for (int c = CodePoints.EOF; c <= CodePoints.MAX_CODE_POINT; c++) {
                if (CodePoints.isIdent(c) != isNameByDefinition(c)) {
                    assertThat(CodePoints.isIdent(c)).as("isIdent(U+%04X)", c).isEqualTo(isNameByDefinition(c));
                }
            }
        }
    }

    @Nested
    class Edges {

        /**
         * EOF is the one negative input, and it reaches the table's fallback rather than the
         * table. Worth its own test because the shift distance is masked to six bits, so a
         * negative code point that did reach the bit test would read some unrelated bit.
         */
        @Test
        void treatsEndOfInputAsNeitherStartNorName() {
            assertThat(CodePoints.isIdentStart(CodePoints.EOF)).isFalse();
            assertThat(CodePoints.isIdent(CodePoints.EOF)).isFalse();
        }

        @Test
        void putsTheAsciiBoundaryWhereTheTableEnds() {
            // 63 and 64 straddle the two halves of the table, and 127/128 its end.
            assertThat(CodePoints.isIdent('?')).isFalse(); // 63, the last of the low half
            assertThat(CodePoints.isIdent('@')).isFalse(); // 64, the first of the high
            assertThat(CodePoints.isIdent('A')).isTrue(); // 65
            assertThat(CodePoints.isIdent(0x7F)).isFalse(); // delete, still in the table
            assertThat(CodePoints.isIdent(0x80)).isFalse(); // first past it, not a name
            assertThat(CodePoints.isIdent(0xB7)).isTrue(); // first that is
        }

        @Test
        void acceptsTheDigitsAndHyphenOnlyAsContinuation() {
            for (char c : "0123456789-".toCharArray()) {
                assertThat(CodePoints.isIdent(c)).as("isIdent('%c')", c).isTrue();
                assertThat(CodePoints.isIdentStart(c)).as("isIdentStart('%c')", c).isFalse();
            }

            assertThat(CodePoints.isIdentStart('_')).isTrue();
        }
    }
}
