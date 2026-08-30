package dev.nullkitty.cassette.serializer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import dev.nullkitty.cassette.ast.Specificity;
import dev.nullkitty.cassette.ast.StyleRule;
import dev.nullkitty.cassette.ast.Stylesheet;
import dev.nullkitty.cassette.parser.CssParser;

/**
 * Nesting, taken back out: {@link NestingMode#FLATTEN}.
 *
 * <p>Asserted through the serializer rather than on the rewritten tree, because the output
 * text is the thing that has to be right and a selector is far easier to read than the
 * three levels of record it is made of.
 */
class FlatteningTest {

    @Nested
    class NestingSelector {

        @Test
        void prependsTheParentWhenNoAmpersandWasWritten() {
            assertThat(flatten(".card { .title { top: 0 } }")).isEqualTo(".card .title{top:0}");
        }

        @Test
        void keepsALeadingCombinatorAndImpliesTheAmpersandBeforeIt() {
            // A nested prelude is a relative selector list: '> .title' is '& > .title'.
            assertThat(flatten(".card { > .title { top: 0 } }")).isEqualTo(".card>.title{top:0}");
        }

        @Test
        void splicesTheParentInPlaceOfALeadingAmpersand() {
            assertThat(flatten(".card { & .title { top: 0 } }")).isEqualTo(".card .title{top:0}");
        }

        @Test
        void joinsACompoundedAmpersandToTheParentsLastCompound() {
            assertThat(flatten(".card .body { &.open { top: 0 } }")).isEqualTo(".card .body.open{top:0}");
        }

        @Test
        void doesNotPrependWhenTheAmpersandIsWrittenElsewhere() {
            // The '.theme-dark &' pattern: the selector is already absolute, so nothing is
            // prepended and the result is '.theme-dark .card', not '.card .theme-dark .card'.
            assertThat(flatten(".card { .theme-dark & { top: 0 } }")).isEqualTo(".theme-dark .card{top:0}");
        }

        @Test
        void inlinesASingleCompoundParentIntoTheMiddleOfACompound() {
            assertThat(flatten(".card { .open& { top: 0 } }")).isEqualTo(".open.card{top:0}");
        }

        @Test
        void wrapsAComplexParentThatCannotBeSplicedInPlace() {
            // '.x > .a .b' would relate '.x' to '.a', not to the element '&' matches.
            assertThat(flatten(".a .b { .x > & { top: 0 } }")).isEqualTo(".x>:is(.a .b){top:0}");
        }

        @Test
        void wrapsATypeSelectorParentRatherThanConcatenatingIt() {
            // '.open' + 'div' inlined would spell '.opendiv'.
            assertThat(flatten("div { .open& { top: 0 } }")).isEqualTo(".open:is(div){top:0}");
        }

        @Test
        void substitutesInsideAFunctionalPseudoClass() {
            assertThat(flatten(".card { :is(& .title, .other) { top: 0 } }")).isEqualTo(":is(.card .title,.other){top:0}");
        }

        @Test
        void nestsThreeDeep() {
            assertThat(flatten(".a { .b { .c { top: 0 } } }")).isEqualTo(".a .b .c{top:0}");
        }
    }

    @Nested
    class MultipleParents {

        @Test
        void wrapsTheParentListInIsByDefault() {
            assertThat(flatten(".card, .panel { & .title { top: 0 } }")).isEqualTo(":is(.card,.panel) .title{top:0}");
        }

        @Test
        void wrapsTheParentListEvenWithNoAmpersandWritten() {
            assertThat(flatten(".card, .panel { .title { top: 0 } }")).isEqualTo(":is(.card,.panel) .title{top:0}");
        }

        @Test
        void duplicatesInsteadUnderTheLegacyOption() {
            assertThat(flattenLegacy(".card, .panel { & .title { top: 0 } }")).isEqualTo(".card .title,.panel .title{top:0}");
        }

        @Test
        void duplicatesAcrossTwoLevelsOfNesting() {
            assertThat(flattenLegacy(".a, .b { .c, .d { top: 0 } }")).isEqualTo(".a .c,.b .c,.a .d,.b .d{top:0}");
        }

        @Test
        void keepsTheSpecificityIsWrappingIsChosenFor() {
            String css = "#id, .cls { & .title { top: 0 } }";

            // :is() takes its most specific argument, which is exactly what '&' contributes:
            // ':is(#id, .cls) .title' weighs the same as '#id .title'.
            assertThat(specificityOf(css, NestingExpansion.IS_WRAP)).isEqualTo(new Specificity(1, 1, 0));

            // Duplicating cannot preserve it; each copy carries only its own weight, so
            // '.cls .title' comes out a class lighter than '&' was. That is the trade the
            // legacy option makes.
            assertThat(specificitiesOf(css, NestingExpansion.DUPLICATE)).containsExactly(new Specificity(1, 1, 0),
                                                                                         new Specificity(0, 2, 0));
        }

        private Specificity specificityOf(String css, NestingExpansion expansion) {
            return specificitiesOf(css, expansion).get(0);
        }

        private List<Specificity> specificitiesOf(String css, NestingExpansion expansion) {
            StyleRule nested = (StyleRule) flattenAst(css, expansion).rules().get(0);
            return nested.selectors().selectors().stream().map(selector -> selector.specificity()).toList();
        }
    }

    @Nested
    class RuleStructure {

        @Test
        void splitsARuleWhereANestedRuleInterruptsItsDeclarations() {
            // Order is cascade order: 'background' is written after the nested rule and has
            // to stay after it.
            assertThat(flatten(".a { color: red; .b { top: 0 } background: blue }")).isEqualTo(".a{color:red}.a .b{top:0}.a{background:blue}");
        }

        @Test
        void emitsNoRuleForANestingOnlyParent() {
            assertThat(flatten(".a { .b { top: 0 } }")).isEqualTo(".a .b{top:0}");
        }

        @Test
        void hoistsANestedGroupRuleAndWrapsItsDeclarations() {
            assertThat(flatten(".a { @media print { color: red } }")).isEqualTo("@media print{.a{color:red}}");
        }

        @Test
        void absolutizesInsideAGroupRule() {
            assertThat(flatten("@media print { .a { .b { top: 0 } } }")).isEqualTo("@media print{.a .b{top:0}}");
        }

        @Test
        void leavesNestedGroupRulesNested() {
            // Merging the conditions would mean evaluating media queries, which is a stated
            // non-goal; every engine that knows @media knows nested ones.
            assertThat(flatten("@media print { @supports (display: grid) { .a { top: 0 } } }")).isEqualTo("@media print{@supports(display:grid){.a{top:0}}}");
        }

        @Test
        void leavesAnOpaqueAtRuleAlone() {
            assertThat(flatten("@keyframes k { from { left: 0 } }")).isEqualTo("@keyframes k{from{left:0}}");
        }

        @Test
        void leavesATopLevelAmpersandAlone() {
            // At the top level '&' has no parent to stand for; rewriting it would be a guess.
            assertThat(flatten("& .a { top: 0 }")).isEqualTo("& .a{top:0}");
        }
    }

    // -----------------------------------------------------------------------

    private static String flatten(String css) {
        return serialize(css, NestingExpansion.IS_WRAP);
    }

    private static String flattenLegacy(String css) {
        return serialize(css, NestingExpansion.DUPLICATE);
    }

    private static String serialize(String css, NestingExpansion expansion) {
        return CssSerializer.serialize(CssParser.parse(css).ast(),
                                       SerializerOptions.builder().nesting(NestingMode.FLATTEN)
                                                        .nestingExpansion(expansion).formatting(Formatting.MINIFIED)
                                                        .build());
    }

    private static Stylesheet flattenAst(String css, NestingExpansion expansion) {
        return Flattener.flatten(CssParser.parse(css).ast(), expansion);
    }
}
