package dev.nullkitty.cassette.lexer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The two allocation decisions the token buffer makes, and the assumptions about real CSS
 * they rest on.
 *
 * <p>Both were settled by the JMH suite and neither is visible in output, so
 * nothing else in the test suite would notice them being undone. These are the guard.
 */
class TokenBufferTest {

    /**
     * A stylesheet shaped like the ones the corpus holds: ordinary property names, a
     * comment, a nested rule, a function, and long enough that the small-input floor on the
     * capacity estimate does not decide the outcome.
     */
    private static final String REALISTIC = """
        /* card component */
        .card, .panel {
          display: flex;
          margin: 0 auto;
          padding: 1rem 1.5rem;
          background-color: #336699;
          border-radius: 0.25rem;
          box-shadow: 0 1px 2px rgb(0 0 0 / 6%);
          transition: background-color 150ms ease-in-out;
          & .title {
            font-weight: 600;
            font-size: clamp(1rem, 0.5rem + 1vw, 1.5rem);
          }
        }
        """;

    /**
     * The token type is stored as an ordinal in a {@code byte[]}, which is a silent
     * assumption about how many constants {@link TokenType} has.
     */
    @Nested
    class TypeStorage {

        /**
         * The guard. Adding a 128th constant would make the ordinal overflow to negative and
         * every token of that type would read back as something else, or throw on the lookup,
         * far from the enum that caused it, and with nothing else in the build to notice. This
         * is cheap and the failure it prevents is not.
         */
        @Test
        void everyOrdinalFitsInAByte() {
            assertThat(TokenType.values().length).isLessThanOrEqualTo(Byte.MAX_VALUE);
        }

        /**
         * Every constant survives the round trip through the ordinal, not merely most.
         */
        @Test
        void everyTypeReadsBackAsItself() {
            for (TokenType type : TokenType.values()) {
                assertThat(TokenType.values()[(byte) type.ordinal()]).isEqualTo(type);
            }
        }
    }

    @Nested
    class Sizing {

        @Test
        void doesNotGrowForRealisticCss() {
            SourceText source = SourceText.of(REALISTIC);

            TokenBuffer buffer = TokenBuffer.tokenize(source);

            // A growth copies every array built so far. The estimate exists to make that not
            // happen, and being a few percent short costs more than overshooting ever does.
            assertThat(buffer.capacity()).as("the arrays grew, so the capacity estimate is too tight")
                                         .isEqualTo(TokenBuffer.estimateCapacity(source.length()));
        }

        @Test
        void doesNotGrowTheNumberArrayForRealisticCss() {
            SourceText source = SourceText.of(REALISTIC);

            TokenBuffer buffer = TokenBuffer.tokenize(source);

            // Same argument as above, one array down. Asserted as "the array is still its
            // original length" and not as "the count fits the capacity", because the second
            // cannot fail: growth raises the capacity above the count by construction, so it
            // would pass just as well if every token took a slot.
            assertThat(buffer.numberCapacity()).as("the dense number array grew, so its fraction of capacity is too tight")
                                               .isEqualTo(TokenBuffer.estimateNumberCapacity(buffer.capacity()));
        }

        @Test
        void theSampleAboveIsDenserThanRealStylesheets() {
            SourceText source = SourceText.of(REALISTIC);

            TokenBuffer buffer = TokenBuffer.tokenize(source);
            double charsPerToken = (double) source.length() / buffer.size();

            // What makes the test above worth anything: this sample is packed tighter than
            // any measured stylesheet (Bootstrap 3 runs 3.06, Bootstrap 5.3.3 runs 3.89,
            // Tailwind 3.85), so it is close to the worst case the estimate has to cover.
            assertThat(charsPerToken).isBetween(2.5, 3.5);
        }
    }

    /**
     * The numeric value, which no longer has a slot of its own per token.
     *
     * <p>It lives in a dense array addressed by an index packed into the spare bits of the
     * token's {@code flags} word, so two things can go wrong that could not before: the index
     * can collide with a flag, and it can be read for a token that never stored one.
     */
    @Nested
    class NumberStorage {

        /**
         * The guard, and the reason this class exists. A seventh {@code FLAG_} constant in
         * {@code Tokenizer} would land on bit 6, which is where the number index starts, every
         * numeric token would then read a corrupted index and every token would read a
         * corrupted flag, with nothing near the change to notice. Cheap; the failure is not.
         */
        @Test
        void theFlagBitsDoNotReachTheNumberIndex() {
            int highestFlag = Tokenizer.FLAG_ID
                              | Tokenizer.FLAG_INTEGER
                              | Tokenizer.FLAG_SIGNED
                              | Tokenizer.FLAG_EXPONENT
                              | Tokenizer.FLAG_ESCAPED
                              | Tokenizer.FLAG_TERMINATED;

            assertThat(Integer.numberOfTrailingZeros(Integer.highestOneBit(highestFlag))).as("a flag bit has reached the number index; move NUMBER_INDEX_SHIFT up")
                                                                                         .isLessThan(TokenBuffer.numberIndexShift());
        }

        /**
         * The second guard. The numeric-type test in {@code append} is a bit per ordinal in a
         * {@code long}, so a 65th {@code TokenType} constant would shift out of the mask and
         * every token of that type would be treated as carrying no number.
         */
        @Test
        void everyOrdinalFitsTheNumericMask() {
            assertThat(TokenType.values().length).isLessThanOrEqualTo(Long.SIZE);
        }

        @Test
        void everyTypeThatCarriesANumberIsInTheMask() {
            // The mask and TokenType.isNumeric() are two statements of one fact, so they are
            // checked against each other rather than the mask being trusted.
            for (TokenType type : TokenType.values()) {
                TokenBuffer buffer = TokenBuffer.tokenize(SourceText.of("a{b:1px}"));
                assertThat(TokenBuffer.numericMaskHas(type)).as("%s", type).isEqualTo(type.isNumeric());
                assertThat(buffer.size()).isPositive();
            }
        }

        @Test
        void everyNumericTokenReadsBackItsOwnValue() {
            // Interleaved with non-numeric tokens, which take no slot, so an index that was
            // handed out per token rather than per numeric token would read the wrong one.
            TokenBuffer buffer = TokenBuffer.tokenize(SourceText.of("a{a:1;b:2px;c:3%;d:x;e:4.5;f:-6;g:7e2;h:y}"));

            List<Double> values = new ArrayList<>();

            for (int at = 0; at < buffer.size(); at++) {
                if (buffer.type(at).isNumeric()) {
                    values.add(buffer.numericValue(at));
                }
            }

            assertThat(values).containsExactly(1.0, 2.0, 3.0, 4.5, -6.0, 700.0);
        }

        @Test
        void readsZeroForATokenThatNeverStoredANumber() {
            TokenBuffer buffer = TokenBuffer.tokenize(SourceText.of("a{b:1}"));

            for (int at = 0; at < buffer.size(); at++) {
                if (!buffer.type(at).isNumeric()) {
                    assertThat(buffer.numericValue(at)).as("token %d is %s", at, buffer.type(at)).isZero();
                }
            }
        }

        @Test
        void keepsTheFlagsReadableAlongsideAPackedIndex() {
            // A signed number with an exponent, far enough into the stylesheet that its index
            // is nonzero and would corrupt the flags if the two overlapped.
            StringBuilder css = new StringBuilder("a{");
            for (int i = 0; i < 40; i++) {
                css.append("p").append(i).append(":").append(i).append(";");
            }

            css.append("q:-1e3}");
            TokenBuffer buffer = TokenBuffer.tokenize(SourceText.of(css.toString()));

            int last = -1;

            for (int at = 0; at < buffer.size(); at++) {
                if (buffer.type(at).isNumeric()) {
                    last = at;
                }
            }

            assertThat(buffer.numericValue(last)).isEqualTo(-1000.0);
            assertThat(buffer.hasSign(last)).isTrue();
            assertThat(buffer.hasExponent(last)).isTrue();
        }

        @Test
        void survivesMoreNumbersThanItWasSizedFor() {
            // Forces the dense array to grow, which is the one path append takes that the
            // corpus never exercises.
            StringBuilder css = new StringBuilder("a{");
            for (int i = 0; i < 3000; i++) {
                css.append("p:").append(i).append(";");
            }

            css.append("}");
            TokenBuffer buffer = TokenBuffer.tokenize(SourceText.of(css.toString()));

            List<Double> values = new ArrayList<>();

            for (int at = 0; at < buffer.size(); at++) {
                if (buffer.type(at).isNumeric()) {
                    values.add(buffer.numericValue(at));
                }
            }

            assertThat(values).hasSize(3000);
            assertThat(values.get(0)).isZero();
            assertThat(values.get(2999)).isEqualTo(2999.0);
        }
    }

    @Nested
    class Interning {

        @Test
        void handsBackOneInstanceForARepeatedValue() {
            List<String> properties = valuesOf(".a{color:red}.b{color:blue}", TokenType.IDENT, "color");

            assertThat(properties).hasSize(2);
            assertThat(properties.get(0)).isSameAs(properties.get(1));
        }

        @Test
        void internsAcrossTokenKinds() {
            // A dimension's unit and a bare identifier are the same characters, and the
            // table is keyed by characters rather than by what produced them.
            TokenBuffer buffer = TokenBuffer.tokenize(SourceText.of("a{width:5px}b{x:px}"));

            List<String> values = valuesOf(buffer, TokenType.DIMENSION, "px");
            values.addAll(valuesOf(buffer, TokenType.IDENT, "px"));

            assertThat(values).hasSize(2);
            assertThat(values.get(0)).isSameAs(values.get(1));
        }

        @Test
        void internsAValueWhoseEscapesWereResolved() {
            List<String> values = valuesOf(".caf\\e9{}.caf\\e9{}", TokenType.IDENT, "café");

            assertThat(values).hasSize(2);
            assertThat(values.get(0)).isSameAs(values.get(1));
        }

        @Test
        void leavesLongTextAlone() {
            // Past the cap the hash stops paying for itself; see MAX_INTERNED_LENGTH.
            String ident = "a-very-long-identifier-past-the-cap";
            List<String> values = valuesOf("." + ident + "{}." + ident + "{}", TokenType.IDENT, ident);

            assertThat(values).hasSize(2);
            assertThat(values.get(0)).isNotSameAs(values.get(1));
            assertThat(values.get(0)).isEqualTo(values.get(1));
        }

        @Test
        void survivesMoreDistinctValuesThanItHasSlots() {
            // Forces the table past its load factor and through a rehash.
            StringBuilder css = new StringBuilder();
            for (int index = 0; index < 2000; index++) {
                css.append(".c").append(index).append("{top:0}");
            }

            TokenBuffer buffer = TokenBuffer.tokenize(SourceText.of(css.toString()));

            assertThat(valuesOf(buffer, TokenType.IDENT, "top")).hasSize(2000);
            assertThat(valuesOf(buffer, TokenType.IDENT, "c1999")).hasSize(1);
        }
    }

    // -----------------------------------------------------------------------

    private static List<String> valuesOf(String css, TokenType type, String wanted) {
        return valuesOf(TokenBuffer.tokenize(SourceText.of(css)), type, wanted);
    }

    /**
     * Every materialized value of {@code type} equal to {@code wanted}, in source order.
     */
    private static List<String> valuesOf(TokenBuffer buffer, TokenType type, String wanted) {
        List<String> found = new ArrayList<>();

        for (int at = 0; at < buffer.size(); at++) {
            if (buffer.type(at) != type) {
                continue;
            }

            String value = buffer.value(at);
            if (value.equals(wanted)) {
                found.add(value);
            }
        }

        return found;
    }
}
