package dev.nullkitty.cassette.parser;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import dev.nullkitty.cassette.ast.AttributeCase;
import dev.nullkitty.cassette.ast.AttributeMatcher;
import dev.nullkitty.cassette.ast.AttributeSelector;
import dev.nullkitty.cassette.ast.ClassSelector;
import dev.nullkitty.cassette.ast.Combinator;
import dev.nullkitty.cassette.ast.CombinatorStep;
import dev.nullkitty.cassette.ast.ComplexSelector;
import dev.nullkitty.cassette.ast.CompoundSelector;
import dev.nullkitty.cassette.ast.IdSelector;
import dev.nullkitty.cassette.ast.NestingSelector;
import dev.nullkitty.cassette.ast.PseudoClassSelector;
import dev.nullkitty.cassette.ast.PseudoElementSelector;
import dev.nullkitty.cassette.ast.SelectorList;
import dev.nullkitty.cassette.ast.SimpleSelector;
import dev.nullkitty.cassette.ast.StyleRule;
import dev.nullkitty.cassette.ast.TypeSelector;
import dev.nullkitty.cassette.diagnostics.Severity;

/**
 * Selectors Level 4, reached the way real input reaches it: as a style rule's prelude.
 *
 * <p>Going through {@link CssParser} rather than calling the selector parser directly is
 * deliberate, the handoff from the rule grammar to the selector grammar, and the token
 * range it passes, is exactly the part worth covering.
 */
class SelectorParserTest {

    @Nested
    class SimpleSelectors {

        @Test
        void parsesATypeSelector() {
            TypeSelector type = (TypeSelector) onlySimple("div");

            assertThat(type.name()).isEqualTo("div");
            assertThat(type.namespace()).isNull();
            assertThat(type.isUniversal()).isFalse();
        }

        @Test
        void parsesTheUniversalSelector() {
            assertThat(((TypeSelector) onlySimple("*")).isUniversal()).isTrue();
        }

        @Test
        void parsesAClassAndAnId() {
            assertThat(((ClassSelector) onlySimple(".card")).name()).isEqualTo("card");
            assertThat(((IdSelector) onlySimple("#main")).name()).isEqualTo("main");
        }

        @Test
        void parsesTheNestingSelector() {
            assertThat(onlySimple("&")).isInstanceOf(NestingSelector.class);
        }

        @Test
        void resolvesEscapesInNames() {
            assertThat(((ClassSelector) onlySimple(".caf\\e9")).name()).isEqualTo("café");
        }

        @Test
        void parsesACompoundSelectorInWrittenOrder() {
            List<SimpleSelector> simples = onlyCompound("div.card:hover").simples();

            assertThat(simples).hasExactlyElementsOfTypes(TypeSelector.class,
                                                          ClassSelector.class,
                                                          PseudoClassSelector.class);
        }

        @Test
        void looksThroughCommentsInsideACompoundSelector() {
            // The tokenizer would have dropped these; cassette keeps them, so the selector
            // grammar has to skip them itself or `.a/*x*/.b` stops being one compound.
            assertThat(onlyCompound(".a/* x */.b").simples()).hasSize(2);
        }
    }

    @Nested
    class Namespaces {

        @ParameterizedTest
        @CsvSource({ "svg|circle,svg,circle", "*|circle,*,circle", "|circle,'',circle", "*|*,*,*" })
        void parsesNamespacePrefixes(String selector, String namespace, String name) {
            TypeSelector type = (TypeSelector) onlySimple(selector);

            assertThat(type.namespace()).isEqualTo(namespace);
            assertThat(type.name()).isEqualTo(name);
        }

        @Test
        void doesNotTreatAnUnprefixedNameAsANamespace() {
            assertThat(((TypeSelector) onlySimple("div")).namespace()).isNull();
        }

        @Test
        void keepsTheColumnCombinatorOutOfTheNamespaceRule() {
            List<CombinatorStep> steps = onlyComplex("a||b").steps();

            assertThat(steps).hasSize(2);
            assertThat(steps.get(1).combinator()).isEqualTo(Combinator.COLUMN);
        }
    }

    @Nested
    class Combinators {

        @ParameterizedTest
        @CsvSource({ "a b,DESCENDANT", "a > b,CHILD", "a+b,NEXT_SIBLING", "a ~ b,SUBSEQUENT_SIBLING" })
        void parsesEachCombinator(String selector, Combinator expected) {
            List<CombinatorStep> steps = onlyComplex(selector).steps();

            assertThat(steps).hasSize(2);
            assertThat(steps.get(0).combinator()).isEqualTo(Combinator.NONE);
            assertThat(steps.get(1).combinator()).isEqualTo(expected);
        }

        @Test
        void ignoresWhitespaceAroundAnExplicitCombinator() {
            assertThat(onlyComplex("a   >   b").steps()).hasSize(2);
        }

        @Test
        void parsesALongChain() {
            assertThat(onlyComplex("div > p + span ~ a em").steps()).hasSize(5);
        }

        @Test
        void splitsASelectorListOnCommas() {
            SelectorList list = selectors(".card,\n.panel");

            assertThat(list.selectors()).hasSize(2);
            assertThat(list.isMultiple()).isTrue();
        }
    }

    @Nested
    class Attributes {

        @Test
        void parsesAPresenceTest() {
            AttributeSelector attribute = (AttributeSelector) onlySimple("[href]");

            assertThat(attribute.name()).isEqualTo("href");
            assertThat(attribute.matcher()).isEqualTo(AttributeMatcher.PRESENT);
            assertThat(attribute.value()).isNull();
        }

        @ParameterizedTest
        @CsvSource({ "'[a=b]',EXACT",
                     "'[a~=b]',INCLUDES",
                     "'[a|=b]',DASH",
                     "'[a^=b]',PREFIX",
                     "'[a$=b]',SUFFIX",
                     "'[a*=b]',SUBSTRING" })
        void parsesEachMatcher(String selector, AttributeMatcher expected) {
            AttributeSelector attribute = (AttributeSelector) onlySimple(selector);

            assertThat(attribute.matcher()).isEqualTo(expected);
            assertThat(attribute.value()).isEqualTo("b");
        }

        @Test
        void acceptsAQuotedValueAndDropsTheQuotes() {
            assertThat(((AttributeSelector) onlySimple("[href^=\"https\"]")).value()).isEqualTo("https");
        }

        @ParameterizedTest
        @CsvSource({ "'[a=b i]',INSENSITIVE", "'[a=b s]',SENSITIVE", "'[a=b]',UNSPECIFIED" })
        void parsesTheCaseModifier(String selector, AttributeCase expected) {
            assertThat(((AttributeSelector) onlySimple(selector)).caseMode()).isEqualTo(expected);
        }

        @Test
        void allowsWhitespaceInsideTheBrackets() {
            AttributeSelector attribute = (AttributeSelector) onlySimple("[ href ^= \"https\" i ]");

            assertThat(attribute.matcher()).isEqualTo(AttributeMatcher.PREFIX);
            assertThat(attribute.caseMode()).isEqualTo(AttributeCase.INSENSITIVE);
        }

        @Test
        void parsesANamespacedAttribute() {
            assertThat(((AttributeSelector) onlySimple("[xlink|href]")).namespace()).isEqualTo("xlink");
        }
    }

    @Nested
    class Pseudos {

        @Test
        void parsesAPlainPseudoClass() {
            PseudoClassSelector pseudo = (PseudoClassSelector) onlySimple(":hover");

            assertThat(pseudo.name()).isEqualTo("hover");
            assertThat(pseudo.functional()).isFalse();
            assertThat(pseudo.selectors()).isNull();
        }

        @Test
        void keepsOpaqueFunctionalArgumentsUnparsed() {
            PseudoClassSelector pseudo = (PseudoClassSelector) onlySimple(":lang(en-GB)");

            assertThat(pseudo.functional()).isTrue();
            assertThat(pseudo.arguments()).hasSize(1);
            assertThat(pseudo.selectors()).isNull();
        }

        @Test
        void parsesADoubleColonPseudoElement() {
            PseudoElementSelector pseudo = (PseudoElementSelector) onlySimple("::before");

            assertThat(pseudo.name()).isEqualTo("before");
            assertThat(pseudo.doubleColon()).isTrue();
        }

        @Test
        void keepsTheLegacySingleColonSpelling() {
            // Rewriting this to `::before` would break the engines legacy mode exists for.
            PseudoElementSelector pseudo = (PseudoElementSelector) onlySimple(":before");

            assertThat(pseudo.name()).isEqualTo("before");
            assertThat(pseudo.doubleColon()).isFalse();
        }

        @Test
        void parsesAFunctionalPseudoElement() {
            PseudoElementSelector pseudo = (PseudoElementSelector) onlySimple("::part(label)");

            assertThat(pseudo.functional()).isTrue();
            assertThat(pseudo.arguments()).hasSize(1);
        }

        @Test
        void parsesSelectorArgumentsStructurally() {
            PseudoClassSelector pseudo = (PseudoClassSelector) onlySimple(":is(.a, #b > c)");

            assertThat(pseudo.selectors()).isNotNull();
            assertThat(pseudo.selectors().selectors()).hasSize(2);
            assertThat(pseudo.arguments()).isEmpty();
        }

        @Test
        void findsANestingSelectorInsideAFunctionalPseudoClass() {
            ComplexSelector selector = onlyComplex(":is(& .a)");

            assertThat(selector.containsNestingSelector()).isTrue();
        }

        @Test
        void dropsOnlyTheBadAlternativesOfAForgivingList() {
            ParseResult result = CssParser.parse(":is(.a, %%%, .b) { top: 0 }");

            PseudoClassSelector pseudo = (PseudoClassSelector) firstSimple(result);
            assertThat(pseudo.selectors().selectors()).hasSize(2);
            assertThat(result.diagnostics(Severity.WARNING)).hasSize(1);
            assertThat(result.hasErrors()).isFalse();
        }

        @Test
        void invalidatesTheWholeRuleForABadArgumentToNot() {
            // :not() is not forgiving; one bad alternative takes the rule with it.
            ParseResult result = CssParser.parse(":not(.a, %%%) { top: 0 }");

            assertThat(result.ast().rules()).isEmpty();
            assertThat(result.hasErrors()).isTrue();
        }

        @ParameterizedTest
        @CsvSource({ ":has(> img),CHILD", ":has(+ p),NEXT_SIBLING", ":has(~ a),SUBSEQUENT_SIBLING", ":has(img),NONE" })
        void acceptsALeadingCombinatorInsideHas(String selector, Combinator expected) {
            // :has() takes a relative selector list: the combinator relates its argument to
            // the scoping element, so there is nothing to its left and that is legal.
            PseudoClassSelector pseudo = (PseudoClassSelector) onlySimple(selector);

            assertThat(pseudo.selectors().selectors().get(0).steps().get(0).combinator()).isEqualTo(expected);
        }

        @Test
        void rejectsALeadingCombinatorOutsideHas() {
            // :not() is not forgiving, so an argument that is only legal inside :has()
            // takes the rule with it.
            ParseResult result = CssParser.parse(":not(> img) { top: 0 }");

            assertThat(result.ast().rules()).isEmpty();
            assertThat(result.hasErrors()).isTrue();
        }

        @Test
        void dropsALeadingCombinatorFromAForgivingList() {
            ParseResult result = CssParser.parse(":is(> img) { top: 0 }");

            PseudoClassSelector pseudo = (PseudoClassSelector) firstSimple(result);
            assertThat(pseudo.selectors().selectors()).isEmpty();
            assertThat(result.diagnostics(Severity.WARNING)).hasSize(1);
            assertThat(result.hasErrors()).isFalse();
        }

        @Test
        void splitsNthChildAtTheOfKeyword() {
            PseudoClassSelector pseudo = (PseudoClassSelector) onlySimple(":nth-child(2n+1 of .a)");

            assertThat(pseudo.arguments()).isNotEmpty();
            assertThat(pseudo.selectors().selectors()).hasSize(1);
        }

        @Test
        void leavesNthChildWithoutAnOfClauseOpaque() {
            PseudoClassSelector pseudo = (PseudoClassSelector) onlySimple(":nth-child(2n+1)");

            assertThat(pseudo.selectors()).isNull();
            assertThat(pseudo.arguments()).isNotEmpty();
        }
    }

    @Nested
    class Invalid {

        @ParameterizedTest
        @ValueSource(
            strings = { "#123", //    a hash that is not an identifier
                        ".", //       a class with no name
                        "a >", //     a combinator with nothing after it
                        "a > > b", // two combinators in a row
                        "a,", //      a trailing comma
                        ",a", //      a leading comma
                        "[a=]", //    a matcher with no value
                        "[a", //      an unclosed attribute selector
                        ":", //       a colon with no name
                        "%", //       not a selector at all
                        ":is(.a", //  an unclosed functional pseudo-class
            })
        void reportsAndDropsTheRule(String selector) {
            ParseResult result = CssParser.parse(selector + " { top: 0 }");

            assertThat(result.ast().rules()).as("rule should be dropped").isEmpty();
            assertThat(result.diagnostics(Severity.ERROR)).as("failure should be reported").isNotEmpty();
        }

        @Test
        void keepsParsingAfterADroppedRule() {
            ParseResult result = CssParser.parse("% { top: 0 } a { top: 0 }");

            assertThat(result.ast().rules()).hasSize(1);
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static SelectorList selectors(String selector) {
        ParseResult result = CssParser.parse(selector + " { top: 0 }");
        assertThat(result.diagnostics(Severity.ERROR)).as("unexpected error parsing '%s'", selector).isEmpty();
        return ((StyleRule) result.ast().rules().get(0)).selectors();
    }

    private static ComplexSelector onlyComplex(String selector) {
        List<ComplexSelector> list = selectors(selector).selectors();
        assertThat(list).hasSize(1);
        return list.get(0);
    }

    private static CompoundSelector onlyCompound(String selector) {
        List<CombinatorStep> steps = onlyComplex(selector).steps();
        assertThat(steps).hasSize(1);
        return steps.get(0).compound();
    }

    private static SimpleSelector onlySimple(String selector) {
        List<SimpleSelector> simples = onlyCompound(selector).simples();
        assertThat(simples).hasSize(1);
        return simples.get(0);
    }

    private static SimpleSelector firstSimple(ParseResult result) {
        return ((StyleRule) result.ast().rules().get(0)).selectors().selectors().get(0).first().simples().get(0);
    }
}
