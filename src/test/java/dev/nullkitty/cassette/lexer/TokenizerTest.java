package dev.nullkitty.cassette.lexer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class TokenizerTest {

    @Test
    void producesEofForEmptyInput() {
        Tokenizer tokenizer = tokenizer("");

        assertThat(tokenizer.next()).isEqualTo(TokenType.EOF);
        assertThat(tokenizer.next()).as("EOF repeats indefinitely").isEqualTo(TokenType.EOF);
    }

    @Test
    void typeIsNullBeforeTheFirstAdvance() {
        assertThat(tokenizer("a").type()).isNull();
    }

    @Test
    void tokenizesASimpleRule() {
        assertThat(types("a{color:red}")).containsExactly(TokenType.IDENT,
                                                          TokenType.LEFT_CURLY,
                                                          TokenType.IDENT,
                                                          TokenType.COLON,
                                                          TokenType.IDENT,
                                                          TokenType.RIGHT_CURLY);
    }

    @Test
    void spansCoverTheInputWithoutGapsOrOverlap() {
        String css = "a { color : #fff ; }";
        Tokenizer tokenizer = tokenizer(css);

        int expectedStart = 0;
        while (tokenizer.next() != TokenType.EOF) {
            assertThat(tokenizer.start()).isEqualTo(expectedStart);
            assertThat(tokenizer.end()).isGreaterThan(tokenizer.start());
            expectedStart = tokenizer.end();
        }

        assertThat(expectedStart).isEqualTo(css.length());
    }

    @Nested
    class Idents {

        @Test
        void readsPlainIdentifiers() {
            Tokenizer tokenizer = firstToken("border-box");

            assertThat(tokenizer.type()).isEqualTo(TokenType.IDENT);
            assertThat(tokenizer.value()).isEqualTo("border-box");
            assertThat(tokenizer.hasEscape()).isFalse();
        }

        @Test
        void readsCustomPropertyNames() {
            assertThat(firstToken("--brand-hue").value()).isEqualTo("--brand-hue");
        }

        @Test
        void resolvesHexEscapesAndSwallowsTheDelimitingSpace() {
            Tokenizer tokenizer = firstToken("caf\\e9 x");

            assertThat(tokenizer.type()).isEqualTo(TokenType.IDENT);
            assertThat(tokenizer.raw()).isEqualTo("caf\\e9 x");
            assertThat(tokenizer.value()).isEqualTo("caf\u00e9x");
            assertThat(tokenizer.hasEscape()).isTrue();
        }

        @Test
        void resolvesEscapesOfNonHexCharacters() {
            assertThat(firstToken("\\@media").value()).isEqualTo("@media");
        }

        @Test
        @DisplayName("a trailing backslash is a valid escape resolving to U+FFFD")
        void treatsTrailingBackslashAsAnEscape() {
            Tokenizer tokenizer = firstToken("a\\");

            assertThat(tokenizer.type()).isEqualTo(TokenType.IDENT);
            assertThat(tokenizer.value()).isEqualTo("a\uFFFD");
        }

        @Test
        void escapesOfSurrogatesAndOutOfRangeValuesBecomeReplacement() {
            assertThat(firstToken("\\d800").value()).isEqualTo("\uFFFD");
            assertThat(firstToken("\\110000").value()).isEqualTo("\uFFFD");
            assertThat(firstToken("\\0").value()).isEqualTo("\uFFFD");
        }

        @Test
        void acceptsAstralCharactersAsIdentCodePoints() {
            String astral = new String(Character.toChars(0x1D54F));

            Tokenizer tokenizer = firstToken(astral + "x");

            assertThat(tokenizer.type()).isEqualTo(TokenType.IDENT);
            assertThat(tokenizer.value()).isEqualTo(astral + "x");
        }

        @Test
        @DisplayName("§4.2's non-ASCII ident list is narrower than 'anything above U+007F'")
        void rejectsNonAsciiCodePointsOutsideTheIdentRanges() {
            // U+00A9 (c) sits in the gap between U+00B7 and U+00C0.
            assertThat(types("\u00a9")).containsExactly(TokenType.DELIM);
            assertThat(types("\u00b7")).containsExactly(TokenType.IDENT);
        }

        @Test
        void functionIsAnIdentFollowedByAnOpenParen() {
            Tokenizer tokenizer = firstToken("rgb(0 0 0)");

            assertThat(tokenizer.type()).isEqualTo(TokenType.FUNCTION);
            assertThat(tokenizer.raw()).isEqualTo("rgb(");
            assertThat(tokenizer.value()).isEqualTo("rgb");
        }
    }

    @Nested
    class Hashes {

        @Test
        void marksIdentifierHashes() {
            Tokenizer tokenizer = firstToken("#main");

            assertThat(tokenizer.type()).isEqualTo(TokenType.HASH);
            assertThat(tokenizer.value()).isEqualTo("main");
            assertThat(tokenizer.isIdHash()).isTrue();
        }

        @Test
        @DisplayName("#123 is a hash but not an identifier, so it cannot be an ID selector")
        void leavesNumericHashesUnrestricted() {
            Tokenizer tokenizer = firstToken("#336699");

            assertThat(tokenizer.type()).isEqualTo(TokenType.HASH);
            assertThat(tokenizer.isIdHash()).isFalse();
        }

        @Test
        void aLoneHashIsADelim() {
            Tokenizer tokenizer = firstToken("# ");

            assertThat(tokenizer.type()).isEqualTo(TokenType.DELIM);
            assertThat(tokenizer.raw()).isEqualTo("#");
        }
    }

    @Nested
    class AtKeywords {

        @Test
        void readsAtKeywords() {
            Tokenizer tokenizer = firstToken("@media screen");

            assertThat(tokenizer.type()).isEqualTo(TokenType.AT_KEYWORD);
            assertThat(tokenizer.value()).isEqualTo("media");
        }

        @Test
        void aLoneAtIsADelim() {
            assertThat(firstToken("@ media").type()).isEqualTo(TokenType.DELIM);
        }
    }

    @Nested
    class Strings {

        @Test
        void readsQuotedStrings() {
            Tokenizer tokenizer = firstToken("\"hello\"");

            assertThat(tokenizer.type()).isEqualTo(TokenType.STRING);
            assertThat(tokenizer.value()).isEqualTo("hello");
            assertThat(tokenizer.isTerminated()).isTrue();
        }

        @Test
        void readsSingleQuotedStrings() {
            assertThat(firstToken("'hello'").value()).isEqualTo("hello");
        }

        @Test
        void anUnterminatedStringStillProducesAString() {
            Tokenizer tokenizer = firstToken("\"hello");

            assertThat(tokenizer.type()).isEqualTo(TokenType.STRING);
            assertThat(tokenizer.value()).isEqualTo("hello");
            assertThat(tokenizer.isTerminated()).isFalse();
        }

        @Test
        @DisplayName("a newline ends a string as a bad-string-token, unconsumed")
        void aNewlineProducesABadString() {
            Tokenizer tokenizer = tokenizer("\"hello\nworld\"");

            assertThat(tokenizer.next()).isEqualTo(TokenType.BAD_STRING);
            assertThat(tokenizer.raw()).isEqualTo("\"hello");
            assertThat(tokenizer.next()).as("the newline is left for the next token").isEqualTo(TokenType.WHITESPACE);
        }

        @Test
        void anEscapedNewlineContinuesTheString() {
            Tokenizer tokenizer = firstToken("\"a\\\nb\"");

            assertThat(tokenizer.type()).isEqualTo(TokenType.STRING);
            assertThat(tokenizer.value()).isEqualTo("ab");
        }

        @Test
        void resolvesEscapesInsideStrings() {
            assertThat(firstToken("\"\\201c\"").value()).isEqualTo("\u201c");
            assertThat(firstToken("\"a\\\"b\"").value()).isEqualTo("a\"b");
        }

        @Test
        @DisplayName("a backslash at end of input contributes nothing to a string")
        void anEscapedEndOfInputIsDroppedInAString() {
            // §4.3.5 says to do nothing with it. Idents, dimensions and urls go through
            // §4.3.7 instead, where the same backslash becomes U+FFFD; see
            // Idents.treatsTrailingBackslashAsAnEscape.
            Tokenizer tokenizer = firstToken("\"foo\\");

            assertThat(tokenizer.type()).isEqualTo(TokenType.STRING);
            assertThat(tokenizer.raw()).isEqualTo("\"foo\\");
            assertThat(tokenizer.value()).isEqualTo("foo");
            assertThat(tokenizer.isTerminated()).isFalse();
        }
    }

    @Nested
    class Urls {

        @Test
        void readsUnquotedUrls() {
            Tokenizer tokenizer = firstToken("url(a.woff2)");

            assertThat(tokenizer.type()).isEqualTo(TokenType.URL);
            assertThat(tokenizer.value()).isEqualTo("a.woff2");
            assertThat(tokenizer.isTerminated()).isTrue();
        }

        @Test
        void trimsWhitespaceAroundAnUnquotedUrl() {
            assertThat(firstToken("url(  a.png  )").value()).isEqualTo("a.png");
        }

        @Test
        @DisplayName("a quoted url is a function plus a string, not a url token")
        void aQuotedUrlIsAFunction() {
            assertThat(types("url(\"a.png\")")).containsExactly(TokenType.FUNCTION,
                                                                TokenType.STRING,
                                                                TokenType.RIGHT_PAREN);
        }

        @Test
        void anEmptyUrlIsAUrlToken() {
            Tokenizer tokenizer = firstToken("url()");

            assertThat(tokenizer.type()).isEqualTo(TokenType.URL);
            assertThat(tokenizer.value()).isEmpty();
        }

        @Test
        void interiorWhitespaceMakesABadUrl() {
            Tokenizer tokenizer = firstToken("url(a b)");

            assertThat(tokenizer.type()).isEqualTo(TokenType.BAD_URL);
            assertThat(tokenizer.raw()).isEqualTo("url(a b)");
        }

        @Test
        void aQuoteInsideAnUnquotedUrlMakesABadUrl() {
            assertThat(firstToken("url(a\"b)").type()).isEqualTo(TokenType.BAD_URL);
        }

        @Test
        @DisplayName("bad-url recovery consumes through the closing paren, not past it")
        void badUrlRecoveryStopsAtTheClosingParen() {
            assertThat(types("url(a b) c")).containsExactly(TokenType.BAD_URL, TokenType.WHITESPACE, TokenType.IDENT);
        }

        @Test
        void anUnterminatedUrlStillProducesAUrl() {
            Tokenizer tokenizer = firstToken("url(a.png");

            assertThat(tokenizer.type()).isEqualTo(TokenType.URL);
            assertThat(tokenizer.value()).isEqualTo("a.png");
            assertThat(tokenizer.isTerminated()).isFalse();
        }

        @Test
        void urlIsRecognisedCaseInsensitively() {
            assertThat(firstToken("URL(a.png)").type()).isEqualTo(TokenType.URL);
        }
    }

    @Nested
    class Numbers {

        @Test
        void readsIntegers() {
            Tokenizer tokenizer = firstToken("42");

            assertThat(tokenizer.type()).isEqualTo(TokenType.NUMBER);
            assertThat(tokenizer.numericValue()).isEqualTo(42);
            assertThat(tokenizer.isInteger()).isTrue();
            assertThat(tokenizer.hasSign()).isFalse();
        }

        @Test
        void readsFractionsWithNoLeadingDigit() {
            Tokenizer tokenizer = firstToken(".500");

            assertThat(tokenizer.numericValue()).isCloseTo(0.5, within(1e-12));
            assertThat(tokenizer.isInteger()).isFalse();
            assertThat(tokenizer.raw()).as("the raw text stays round-trippable").isEqualTo(".500");
        }

        @Test
        void recordsAnExplicitSign() {
            Tokenizer tokenizer = firstToken("+5");

            assertThat(tokenizer.numericValue()).isEqualTo(5);
            assertThat(tokenizer.hasSign()).isTrue();
            assertThat(tokenizer.isInteger()).isTrue();
        }

        @Test
        void readsNegativeNumbers() {
            assertThat(firstToken("-2.5").numericValue()).isCloseTo(-2.5, within(1e-12));
        }

        @Test
        void readsExponents() {
            Tokenizer tokenizer = firstToken("1e2");

            assertThat(tokenizer.numericValue()).isCloseTo(100, within(1e-12));
            assertThat(tokenizer.hasExponent()).isTrue();
            assertThat(tokenizer.isInteger()).as("an exponent makes it a number, not an integer").isFalse();
        }

        @Test
        void readsNegativeExponents() {
            assertThat(firstToken("5e-1").numericValue()).isCloseTo(0.5, within(1e-12));
        }

        @Test
        @DisplayName("a trailing 'e' with no digits is a unit, not an exponent")
        void anIncompleteExponentBecomesADimension() {
            Tokenizer tokenizer = firstToken("1e");

            assertThat(tokenizer.type()).isEqualTo(TokenType.DIMENSION);
            assertThat(tokenizer.value()).isEqualTo("e");
            assertThat(tokenizer.numericValue()).isEqualTo(1);
        }

        @Test
        void readsDimensions() {
            Tokenizer tokenizer = firstToken("10px");

            assertThat(tokenizer.type()).isEqualTo(TokenType.DIMENSION);
            assertThat(tokenizer.numericValue()).isEqualTo(10);
            assertThat(tokenizer.value()).as("the value of a dimension is its unit").isEqualTo("px");
            assertThat(tokenizer.raw()).isEqualTo("10px");
        }

        @Test
        void readsPercentages() {
            Tokenizer tokenizer = firstToken("50%");

            assertThat(tokenizer.type()).isEqualTo(TokenType.PERCENTAGE);
            assertThat(tokenizer.numericValue()).isEqualTo(50);
            assertThat(tokenizer.value()).isEqualTo("50");
        }

        @Test
        @DisplayName("a trailing dot is not part of the number")
        void doesNotConsumeATrailingDot() {
            assertThat(types("5.")).containsExactly(TokenType.NUMBER, TokenType.DELIM);
        }
    }

    @Nested
    class Comments {

        @Test
        void areEmittedAsTokens() {
            Tokenizer tokenizer = firstToken("/* hi */a");

            assertThat(tokenizer.type()).isEqualTo(TokenType.COMMENT);
            assertThat(tokenizer.value()).isEqualTo(" hi ");
            assertThat(tokenizer.isTerminated()).isTrue();
        }

        @Test
        void anUnterminatedCommentRunsToEndOfInput() {
            Tokenizer tokenizer = firstToken("/* hi");

            assertThat(tokenizer.type()).isEqualTo(TokenType.COMMENT);
            assertThat(tokenizer.value()).isEqualTo(" hi");
            assertThat(tokenizer.isTerminated()).isFalse();
        }

        @Test
        void aLoneSlashIsADelim() {
            assertThat(types("a/b")).containsExactly(TokenType.IDENT, TokenType.DELIM, TokenType.IDENT);
        }
    }

    @Nested
    class Punctuation {

        @Test
        void readsHtmlCommentDelimiters() {
            assertThat(types("<!-- a -->")).containsExactly(TokenType.CDO,
                                                            TokenType.WHITESPACE,
                                                            TokenType.IDENT,
                                                            TokenType.WHITESPACE,
                                                            TokenType.CDC);
        }

        @Test
        @DisplayName("an ident swallows a following '--', so a-->' is not a CDC")
        void hyphensBindToAPrecedingIdent() {
            Tokenizer tokenizer = firstToken("a-->");

            assertThat(tokenizer.type()).isEqualTo(TokenType.IDENT);
            assertThat(tokenizer.value()).isEqualTo("a--");
            assertThat(types("a-->")).containsExactly(TokenType.IDENT, TokenType.DELIM);
        }

        @Test
        void aLoneAngleBracketIsADelim() {
            assertThat(types("a>b")).containsExactly(TokenType.IDENT, TokenType.DELIM, TokenType.IDENT);
        }

        @Test
        void doubleHyphenStartsACustomProperty() {
            assertThat(types("--x")).containsExactly(TokenType.IDENT);
        }

        @Test
        void readsBrackets() {
            assertThat(types("[](){}")).containsExactly(TokenType.LEFT_SQUARE,
                                                        TokenType.RIGHT_SQUARE,
                                                        TokenType.LEFT_PAREN,
                                                        TokenType.RIGHT_PAREN,
                                                        TokenType.LEFT_CURLY,
                                                        TokenType.RIGHT_CURLY);
        }

        @Test
        void collapsesRunsOfWhitespaceIntoOneToken() {
            Tokenizer tokenizer = firstToken("  \t\n  a");

            assertThat(tokenizer.type()).isEqualTo(TokenType.WHITESPACE);
            assertThat(tokenizer.end()).isEqualTo(6);
        }
    }

    @Nested
    class Spans {

        @Test
        void valueSpanExcludesDelimiters() {
            Tokenizer tokenizer = firstToken("\"abc\"");

            assertThat(tokenizer.span().start()).isZero();
            assertThat(tokenizer.span().length()).isEqualTo(5);
            assertThat(tokenizer.valueSpan().start()).isEqualTo(1);
            assertThat(tokenizer.valueSpan().length()).isEqualTo(3);
        }

        @Test
        void valueSpanIsTheWholeTokenWhenThereAreNoDelimiters() {
            Tokenizer tokenizer = firstToken("abc");

            assertThat(tokenizer.valueSpan()).isEqualTo(tokenizer.span());
        }

        @Test
        void spansIndexIntoThePreprocessedBuffer() {
            SourceText source = SourceText.of("a\r\nb");
            Tokenizer tokenizer = new Tokenizer(source);

            tokenizer.next();
            tokenizer.next();
            tokenizer.next();

            assertThat(tokenizer.type()).isEqualTo(TokenType.IDENT);
            assertThat(tokenizer.start()).as("CRLF collapsed to one character").isEqualTo(2);
            assertThat(tokenizer.span().text(source)).isEqualTo("b");
        }
    }

    @Nested
    class ValueComparison {

        @Test
        void comparesCaseInsensitively() {
            Tokenizer tokenizer = firstToken("@MEDIA");

            assertThat(tokenizer.valueEqualsIgnoreCase("media")).isTrue();
            assertThat(tokenizer.valueEqualsIgnoreCase("supports")).isFalse();
        }

        @Test
        void comparesAgainstTheDecodedValue() {
            assertThat(firstToken("\\6d edia").valueEqualsIgnoreCase("media")).isTrue();
        }
    }

    private static Tokenizer tokenizer(String css) {
        return new Tokenizer(SourceText.of(css));
    }

    private static Tokenizer firstToken(String css) {
        Tokenizer tokenizer = tokenizer(css);
        tokenizer.next();
        return tokenizer;
    }

    private static List<TokenType> types(String css) {
        Tokenizer tokenizer = tokenizer(css);
        List<TokenType> types = new ArrayList<>();
        while (tokenizer.next() != TokenType.EOF) {
            types.add(tokenizer.type());
        }

        return types;
    }
}
