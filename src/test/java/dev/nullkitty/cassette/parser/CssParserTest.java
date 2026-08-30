package dev.nullkitty.cassette.parser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import dev.nullkitty.cassette.ast.AtRule;
import dev.nullkitty.cassette.ast.Comment;
import dev.nullkitty.cassette.ast.ConditionalGroupRule;
import dev.nullkitty.cassette.ast.Declaration;
import dev.nullkitty.cassette.ast.DimensionToken;
import dev.nullkitty.cassette.ast.FunctionValue;
import dev.nullkitty.cassette.ast.IdentToken;
import dev.nullkitty.cassette.ast.Node;
import dev.nullkitty.cassette.ast.NumberToken;
import dev.nullkitty.cassette.ast.PseudoClassSelector;
import dev.nullkitty.cassette.ast.SimpleBlock;
import dev.nullkitty.cassette.ast.SourceSpan;
import dev.nullkitty.cassette.ast.StringToken;
import dev.nullkitty.cassette.ast.StyleRule;
import dev.nullkitty.cassette.ast.Stylesheet;
import dev.nullkitty.cassette.diagnostics.Diagnostic;
import dev.nullkitty.cassette.diagnostics.Severity;

/**
 * The rule and declaration grammar: CSS Syntax Level 3 §5, plus what CSS Nesting changes
 * about it.
 *
 * <p>Selector-specific behaviour lives in {@link SelectorParserTest}; these tests treat a
 * prelude as a black box beyond checking that it parsed.
 */
class CssParserTest {

    @Nested
    class Rules {

        @Test
        void parsesAStyleRule() {
            StyleRule rule = firstStyleRule("a { color: red }");

            assertThat(rule.selectors().selectors()).hasSize(1);
            assertThat(rule.declarations()).hasSize(1);
            assertThat(rule.span()).isEqualTo(span(0, 16));
        }

        @Test
        void keepsTopLevelCommentsAsSiblings() {
            Stylesheet sheet = parse("/* one */ a {} /* two */");

            assertThat(sheet.children()).hasSize(3);
            assertThat(sheet.children().get(0)).isInstanceOf(Comment.class);
            assertThat(sheet.children().get(1)).isInstanceOf(StyleRule.class);
            assertThat(((Comment) sheet.children().get(2)).text()).isEqualTo(" two ");
        }

        @Test
        void discardsHtmlCommentDelimitersAtTheTopLevelOnly() {
            Stylesheet sheet = parse("<!-- a { color: red } -->");

            assertThat(sheet.rules()).hasSize(1);
        }

        @Test
        void parsesAStatementAtRule() {
            AtRule rule = (AtRule) parse("@import url(a.css);").rules().get(0);

            assertThat(rule.name()).isEqualTo("import");
            assertThat(rule.isStatement()).isTrue();
            assertThat(rule.prelude()).hasSize(1);
        }

        @Test
        void keepsAnOrdinaryAtRuleBlockAsTokens() {
            AtRule rule = (AtRule) parse("@font-face { src: url(a.woff2) }").rules().get(0);

            assertThat(rule.name()).isEqualTo("font-face");
            assertThat(rule.isStatement()).isFalse();

            // Opaque by design: Level 3's own grammar knows nothing about @font-face either.
            assertThat(rule.block()).isNotEmpty();
        }

        @Test
        void parsesConditionalGroupRuleBlocksAsRules() {
            ConditionalGroupRule rule =
                (ConditionalGroupRule) parse("@media screen { a { color: red } }").rules().get(0);

            assertThat(rule.name()).isEqualTo("media");
            assertThat(rule.nestedRules()).hasSize(1);
            assertThat(rule.prelude()).singleElement().isInstanceOf(IdentToken.class);
        }

        @Test
        void treatsTheStatementFormOfLayerAsAnOrdinaryAtRule() {
            // Nothing to recurse into, so nothing is gained by making it a group rule.
            assertThat(parse("@layer base, components;").rules().get(0)).isInstanceOf(AtRule.class);
            assertThat(parse("@layer base { a {} }").rules().get(0)).isInstanceOf(ConditionalGroupRule.class);
        }

        @Test
        void matchesAtRuleNamesIgnoringCase() {
            assertThat(parse("@MEDIA screen { a {} }").rules().get(0)).isInstanceOf(ConditionalGroupRule.class);
        }
    }

    @Nested
    class Declarations {

        @Test
        void parsesAValueAsComponentValues() {
            Declaration declaration = firstDeclaration("a { margin: 0 1px }");

            assertThat(declaration.property()).isEqualTo("margin");
            assertThat(declaration.value()).hasSize(3);
            assertThat(declaration.value().get(0)).isInstanceOf(NumberToken.class);
            assertThat(declaration.value().get(2)).isInstanceOf(DimensionToken.class);
        }

        @Test
        void trimsWhitespaceAtTheEdgesButNotBetweenValues() {
            Declaration declaration = firstDeclaration("a { margin:   0 1px   }");

            assertThat(declaration.value().get(0)).isInstanceOf(NumberToken.class);
            assertThat(declaration.value().get(2)).isInstanceOf(DimensionToken.class);
        }

        @Test
        void stripsImportantOutOfTheValue() {
            Declaration declaration = firstDeclaration("a { color: red !important }");

            assertThat(declaration.important()).isTrue();
            assertThat(declaration.value()).singleElement().isInstanceOf(IdentToken.class);
        }

        @Test
        void allowsWhitespaceAndCaseInImportant() {
            assertThat(firstDeclaration("a { color: red ! IMPORTANT }").important()).isTrue();
        }

        @Test
        void leavesABareBangInTheValue() {
            Declaration declaration = firstDeclaration("a { color: red !x }");

            assertThat(declaration.important()).isFalse();
            assertThat(declaration.value()).hasSize(4);
        }

        @Test
        void keepsFunctionsNested() {
            Declaration declaration = firstDeclaration("a { color: rgb(0 0 0 / 50%) }");

            FunctionValue function = (FunctionValue) declaration.value().get(0);
            assertThat(function.nameIs("rgb")).isTrue();
            assertThat(function.arguments()).isNotEmpty();
        }

        @Test
        void keepsCustomPropertyValuesOpaqueEvenWhenTheyContainBraces() {
            Declaration declaration = firstDeclaration("a { --x: {a:b} }");

            assertThat(declaration.isCustomProperty()).isTrue();
            assertThat(declaration.value()).singleElement().isInstanceOf(SimpleBlock.class);
        }

        @Test
        void keepsQuotedUrlsAsAFunctionAndAString() {
            Declaration declaration = firstDeclaration("a { background: url(\"a.png\") }");

            FunctionValue function = (FunctionValue) declaration.value().get(0);
            assertThat(function.nameIs("url")).isTrue();
            assertThat(function.arguments()).singleElement().isInstanceOf(StringToken.class);
        }

        @Test
        void keepsCommentsBetweenDeclarations() {
            StyleRule rule = firstStyleRule("a { color: red; /* why */ top: 0 }");

            assertThat(rule.body()).hasSize(3);
            assertThat(rule.body().get(1)).isInstanceOf(Comment.class);
        }
    }

    @Nested
    class Nesting {

        @Test
        void parsesANestedStyleRule() {
            StyleRule outer = firstStyleRule(".card { color: red; & .title { font-weight: bold } }");

            assertThat(outer.declarations()).hasSize(1);
            assertThat(outer.nestedRules()).hasSize(1);
            assertThat(((StyleRule) outer.nestedRules().get(0)).declarations()).hasSize(1);
        }

        @Test
        void keepsDeclarationAndRuleOrder() {
            // Order is meaning: a declaration written after a nested rule cascades after it.
            StyleRule rule = firstStyleRule(".a { top: 0; & b {} left: 0 }");

            assertThat(rule.body()).hasExactlyElementsOfTypes(Declaration.class, StyleRule.class, Declaration.class);
        }

        @Test
        void tellsAPseudoClassRuleFromADeclaration() {
            StyleRule rule = firstStyleRule("a { color: red; b:hover { top: 0 } }");

            assertThat(rule.declarations()).hasSize(1);
            assertThat(rule.nestedRules()).hasSize(1);
        }

        @Test
        void letsANestedGroupRuleHoldBareDeclarations() {
            StyleRule rule = firstStyleRule(".a { @media print { color: red } }");

            ConditionalGroupRule media = (ConditionalGroupRule) rule.nestedRules().get(0);
            assertThat(media.body()).singleElement().isInstanceOf(Declaration.class);
        }

        @Test
        void recursesThroughGroupRulesIntoNestedStyleRules() {
            ConditionalGroupRule media =
                (ConditionalGroupRule) parse("@media screen { .a { & .b { top: 0 } } }").rules().get(0);

            StyleRule outer = (StyleRule) media.nestedRules().get(0);
            assertThat(outer.nestedRules()).hasSize(1);
        }
    }

    @Nested
    class Recovery {

        @Test
        void reportsADeclarationWithNoColonAndKeepsGoing() {
            ParseResult result = CssParser.parse("a { color red; top: 0 }");

            assertThat(result.hasErrors()).isTrue();

            assertThat(styleRules(result).get(0).declarations()).singleElement().extracting(Declaration::property)
                                                                .isEqualTo("top");
        }

        @Test
        void dropsARuleWithAnInvalidSelectorButNotItsSuccessors() {
            ParseResult result = CssParser.parse("#123 { color: red } a { top: 0 }");

            assertThat(result.hasErrors()).isTrue();
            assertThat(styleRules(result)).hasSize(1);
        }

        @Test
        void closesAnUnterminatedBlockAtEndOfInput() {
            ParseResult result = CssParser.parse("a { color: red");

            assertThat(styleRules(result).get(0).declarations()).hasSize(1);
            assertThat(result.diagnostics()).isNotEmpty();
        }

        /**
         * §4.3.8: a backslash before a newline does not start a valid escape, so it survives
         * as a plain delimiter, the one token that reaches the tree having already failed.
         * The serializer drops it, so the parse is the only place this can be reported.
         */
        @Test
        void reportsABackslashThatDidNotStartAnEscape() {
            ParseResult result = CssParser.parse("a { color: red\\\n }");

            assertThat(result.diagnostics()).extracting(Diagnostic::message)
                                            .anyMatch(message -> message.contains("does not start an escape"));
        }

        @Test
        void reportsAStrayClosingBrace() {
            ParseResult result = CssParser.parse("} a { top: 0 }");

            assertThat(result.diagnostics()).anyMatch(d -> d.message().contains("'}'"));
            assertThat(styleRules(result)).hasSize(1);
        }

        @Test
        void reportsAnAtRuleWithNeitherBlockNorSemicolon() {
            ParseResult result = CssParser.parse("@media screen");

            assertThat(result.hasErrors()).isTrue();
            assertThat(result.ast().rules()).singleElement().isInstanceOf(AtRule.class);
        }

        @Test
        void ignoresStraySemicolonsInsideABlock() {
            StyleRule rule = firstStyleRule("a { ;;; color: red ;;; }");

            assertThat(rule.declarations()).hasSize(1);
        }

        @Test
        void reportsABadStringToken() {
            ParseResult result = CssParser.parse("a { font-family: \"unterminated;\n top: 0 }");

            assertThat(messages(result)).contains("unterminated string, ended by a newline");
        }

        @Test
        void reportsABadUrlToken() {
            ParseResult result = CssParser.parse("a { background: url(two words) }");

            assertThat(messages(result)).contains("malformed url()");
        }

        @Test
        void reportsConstructsEndOfInputCutShort() {
            // A string and a url() closed by end of input are parse errors per §4.3.5 and
            // §4.3.6, but still ordinary tokens, unlike the bad-* pair above.
            assertThat(messages(CssParser.parse("a { content: \"no end"))).contains("unterminated string at end of input");
            assertThat(messages(CssParser.parse("a { background: url(no-end"))).contains("unterminated url() at end of input");
            assertThat(messages(CssParser.parse("a { /* no end"))).contains("unterminated comment");
        }

        @Test
        void saysWhatAnUnclosedBracketCost() {
            // One missing ')' takes every rule after it, which is spec-correct and what
            // browsers do, so the diagnostic has to name the consequence, not just the token.
            ParseResult result = CssParser.parse("@media (min-width: 40em { a {} } b { top: 0 }");

            assertThat(styleRules(result)).isEmpty();
            assertThat(messages(result)).contains("unclosed ( ) block, which consumed everything after it");
        }

        @Test
        void reportsTheDepthBoundOnceRatherThanPerLevel() {
            ParseResult result = CssParser.parse("a { top: " + "(".repeat(2000) + " }");

            assertThat(messages(result).stream().filter(m -> m.startsWith("nesting too deep"))).hasSize(1);
        }

        @Test
        void doesNotLetAMismatchedCloserEndABlock() {
            // A ']' with no '[' closes nothing; it is a preserved token, so the '}' after
            // it still belongs to this rule and '.b' is not a stray at the top level.
            ParseResult result = CssParser.parse(".a { color: red ] } .b { top: 0 }");

            assertThat(styleRules(result)).hasSize(2);
            assertThat(messages(result)).containsExactly("unmatched ]");
        }

        @Test
        void dropsAPreludeThatStartsLikeACustomProperty() {
            // §5.5.3: '--x:hover' would otherwise parse as the perfectly plausible selector
            // "identifier followed by a pseudo-class".
            ParseResult result = CssParser.parse(".a { } --x:hover { } .b { }");

            assertThat(styleRules(result)).hasSize(2);
            assertThat(messages(result)).containsExactly("'--x:' starts a custom property, not a selector");
        }

        @Test
        void keepsACustomPropertyADeclarationWhenNested() {
            // The same prelude nested is a declaration, not a dropped rule, and its value
            // runs to the end of the enclosing block.
            StyleRule rule = firstStyleRule("div { .a { } --x:hover { } .b { } }");

            assertThat(rule.nestedRules()).hasSize(1);
            assertThat(rule.declarations()).singleElement().extracting(Declaration::property).isEqualTo("--x");
        }

        @Test
        void parsesAnEmptyStylesheetIntoAnEmptyTree() {
            ParseResult result = CssParser.parse("");

            assertThat(result.ast().children()).isEmpty();
            assertThat(result.diagnostics()).isEmpty();
        }
    }

    @Nested
    class Entry {

        @Test
        void readsBytesThroughCharsetDetection() {
            byte[] utf16 = "a { content: \"café\" }".getBytes(StandardCharsets.UTF_16);

            ParseResult result = CssParser.parse(utf16);

            StringToken content = (StringToken) styleRules(result).get(0).declarations().get(0).value().get(0);
            assertThat(content.value()).isEqualTo("café");
        }

        /**
         * Falling back is right; falling back silently is not. Decoding legacy CJK bytes as
         * UTF-8 does not merely garble them, a trailing {@code 0x5C} survives as a real
         * backslash, which starts a CSS escape and can eat a string's closing quote.
         */
        @Test
        void warnsWhenADeclaredCharsetCannotBeResolved() {
            ParseResult result =
                CssParser.parse("@charset \"nonsense\";\na { color: red }".getBytes(StandardCharsets.UTF_8));

            assertThat(result.diagnostics()).hasSize(1);
            Diagnostic diagnostic = result.diagnostics().get(0);
            assertThat(diagnostic.severity()).isEqualTo(Severity.WARNING);

            assertThat(diagnostic.message()).contains("@charset \"nonsense\"").contains("names no known encoding")
                                            .contains("decoded as UTF-8");

            assertThat(diagnostic.span()).isEqualTo(span(0, "@charset \"nonsense\";".length()));

            // The stylesheet still parsed; the warning is about how it was read.
            assertThat(styleRules(result)).hasSize(1);
        }

        @Test
        void doesNotWarnWhenTheCharsetResolves() {
            assertThat(CssParser.parse("@charset \"utf-8\";\na { color: red }".getBytes(StandardCharsets.UTF_8))
                                .diagnostics()).isEmpty();

            assertThat(CssParser.parse("@charset \"shift_jis\";\na { color: red }".getBytes(StandardCharsets.UTF_8))
                                .diagnostics()).isEmpty();
        }

        /**
         * A label cassette catalogues but the runtime cannot supply says so differently,
         * because the stylesheet is not the thing that is wrong. Unreachable through a decode
         * on a JVM that has every charset, so the message is asserted directly.
         */
        @Test
        void separatesAnUnavailableEncodingFromAnUnknownOne() {
            assertThat(CssParser.charsetFallbackMessage("shift_jis",
                                                        "UTF-8",
                                                        true)).isEqualTo("@charset \"shift_jis\" names an encoding this build cannot "
                                                                         + "decode; decoded as UTF-8 instead");

            assertThat(CssParser.charsetFallbackMessage("nonsense",
                                                        "UTF-8",
                                                        false)).isEqualTo("@charset \"nonsense\" names no known encoding; "
                                                                          + "decoded as UTF-8 instead");
        }

        /**
         * Why the charset table is worth carrying. {@code 表} is {@code 95 5C} in Shift_JIS,
         * and a trailing {@code 0x5C} is a real backslash to any UTF-8 decoder, so getting
         * the encoding wrong here does not garble the text, it escapes the string's closing
         * quote and loses the declaration entirely.
         */
        @Test
        void decodesLegacyCjkRatherThanCorruptingIt() {
            byte[] sjis = "@charset \"shift_jis\";\na { content: \"表\" }".getBytes(Charset.forName("Shift_JIS"));

            ParseResult honoured = CssParser.parse(sjis);

            assertThat(honoured.diagnostics()).isEmpty();
            StringToken content = (StringToken) styleRules(honoured).get(0).declarations().get(0).value().get(0);
            assertThat(content.value()).isEqualTo("表");

            // The same bytes read as UTF-8, what a build without this charset would do. The
            // 0x5C escapes the closing quote, so the string runs on and eats the rest of the
            // rule, closing brace included, and ends unterminated at end of input.
            ParseResult corrupted = CssParser.parse("a { content: \"表\" }".getBytes(Charset.forName("Shift_JIS")));

            assertThat(corrupted.hasErrors()).isTrue();
            StringToken wrecked = (StringToken) styleRules(corrupted).get(0).declarations().get(0).value().get(0);
            assertThat(wrecked.terminated()).isFalse();
            assertThat(wrecked.value()).isNotEqualTo("表").endsWith("\" }");
        }

        @Test
        void resolvesEscapesInIdentifiers() {
            Declaration declaration = firstDeclaration("a { font-family: caf\\e9 }");

            assertThat(((IdentToken) declaration.value().get(0)).value()).isEqualTo("café");
        }
    }

    /**
     * {@code decode} exists so a caller can hold the text a span indexes into. The scheme rests
     * on one property, that decoding is a fixed point of §3.3 preprocessing, so running it
     * again inside {@code parse(CharSequence)} moves no offset, and that property is a fact
     * about the current preprocessing rules rather than something the types enforce. A rule
     * normalizing anything §3.3 currently emits would break it silently.
     */
    @Nested
    class Decoding {

        @Test
        void isAFixedPointOfPreprocessing() {
            byte[] source = "a {\r\n  color: red;\r\n}\r\n".getBytes(StandardCharsets.UTF_8);

            String once = CssParser.decode(source);
            String twice = CssParser.decode(once.getBytes(StandardCharsets.UTF_8));

            assertThat(once).isEqualTo(twice).doesNotContain("\r");
        }

        /**
         * The assertion the whole scheme reduces to: a tree parsed from the decoded text is
         * the same tree, span for span, as one parsed from the bytes.
         */
        @Test
        void leavesEverySpanWhereParsingTheBytesPutIt() {
            byte[] source = ("a {\r\n  color: red\r\n}\r\n\r\n"
                             + ".b > .c { background: url(\"x\") }\r\n"
                             + "@media print {\r\n  .d { top: 0 }\r\n}").getBytes(StandardCharsets.UTF_8);

            Stylesheet fromBytes = CssParser.parse(source).ast();
            Stylesheet fromText = CssParser.parse(CssParser.decode(source)).ast();

            // Records render their components, packed spans included, so this compares the
            // whole tree and every offset in it.
            assertThat(fromText.toString()).isEqualTo(fromBytes.toString());
        }

        /**
         * Why the byte offsets of the original file are not an option: collapsing CRLF
         * shortens the buffer, so every offset after the first one is smaller than the byte
         * index that produced it.
         */
        @Test
        void collapsesCrlfSoOffsetsRunBehindTheBytes() {
            byte[] source = "a{}\r\nb{}\r\nc{}".getBytes(StandardCharsets.UTF_8);

            String text = CssParser.decode(source);

            assertThat(text).isEqualTo("a{}\nb{}\nc{}");
            assertThat(text.indexOf("c{}")).isEqualTo(8);

            // The same construct sits at byte 10, two CRLFs further along.
            assertThat(new String(source, StandardCharsets.UTF_8).indexOf("c{}")).isEqualTo(10);
        }

        @Test
        void reportsAnUnresolvableCharsetThroughTheSink() {
            List<Diagnostic> found = new ArrayList<>();

            CssParser.decode("@charset \"nonsense\";a{color:red}".getBytes(StandardCharsets.UTF_8), null, found::add);

            assertThat(found).singleElement().satisfies(diagnostic -> {
                assertThat(diagnostic.severity()).isEqualTo(Severity.WARNING);
                assertThat(diagnostic.message()).contains("nonsense").contains("decoded as UTF-8");
            });
        }

        /**
         * The trap the sink exists for. Decoding and parsing as two steps is the only way to
         * hold the text a span indexes into, and the text entry point has no encoding left to
         * question, so it cannot report the fallback that the byte entry point does. A caller
         * that decodes separately and drops the sink loses the diagnostic entirely.
         */
        @Test
        void isTheOnlyPlaceTheFallbackCanBeReportedOnceTextIsHeld() {
            byte[] source = "@charset \"nonsense\";a{color:red}".getBytes(StandardCharsets.UTF_8);

            assertThat(CssParser.parse(source).diagnostics()).hasSize(1);
            assertThat(CssParser.parse(CssParser.decode(source)).diagnostics()).isEmpty();
        }

        @Test
        void honoursAProtocolEncodingAndDiscardsWithoutASink() {
            byte[] utf16 = "a { content: \"café\" }".getBytes(StandardCharsets.UTF_16);

            assertThat(CssParser.decode(utf16, StandardCharsets.UTF_16)).contains("café");
            assertThat(CssParser.decode("@charset \"nonsense\";a{}".getBytes(StandardCharsets.UTF_8))).contains("a{}");
        }
    }

    /**
     * Keeping the decoded buffer rather than its text, which exists to skip a copy and must
     * therefore be indistinguishable from not having done so.
     */
    @Nested
    class DecodedSources {

        /**
         * The whole contract: same tree, same spans, one buffer less.
         */
        @Test
        void parseTheSameTreeAsTheTextPairing() {
            byte[] source = ("a {\r\n  color: red\r\n}\r\n"
                             + ".b > .c { background: url(\"x\") }\r\n"
                             + "@media print {\r\n  .d { top: 0 }\r\n}").getBytes(StandardCharsets.UTF_8);

            Stylesheet viaText = CssParser.parse(CssParser.decode(source)).ast();
            Stylesheet viaSource = CssParser.parse(CssParser.decodeSource(source, null, 0, Diagnostic.DISCARD)).ast();

            assertThat(viaSource.toString()).isEqualTo(viaText.toString());
        }

        @Test
        void carryTheirBaseIntoEverySpan() {
            byte[] source = "a{color:red}".getBytes(StandardCharsets.UTF_8);

            DecodedSource decoded = CssParser.decodeSource(source, null, 500, Diagnostic.DISCARD);
            Stylesheet ast = CssParser.parse(decoded).ast();

            assertThat(decoded.base()).isEqualTo(500);
            assertThat(decoded.length()).isEqualTo(12);
            assertThat(ast.children().get(0).span().start()).isEqualTo(500);
            assertThat(CssParser.parse(decoded.text(), 500).ast().toString()).isEqualTo(ast.toString());
        }

        /**
         * The one asymmetry with the other {@code parse} overloads, and the reason it is safe:
         * {@code decodeSource} took the sink and has already reported. Reporting again would
         * give a bundle two warnings for every source read in the wrong encoding.
         */
        @Test
        void reportTheCharsetFallbackAtDecodeAndNotAgainAtParse() {
            byte[] source = "@charset \"nonsense\";a{color:red}".getBytes(StandardCharsets.UTF_8);
            List<Diagnostic> found = new ArrayList<>();

            DecodedSource decoded = CssParser.decodeSource(source, null, 0, found::add);

            assertThat(found).singleElement()
                             .satisfies(diagnostic -> assertThat(diagnostic.message()).contains("nonsense"));

            assertThat(CssParser.parse(decoded).diagnostics()).isEmpty();
        }

        /**
         * What the bundler reads instead of sniffing the bytes a second time.
         */
        @Test
        void answerTheEncodingTheDecodeSettledOn() {
            byte[] utf16 = "a { content: \"café\" }".getBytes(StandardCharsets.UTF_16);

            assertThat(CssParser.decodeSource(utf16, null, 0, Diagnostic.DISCARD)
                                .encoding()).isEqualTo(CssParser.detectEncoding(utf16, null));

            assertThat(CssParser.decodeSource("a{}".getBytes(StandardCharsets.UTF_8), null, 0, Diagnostic.DISCARD)
                                .encoding()).isEqualTo(StandardCharsets.UTF_8);
        }

        /**
         * Materialized on demand, so a caller wanting only a tree never pays for it.
         */
        @Test
        void handBackTheSameTextEveryTime() {
            DecodedSource decoded =
                CssParser.decodeSource("a{}\r\nb{}".getBytes(StandardCharsets.UTF_8), null, 0, Diagnostic.DISCARD);

            assertThat(decoded.text()).isEqualTo("a{}\nb{}").isSameAs(decoded.text());
            assertThat(decoded.length()).isEqualTo(decoded.text().length());
        }

        @Test
        void rejectANegativeBaseAndANullSource() {
            assertThatThrownBy(() -> CssParser.decodeSource(new byte[0],
                                                            null,
                                                            -1,
                                                            Diagnostic.DISCARD)).isInstanceOf(IllegalArgumentException.class);

            assertThatThrownBy(() -> CssParser.parse((DecodedSource) null)).isInstanceOf(NullPointerException.class);
        }
    }

    /**
     * Parsing a source at a base, which is how several of them come to share one space of
     * offsets without any tree being rebased afterwards.
     */
    @Nested
    class CoordinateSpace {

        private static final String CSS = "@media print { .a > .b { color: red; background: url(\"x\") } }";

        /**
         * The whole of it in one assertion: a tree parsed at a base is the tree parsed at zero
         * with every offset shifted, and nothing else about it differs. Records render their
         * components, packed spans included, so comparing the dumps compares every span in the
         * tree rather than the handful a hand-written assertion would reach.
         */
        @Test
        void shiftsEverySpanAndChangesNothingElse() {
            Stylesheet atZero = CssParser.parse(CSS).ast();
            Stylesheet at1000 = CssParser.parse(CSS, 1000).ast();

            assertThat(rebased(atZero, 1000)).isEqualTo(spans(at1000));

            assertThat(at1000.toString().replaceAll("packedSpan=-?\\d+",
                                                    "")).isEqualTo(atZero.toString().replaceAll("packedSpan=-?\\d+",
                                                                                                ""));
        }

        /**
         * The stylesheet's own span is the one span in a tree that no token produces, it
         * covers the input whether or not anything was scanned, so it was written as a
         * hardcoded zero and would have stayed at zero for every source in a bundle. The
         * charset warning's span is the other one, below.
         */
        @Test
        void basesTheStylesheetsOwnSpanToo() {
            assertThat(CssParser.parse(CSS, 1000).ast().span()).isEqualTo(new SourceSpan(1000, CSS.length()));
            assertThat(CssParser.parse("", 40).ast().span()).isEqualTo(new SourceSpan(40, 0));
        }

        /**
         * The other span no token produces. Left at zero, every source in a bundle would report
         * its unresolvable {@code @charset} against whichever source happened to sit at offset
         * zero, and the message names an encoding, so nothing in it would look wrong.
         */
        @Test
        void basesTheCharsetWarningAtTheSourceThatDeclaredIt() {
            byte[] source = "@charset \"nonsense\";a{color:red}".getBytes(StandardCharsets.UTF_8);
            List<Diagnostic> found = new ArrayList<>();

            CssParser.decode(source, null, 500, found::add);

            assertThat(found).singleElement().satisfies(diagnostic -> {
                assertThat(diagnostic.message()).contains("nonsense");
                assertThat(diagnostic.span().start()).isEqualTo(500);
                assertThat(diagnostic.span().length()).isEqualTo("@charset \"nonsense\";".length());
            });
        }

        /**
         * Diagnostic spans are based as well, being built from tokens like everything else.
         */
        @Test
        void basesDiagnosticSpans() {
            String broken = ".b { background: rgb(0 0 0; }";

            List<Diagnostic> atZero = CssParser.parse(broken).diagnostics();
            List<Diagnostic> at1000 = CssParser.parse(broken, 1000).diagnostics();

            assertThat(at1000).hasSameSizeAs(atZero).isNotEmpty();

            for (int index = 0; index < atZero.size(); index++) {
                assertThat(at1000.get(index).span().start()).isEqualTo(atZero.get(index).span().start() + 1000);
                assertThat(at1000.get(index).message()).isEqualTo(atZero.get(index).message());
            }
        }

        /**
         * The base shifts spans and must not reach the buffer. Every character-level decision
         * the parser makes, interning a name, comparing a delimiter, deciding that two tokens
         * were written with nothing between them, reads the decoded array with a local offset,
         * and a based one would read the wrong characters or run off the end.
         */
        @Test
        void doesNotLeakIntoTheCharactersTheParserReads() {
            // `a|b` is a namespaced type selector and `a |b` is not, which is decided by
            // adjacency; the identifiers themselves come from the buffer by offset.
            Stylesheet sheet = CssParser.parse("a|b { color: red }", 9_999).ast();

            assertThat(sheet.rules()).singleElement()
                                     .isInstanceOfSatisfying(StyleRule.class,
                                                             rule -> assertThat(rule.selectors()
                                                                                    .toString()).contains("a")
                                                                                                .contains("b"));

            assertThat(CssParser.parse("a|b { color: red }", 9_999).diagnostics()).isEmpty();
        }

        @Test
        void refusesANegativeBase() {
            assertThatThrownBy(() -> CssParser.parse(CSS, -1)).isInstanceOf(IllegalArgumentException.class)
                                                              .hasMessageContaining("base");
        }

        /**
         * Every span in the tree, in walk order, shifted by {@code base}.
         */
        private static List<SourceSpan> rebased(Node root, int base) {
            return spans(root).stream().map(span -> new SourceSpan(span.start() + base, span.length())).toList();
        }

        private static List<SourceSpan> spans(Node root) {
            List<SourceSpan> found = new ArrayList<>();
            collectSpans(root, found);
            return found;
        }

        private static void collectSpans(Node node, List<SourceSpan> into) {
            into.add(node.span());

            for (Node child : children(node)) {
                collectSpans(child, into);
            }
        }

        private static List<Node> children(Node node) {
            return switch (node) {
                case Stylesheet sheet -> sheet.children();
                case StyleRule rule -> rule.body();
                case ConditionalGroupRule rule -> List.copyOf(rule.body());
                case Declaration declaration -> List.copyOf(declaration.value());
                case FunctionValue function -> List.copyOf(function.arguments());
                default -> List.of();
            };
        }
    }

    /**
     * The parsers build every child list on one shared scratch stack, taking each finished list
     * off it in one go. The hazard that creates is a build abandoned half way, a selector that
     * fails after pushing an alternative or a step, because whatever the stack still holds
     * would be taken by the next list to close, and land in a rule that had nothing to do with
     * it. These are the shapes that leave something behind.
     */
    @Nested
    class AbandonedBuilds {

        @Test
        void aFailedSelectorDoesNotLeakIntoTheNextRule() {
            // ".a > " parses a compound and a combinator, then fails on '!'. Everything it
            // pushed has to go with it: ".b" must be one alternative of one step.
            Stylesheet sheet = parse(".a > ! { color: red } .b { color: blue }");

            List<StyleRule> rules =
                sheet.rules().stream().filter(StyleRule.class::isInstance).map(StyleRule.class::cast).toList();
            assertThat(rules).hasSize(1);
            assertThat(rules.get(0).selectors().selectors()).hasSize(1);
            assertThat(rules.get(0).selectors().selectors().get(0).steps()).hasSize(1);
        }

        @Test
        void aFailedAlternativeDoesNotLeakIntoTheSurvivingOnes() {
            // The whole list is invalid, so the rule goes; the point is what the rule after
            // it sees. A leaked ".a" would show up as a second alternative on ".c".
            Stylesheet sheet = parse(".a, .b! , .x { color: red } .c { color: blue }");

            StyleRule survivor = (StyleRule) sheet.rules().get(0);
            assertThat(survivor.selectors().selectors()).hasSize(1);
            assertThat(survivor.declarations()).hasSize(1);
        }

        @Test
        void aFailedCompoundDoesNotLeakIntoTheNextCompound() {
            // ".a .b|" fails inside the second compound, after ".a" and its step are on the
            // stack. ".d" is what would inherit them.
            Stylesheet sheet = parse(".a .b|{ color: red } .d { color: blue }");

            StyleRule survivor = (StyleRule) sheet.rules().get(0);
            assertThat(survivor.selectors().selectors().get(0).steps()).hasSize(1);
            assertThat(survivor.selectors().selectors().get(0).steps().get(0).compound().simples()).hasSize(1);
        }

        /**
         * A forgiving list keeps what parses and drops what does not, so the dropped
         * alternative's part-built pieces must not end up among the kept ones.
         */
        @Test
        void aDroppedForgivingAlternativeLeavesNothingAmongTheKeptOnes() {
            StyleRule rule = firstStyleRule(":is(.a, .b!, .c) { color: red }");

            PseudoClassSelector is =
                (PseudoClassSelector) rule.selectors().selectors().get(0).steps().get(0).compound().simples().get(0);
            assertThat(is.selectors().selectors()).hasSize(2);
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static Stylesheet parse(String css) {
        ParseResult result = CssParser.parse(css);
        assertThat(result.ast()).isNotNull();
        return result.ast();
    }

    private static List<String> messages(ParseResult result) {
        return result.diagnostics().stream().map(Diagnostic::message).toList();
    }

    private static List<StyleRule> styleRules(ParseResult result) {
        return result.ast().rules().stream().filter(StyleRule.class::isInstance).map(StyleRule.class::cast).toList();
    }

    private static StyleRule firstStyleRule(String css) {
        return (StyleRule) parse(css).rules().get(0);
    }

    private static Declaration firstDeclaration(String css) {
        return firstStyleRule(css).declarations().get(0);
    }

    private static SourceSpan span(int start, int end) {
        return new SourceSpan(start, end - start);
    }
}
