package dev.nullkitty.cassette.ast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import dev.nullkitty.cassette.parser.CssParser;
import dev.nullkitty.cassette.parser.ParseResult;

/**
 * Selectors Level 4 §17, including the three pseudo-classes that do not follow it.
 */
class SpecificityTest {

    @Nested
    class Counting {

        @ParameterizedTest
        @CsvSource({ "*,0,0,0",
                     "li,0,0,1",
                     "ul li,0,0,2",
                     "'ul ol+li',0,0,3",
                     "h1 + *[rel=up],0,1,1",
                     "'ul ol li.red',0,1,3",
                     "li.red.level,0,2,1",
                     "#x34y,1,0,0",
                     "'#s12:not(FOO)',1,0,1",
                     "a:hover,0,1,1",
                     "'a::before',0,0,2",
                     "'a:before',0,0,2",
                     "'[href]',0,1,0",
                     "'svg|circle',0,0,1", })
        void matchesTheSpecExamples(String selector, int id, int cls, int type) {
            assertThat(specificityOf(selector)).isEqualTo(new Specificity(id, cls, type));
        }

        @Test
        void ignoresTheUniversalSelectorInACompound() {
            assertThat(specificityOf("*.card")).isEqualTo(new Specificity(0, 1, 0));
        }
    }

    @Nested
    class FunctionalPseudoClasses {

        @Test
        void takesTheMostSpecificArgumentOfIs() {
            // :is() itself contributes nothing; its most specific argument does.
            assertThat(specificityOf(":is(em, #foo)")).isEqualTo(new Specificity(1, 0, 0));
        }

        @Test
        void countsNothingForWhere() {
            assertThat(specificityOf(":where(#foo)")).isEqualTo(Specificity.ZERO);
            assertThat(specificityOf("p:where(#foo)")).isEqualTo(new Specificity(0, 0, 1));
        }

        @Test
        void takesTheMostSpecificArgumentOfNot() {
            assertThat(specificityOf(":not(em, #foo)")).isEqualTo(new Specificity(1, 0, 0));
        }

        @Test
        void addsNthChildToItsOfClause() {
            // A pseudo-class of its own, *plus* the most specific selector after `of`.
            assertThat(specificityOf(":nth-child(2n+1 of .a)")).isEqualTo(new Specificity(0, 2, 0));
            assertThat(specificityOf(":nth-child(2n+1)")).isEqualTo(new Specificity(0, 1, 0));
        }

        @Test
        void countsTheNestingSelectorAsZero() {
            // Its real weight is the parent's, which a node with no parent cannot see.
            assertThat(specificityOf("& .a")).isEqualTo(new Specificity(0, 1, 0));
        }
    }

    @Nested
    class Ordering {

        @Test
        void ordersLexicographicallyRatherThanAsDigits() {
            Specificity oneId = new Specificity(1, 0, 0);
            Specificity manyClasses = new Specificity(0, 11, 0);

            assertThat(oneId).isGreaterThan(manyClasses);
        }

        @Test
        void fallsThroughToTheTypeCount() {
            Specificity specificity012 = new Specificity(0, 1, 2);
            Specificity specificity011 = new Specificity(0, 1, 1);

            assertThat(specificity012).isGreaterThan(specificity011);
        }

        @Test
        void sortsAsExpected() {
            Specificity specificity100 = new Specificity(1, 0, 0);
            Specificity specificity010 = new Specificity(0, 1, 0);

            List<Specificity> sorted = Stream.of(specificity100, Specificity.ZERO, specificity010) //
                                             .sorted() //
                                             .toList();

            assertThat(sorted).containsExactly(Specificity.ZERO, specificity010, specificity100);
        }

        @Test
        void takesTheMostSpecificAlternativeOfASelectorList() {
            assertThat(Specificity.of(selectorsOf(".a, #b"))).isEqualTo(new Specificity(1, 0, 0));
        }
    }

    @Test
    void rejectsNegativeCounts() {
        assertThatThrownBy(() -> new Specificity(0, -1, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void printsAsATriple() {
        assertThat(new Specificity(1, 2, 3)).hasToString("(1,2,3)");
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static Specificity specificityOf(String selector) {
        return selectorsOf(selector).selectors().get(0).specificity();
    }

    private static SelectorList selectorsOf(String selector) {
        ParseResult result = CssParser.parse(selector + " { top: 0 }");
        assertThat(result.diagnostics()).as("unexpected diagnostics parsing '%s'", selector).isEmpty();
        return ((StyleRule) result.ast().rules().get(0)).selectors();
    }
}
