package dev.nullkitty.cassette.text;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * ASCII folding, and specifically the four characters Java folds into ASCII and CSS does not.
 *
 * <p>Each of those four is named in its own test rather than lumped into one, so that the
 * <em>specific and enumerable</em> set on which {@code String}'s two obvious spellings differ from
 * ASCII folding is visible.
 */
class AsciiTest {

    /**
     * The complete set: every non-ASCII code point that either of {@code String}'s
     * case-insensitive operations folds to a bare ASCII letter. Established by scanning the whole
     * code point range, not by reading the Unicode tables.
     */
    @Nested
    @DisplayName("the four characters Java folds into ASCII")
    class JavaFoldsTheseAndWeDoNot {

        @Test
        void dottedCapitalIIsNotAnI() {
            assertThat("İ".equalsIgnoreCase("i")).isTrue();

            assertThat(Ascii.equalsIgnoreCase("İ", "i")).isFalse();
        }

        @Test
        void dotlessSmallIIsNotAnI() {
            assertThat("ı".equalsIgnoreCase("i")).isTrue();

            assertThat(Ascii.equalsIgnoreCase("ı", "i")).isFalse();
        }

        @Test
        void longSIsNotAnS() {
            assertThat("ſ".equalsIgnoreCase("s")).isTrue();

            assertThat(Ascii.equalsIgnoreCase("ſ", "s")).isFalse();
        }

        @Test
        void kelvinSignIsNotAK() {
            // The only one of the four that String.toLowerCase folds too, which is why the
            // toLowerCase call sites were exposed as well as the equalsIgnoreCase ones.
            assertThat("K".toLowerCase(Locale.ROOT)).isEqualTo("k");
            assertThat("K".equalsIgnoreCase("k")).isTrue();

            assertThat(Ascii.lower("K")).isEqualTo("K");
            assertThat(Ascii.equalsIgnoreCase("K", "k")).isFalse();
        }
    }

    @Nested
    class Folding {

        @Test
        void foldsAsciiLetters() {
            assertThat(Ascii.equalsIgnoreCase("IMPORTANT", "important")).isTrue();
            assertThat(Ascii.equalsIgnoreCase("ImPoRtAnT", "important")).isTrue();
            assertThat(Ascii.lower("MEDIA")).isEqualTo("media");
        }

        @Test
        void leavesEverythingElseAlone() {
            assertThat(Ascii.lower("ÄÖÜ")).isEqualTo("ÄÖÜ");
            assertThat(Ascii.equalsIgnoreCase("ä", "Ä")).isFalse();
        }

        @Test
        void returnsTheSameInstanceWhenThereIsNothingToFold() {
            String already = "media";

            assertThat(Ascii.lower(already)).isSameAs(already);
        }

        @Test
        void comparesLengthFirst() {
            assertThat(Ascii.equalsIgnoreCase("import", "imports")).isFalse();
            assertThat(Ascii.equalsIgnoreCase("imports", "import")).isFalse();
            assertThat(Ascii.equalsIgnoreCase("", "")).isTrue();
        }

        @Test
        void aSurrogatePairIsNeverEqualToAnAsciiLetter() {
            // Compared code unit by code unit, which is safe only because nothing it folds is
            // outside ASCII. This is the case that would catch it if that stopped being true.
            assertThat(Ascii.equalsIgnoreCase("😀", "ab")).isFalse();
        }
    }
}
