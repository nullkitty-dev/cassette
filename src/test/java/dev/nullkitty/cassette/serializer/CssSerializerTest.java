package dev.nullkitty.cassette.serializer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import dev.nullkitty.cassette.ast.Node;
import dev.nullkitty.cassette.ast.StyleRule;
import dev.nullkitty.cassette.ast.Stylesheet;
import dev.nullkitty.cassette.diagnostics.Diagnostic;
import dev.nullkitty.cassette.diagnostics.Severity;
import dev.nullkitty.cassette.parser.CssParser;

/**
 * What the writer puts back, given what the parser produced.
 *
 * <p>Everything here goes through {@code parse} first: the shapes worth asserting on are
 * the ones a real stylesheet produces, and hand-building a tree would mostly test the
 * builder. Fixtures cover whole files; these cover one decision each.
 */
class CssSerializerTest {

    @Nested
    class Layout {

        @Test
        void prettyPrintsOneDeclarationPerLine() {
            assertThat(pretty("a{color:red;top:0}")).isEqualTo("""
                a {
                  color: red;
                  top: 0;
                }
                """);
        }

        @Test
        void putsEachSelectorAlternativeOnItsOwnLine() {
            assertThat(pretty(".a,.b{top:0}")).isEqualTo("""
                .a,
                .b {
                  top: 0;
                }
                """);
        }

        @Test
        void separatesTopLevelRulesWithABlankLine() {
            assertThat(pretty("a{top:0}b{top:0}")).isEqualTo("""
                a {
                  top: 0;
                }

                b {
                  top: 0;
                }
                """);
        }

        @Test
        void keepsCommentsWhereTheyWereWritten() {
            assertThat(pretty("/* one */ a { /* two */ top: 0 }")).isEqualTo("""
                /* one */
                a {
                  /* two */
                  top: 0;
                }
                """);
        }

        @Test
        void reformatsRatherThanReproducing() {
            // Same tree, different source: the output is a function of the tree alone.
            assertThat(pretty("a{top:0}")).isEqualTo(pretty("a\n\n{\ttop  :  0  ;  }"));
        }

        @Test
        void dropsTheLastDeclarationsSemicolonWhenMinifying() {
            assertThat(minified("a { color: red; top: 0; }")).isEqualTo("a{color:red;top:0}");
        }

        @Test
        void dropsCommentsWhenMinifying() {
            assertThat(minified("/* one */ a { /* two */ top: 0 }")).isEqualTo("a{top:0}");
        }

        // `/*!` is the minifier-wide convention for a comment that must survive, and what it
        // carries is a licence header. Dropping Font Awesome's changes the terms the file
        // ships under, which is not a size decision.
        @Test
        void keepsABangCommentWhenMinifying() {
            assertThat(minified("/*! (c) me */ a { top: 0 }")).isEqualTo("/*! (c) me */a{top:0}");
        }

        @Test
        void keepsABangCommentAnywhereItAppears() {
            assertThat(minified("a { /*! one */ margin: 0 /*! two */ 0 }")).isEqualTo("a{/*! one */margin:0 /*! two */ 0}");
        }

        @Test
        void keepsOnlyTheBangCommentsFromARun() {
            assertThat(minified("/*!x*/ /*y*/ /*!z*/ a { top: 0 }")).isEqualTo("/*!x*//*!z*/a{top:0}");
        }

        @Test
        void minifyingIsAFixedPointWithABangComment() {
            // The kept comment re-parses as a comment, so a second pass cannot move it.
            assertThat(minified(minified("/*! (c) me */ a { top: 0 }"))).isEqualTo(minified("/*! (c) me */ a { top: 0 }"));
        }

        @Test
        void keepsASemicolonBeforeANestedRule() {
            assertThat(minified("a { color: red; & b { top: 0 } }")).isEqualTo("a{color:red;& b{top:0}}");
        }

        @Test
        void keepsTheWhitespaceThatSeparatesTwoValues() {
            assertThat(minified("a { border: 1px solid red }")).isEqualTo("a{border:1px solid red}");
        }

        @Test
        void keepsTheWhitespaceAroundACalcOperator() {
            assertThat(minified("a { width: calc( 100% - 2px ) }")).isEqualTo("a{width:calc(100% - 2px)}");
        }

        @Test
        void dropsTheWhitespaceAroundASeparator() {
            assertThat(minified("a { transition: color 1s , top 2s }")).isEqualTo("a{transition:color 1s,top 2s}");
        }

        @Test
        void putsASpaceWhereADroppedCommentHeldTwoValuesApart() {
            // 'a/*x*/b' is two identifiers; 'ab' would be one.
            assertThat(minified("a { font-family: one/*x*/two }")).isEqualTo("a{font-family:one two}");
        }

        @Test
        void needsNoSpaceWhereADroppedCommentTouchedASeparator() {
            assertThat(minified("a { font-family: one/*x*/, two }")).isEqualTo("a{font-family:one,two}");
        }
    }

    @Nested
    class Values {

        @Test
        void keepsANumbersRawText() {
            // .500 and 0.5 are the same number and different text; the text is authoritative.
            assertThat(pretty("a { margin: .500em +5px 1e2 }")).contains("margin: .500em +5px 1e2;");
        }

        @Test
        void keepsImportant() {
            assertThat(pretty("a { top: 0 !important }")).contains("top: 0 !important;");
            assertThat(minified("a { top: 0 ! important }")).isEqualTo("a{top:0!important}");
        }

        @Test
        void keepsACustomPropertysValueAsTokens() {
            // Not evaluated, not validated, but still reflowed, like every other value: the
            // AST records that there was whitespace here, never how much.
            assertThat(pretty("a { --x: { still a value } }")).contains("--x: {still a value};");
        }

        @Test
        void writesAnUnquotedUrlUnquoted() {
            assertThat(pretty("a { background: url( images/a.png ) }")).contains("background: url(images/a.png);");
        }

        @Test
        void quotesAUrlThatCannotBeWrittenBare() {
            assertThat(pretty("a { background: url('a b.png') }")).contains("background: url(\"a b.png\");");
        }

        @Test
        void normalizesQuotesToDoubleQuotes() {
            assertThat(pretty("a { content: 'x' }")).contains("content: \"x\";");
        }

        @Test
        void escapesADoubleQuoteInsideAString() {
            assertThat(pretty("a { content: 'say \"hi\"' }")).contains("""
                content: "say \\"hi\\"";""");
        }

        @Test
        void dropsABadTokenAndTheSpaceThatWouldBeLeftBehind() {
            // A bad-url token is the wreckage of a construct that never parsed; there is no
            // text that would read back as itself.
            assertThat(pretty("a { background: url(bad url) }")).contains("background:;");
        }
    }

    @Nested
    class Selectors {

        @Test
        void spacesOutCombinatorsWhenPrettyPrinting() {
            assertThat(pretty("a>b+c~d e{top:0}")).startsWith("a > b + c ~ d e {");
        }

        @Test
        void dropsCombinatorSpacingWhenMinifying() {
            assertThat(minified("a > b + c ~ d e{top:0}")).startsWith("a>b+c~d e{");
        }

        @Test
        void keepsTheLegacySingleColonPseudoElement() {
            assertThat(pretty("a:before, b::before {top:0}")).startsWith("a:before,\nb::before {");
        }

        @Test
        void alwaysQuotesAnAttributeValue() {
            assertThat(pretty("a[href=x]{top:0}")).startsWith("a[href=\"x\"] {");
        }

        @Test
        void keepsTheAttributeCaseModifier() {
            assertThat(pretty("a[href=x i]{top:0}")).startsWith("a[href=\"x\" i] {");
            assertThat(minified("a[href=x i]{top:0}")).startsWith("a[href=\"x\"i]{");
        }

        @Test
        void keepsNamespacesApart() {
            assertThat(pretty("svg|circle, *|use, |defs {top:0}")).startsWith("svg|circle,\n*|use,\n|defs {");
        }

        @Test
        void writesARelativeSelectorInsideHas() {
            assertThat(pretty("a:has(> img){top:0}")).startsWith("a:has(> img) {");
        }

        @Test
        void writesTheOfClauseOfNthChild() {
            assertThat(pretty("li:nth-child(2n+1 of .visible){top:0}")).startsWith("li:nth-child(2n+1 of .visible) {");
        }
    }

    @Nested
    class AtRules {

        @Test
        void writesAStatementAtRule() {
            assertThat(pretty("@import url(a.css) screen;")).isEqualTo("@import url(a.css) screen;\n");
        }

        @Test
        void writesAGroupRulesBodyAsRules() {
            assertThat(pretty("@media print{a{top:0}}")).isEqualTo("""
                @media print {
                  a {
                    top: 0;
                  }
                }
                """);
        }

        @Test
        void breaksAnOpaqueBlockOnItsOwnPunctuation() {
            // There is no structure to indent by, so a top-level ';' or '{ }' ends a line:
            // and nothing else is added, because token soup is all the writer was given. The
            // space after ':' is the author's or nobody's.
            assertThat(pretty("@font-face{font-family:\"X\";src: url(a.woff2)}")).isEqualTo("""
                @font-face {
                  font-family:"X";
                  src: url(a.woff2)
                }
                """);
        }

        @Test
        void keepsAtKeywordCase() {
            // Lowercasing is an opt-in transform, not something the writer does behind the
            // caller's back.
            assertThat(pretty("@MEDIA print{a{top:0}}")).startsWith("@MEDIA print {");
        }

        @Test
        void writesAnEmptyBlockAsOne() {
            assertThat(pretty("@font-face{}")).isEqualTo("@font-face {}\n");
        }

        /**
         * A bad token writes nothing, so the indent written in front of it would be left
         * behind as a line holding only whitespace, which re-parses to nothing, and cost
         * the round trip its fixed point.
         */
        @Test
        void leavesNoBlankLineWhereABadTokenWroteNothing() {
            assertThat(pretty("@a{b{top:0}url(has spaces and \" quote)")).isEqualTo("""
                @a {
                  b{top:0}
                }
                """);
        }
    }

    @Nested
    class Escapes {

        @Test
        void writesNonAsciiLiterallyByDefault() {
            assertThat(pretty(".caf\\e9 {top:0}")).startsWith(".café {");
        }

        @Test
        void escapesNonAsciiUnderTheLegacyOption() {
            // The trailing space terminates the escape, so the one before '{' survives it.
            assertThat(serialize(".café {top:0}",
                                 SerializerOptions.builder().legacyCompatible().nesting(NestingMode.PRESERVE).build()))
                                                                                                                       .startsWith(".caf\\e9  {");
        }

        @Test
        void escapesAnIdentifierThatWouldOtherwiseLexAsSomethingElse() {
            assertThat(pretty(".\\33 d {top:0}")).startsWith(".\\33 d {");
        }

        @Test
        void doesNotEscapeADigitInsideAHashThatIsNotAnIdentifier() {
            assertThat(pretty("a{color:#336699}")).contains("color: #336699;");
        }

        @Test
        void escapesAUnitThatWouldFuseIntoAnExponent() {
            // A unit of 'e5' would make '1' and 'e5' read back as the number 1e5.
            assertThat(pretty("a{width:1\\65 5}")).contains("width: 1\\65 5;");
        }

        /**
         * A backslash before a newline does not start an escape (§4.3.8), so it survives as a
         * plain delimiter. Written verbatim it would escape the {@code ;} the writer puts
         * after it, and the declaration would swallow its own terminator on the way back in.
         */
        @Test
        void dropsAStrayBackslashRatherThanLettingItEscapeWhatFollows() {
            assertThat(pretty("a{b:c\\\n")).contains("b: c;").doesNotContain("\\");
            assertThat(minified("a{b:c\\\n")).isEqualTo("a{b:c}");
        }

        /**
         * Deciding whether a space still separates anything means looking past every value
         * that writes nothing. Stopping at the bad string here kept a space in front of a
         * {@code )} that closes, which the next pass then removed, so the first output was
         * not a fixed point.
         */
        @Test
        void looksPastATokenThatWritesNothingWhenDecidingIfASpaceSeparates() {
            assertThat(minified("@a{b:c\n\"a{color:red}\n)")).isEqualTo("@a{b:c)}");
        }
    }

    /**
     * {@code url()} is the one name whose spelling decides how the tokenizer reads it back.
     * §4.3.4 emits a function token only when a quote follows the paren, and §4.3.6 otherwise
     * lexes a url-token, whose body admits no whitespace, quotes or parentheses.
     */
    @Nested
    class Urls {

        /**
         * The defect this class exists for. A {@code url(} function whose arguments are not a
         * string used to be written as {@code url(} plus those arguments, which re-parses as a
         * bad-url that swallows what follows, so the first output was not a fixed point, and
         * the second was the wreckage {@code @a );}.
         */
        @Test
        void dropsAUrlFunctionThatCannotBeSpelledAsAUrlToken() {
            assertThat(pretty("@a url(\"{@a\n@a url(")).isEqualTo("@a;\n");
        }

        @Test
        void reportsTheDroppedUrlFunction() {
            List<Diagnostic> diagnostics = new ArrayList<>();
            Stylesheet ast = CssParser.parse("@a url(\"{@a\n@a url(").ast();

            CssSerializer.serialize(ast, SerializerOptions.DEFAULTS, diagnostics::add);

            assertThat(diagnostics).singleElement().satisfies(diagnostic -> {
                assertThat(diagnostic.severity()).isEqualTo(Severity.WARNING);
                assertThat(diagnostic.message()).contains("url()");
            });
        }

        /**
         * The narrow half of the rule. A url function may hold more than one argument and
         * still be faithful, because what decides it is the quote the tokenizer sees first,
         * so "arguments are not a single string" would have dropped output that works.
         */
        @Test
        void keepsAMultiArgumentUrlFunctionThatOpensWithAString() {
            assertThat(pretty("a{b:url(\"a\" c)}")).contains("b: url(\"a\" c);");
            assertThat(minified("a{b:url(\"a\" c)}")).isEqualTo("a{b:url(\"a\" c)}");
        }

        /**
         * A url written without a quote is a url-token to begin with, and is untouched.
         */
        @Test
        void keepsAUrlTokenAlone() {
            assertThat(minified("a{b:url(c)}")).isEqualTo("a{b:url(c)}");
            assertThat(minified("a{b:url()}")).isEqualTo("a{b:url()}");
        }

        /**
         * The reason the test is structural rather than a check on what was written: a value
         * that writes nothing has to be visible to the scans that decide whether the space in
         * front of it still separates anything. Deciding it after writing left {@code b: c ;}
         * behind, which re-parses to {@code b: c;}, the same prefix-before-nothing family as
         * the other dropped-value defects.
         */
        @Test
        void dropsTheSeparatorInFrontOfADroppedUrl() {
            assertThat(pretty("a{b:c url(\"\n@a b)}")).isEqualTo("""
                a {
                  b: c;
                }
                """);

            assertThat(minified("a{b:c url(\"\n@a b)}")).isEqualTo("a{b:c}");
            assertThat(minified("@a c url(\"\n@a b);")).isEqualTo("@a c;");
        }

        /**
         * Why escaping the name was never an escape hatch: §4.3.4 matches "url" against the
         * ident sequence's <em>decoded</em> value, so {@code \75 rl(} is a url-token too, and
         * writing the name escaped would have changed nothing but the reader's patience.
         */
        @Test
        void anEscapedUrlNameIsStillAUrlToken() {
            assertThat(CssParser.parse("a{b:\\75 rl(c)}").ast().toString()).contains("UrlToken");
            assertThat(minified("a{b:\\75 rl(c)}")).isEqualTo("a{b:url(c)}");
        }

        @Test
        void reportsNothingForAUrlItCanWrite() {
            List<Diagnostic> diagnostics = new ArrayList<>();
            Stylesheet ast = CssParser.parse("a{b:url(\"c\" d);e:url(f)}").ast();

            CssSerializer.serialize(ast, SerializerOptions.DEFAULTS, diagnostics::add);

            assertThat(diagnostics).isEmpty();
        }
    }

    @Nested
    class Fragments {

        @Test
        void writesASelectorOnItsOwn() {
            StyleRule rule = (StyleRule) CssParser.parse(".a > .b, .c {top:0}").ast().rules().get(0);

            assertThat(CssSerializer.serialize(rule.selectors(), SerializerOptions.DEFAULTS)).isEqualTo(".a > .b, .c");
        }

        @Test
        void writesADeclarationWithoutItsTerminator() {
            StyleRule rule = (StyleRule) CssParser.parse("a{color:red}").ast().rules().get(0);

            assertThat(CssSerializer.serialize(rule.declarations().get(0),
                                               SerializerOptions.DEFAULTS)).isEqualTo("color: red");
        }

        /**
         * The two {@code serialize} overloads are picked by the argument's static type, so a
         * stylesheet held in a {@code Node} variable must not take the fragment path, it
         * would skip flattening and silently emit nested CSS where the options asked for
         * flat.
         */
        @Test
        void aStylesheetSerializesTheSameThroughEitherOverload() {
            SerializerOptions flattening = SerializerOptions.builder().nesting(NestingMode.FLATTEN).build();
            Stylesheet stylesheet = CssParser.parse(".card{color:red; .title{top:0}}").ast();
            Node asNode = stylesheet;

            assertThat(CssSerializer.serialize(asNode, flattening))
                                                                   .isEqualTo(CssSerializer.serialize(stylesheet,
                                                                                                      flattening))
                                                                   .contains(".card .title");
        }
    }

    // -----------------------------------------------------------------------

    private static String pretty(String css) {
        return serialize(css, SerializerOptions.DEFAULTS);
    }

    private static String minified(String css) {
        return serialize(css, SerializerOptions.builder().formatting(Formatting.MINIFIED).build());
    }

    private static String serialize(String css, SerializerOptions options) {
        Stylesheet ast = CssParser.parse(css).ast();
        return CssSerializer.serialize(ast, options);
    }
}
