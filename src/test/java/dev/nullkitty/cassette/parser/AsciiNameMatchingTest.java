package dev.nullkitty.cassette.parser;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import dev.nullkitty.cassette.ast.AttributeCase;
import dev.nullkitty.cassette.ast.AttributeSelector;
import dev.nullkitty.cassette.ast.CompoundSelector;
import dev.nullkitty.cassette.ast.Declaration;
import dev.nullkitty.cassette.ast.PseudoClassSelector;
import dev.nullkitty.cassette.ast.SimpleSelector;
import dev.nullkitty.cassette.ast.StyleRule;
import dev.nullkitty.cassette.ast.Stylesheet;
import dev.nullkitty.cassette.serializer.CssSerializer;
import dev.nullkitty.cassette.serializer.Formatting;
import dev.nullkitty.cassette.serializer.SerializerOptions;

/**
 * CSS matches names ASCII case-insensitively, and every one of these was a real misparse.
 *
 * <p>Java's {@code equalsIgnoreCase} additionally folds U+0130 {@code İ}, U+0131 {@code ı} and
 * U+017F {@code ſ} into {@code i} and {@code s}, and its {@code toLowerCase} folds U+212A KELVIN
 * SIGN into {@code k}. The names {@code important}, {@code has}, {@code charset}, {@code import},
 * {@code supports} and {@code -webkit-any} contain exactly those letters, so each case below once
 * parsed as the name it merely resembles.
 *
 * <p>Written with {@code char} constants rather than literal characters. The first attempt at
 * demonstrating the KELVIN case used a plain ASCII {@code K}, which is visually identical in most
 * fonts and therefore proved nothing. A test whose subject is invisible has to name its code
 * points.
 *
 * @see dev.nullkitty.cassette.text.Ascii
 */
class AsciiNameMatchingTest {

    private static final char DOTTED_I = 0x0130;

    private static final char DOTLESS_I = 0x0131;

    private static final char LONG_S = 0x017F;

    private static final char KELVIN = 0x212A;

    private static Stylesheet parse(String css) {
        return CssParser.parse(css).ast();
    }

    private static String minify(String css) {
        return CssSerializer.serialize(parse(css), SerializerOptions.builder().formatting(Formatting.MINIFIED).build());
    }

    private static SimpleSelector lastSimple(String css) {
        StyleRule rule = (StyleRule) parse(css).children().get(0);
        CompoundSelector compound = rule.selectors().selectors().get(0).steps().get(0).compound();

        return compound.simples().get(compound.simples().size() - 1);
    }

    private static boolean isImportant(String css) {
        StyleRule rule = (StyleRule) parse(css).children().get(0);

        return ((Declaration) rule.body().get(0)).important();
    }

    /**
     * The worst of the three, because the output was not merely misparsed but rewritten: the
     * serializer wrote the canonical {@code !important} for a declaration no browser treats as
     * important, so maps-off CSS stopped meaning what its source said.
     */
    @Nested
    @DisplayName("!important")
    class Important {

        @Test
        void isImportantWhenSpelledInAscii() {
            assertThat(isImportant("a{color:red !important}")).isTrue();
            assertThat(isImportant("a{color:red !IMPORTANT}")).isTrue();
            assertThat(minify("a{color:red !IMPORTANT}")).isEqualTo("a{color:red!important}");
        }

        @Test
        void isNotImportantWithADotlessI() {
            assertThat(isImportant("a{color:red !" + DOTLESS_I + "mportant}")).isFalse();
        }

        @Test
        void isNotImportantWithADottedCapitalI() {
            assertThat(isImportant("a{color:red !" + DOTTED_I + "mportant}")).isFalse();
        }

        @Test
        void andTheOutputKeepsWhatWasWritten() {
            // Not just "not important": the flag has to survive into the output as the author
            // wrote it, because rewriting it to `!important` is what changed the meaning.
            assertThat(minify("a{color:red !" + DOTLESS_I + "mportant}")).isEqualTo("a{color:red !"
                                                                                    + DOTLESS_I
                                                                                    + "mportant}");
        }
    }

    /**
     * An attribute selector's case-sensitivity flag, which is where escaping used to change what
     * an identifier matched — the one thing CSS escaping is defined not to do.
     */
    @Nested
    @DisplayName("[attr=value s]")
    class AttributeFlag {

        @Test
        void asciiSIsTheFlag() {
            assertThat(((AttributeSelector) lastSimple("[a=b s]{color:red}")).caseMode()).isEqualTo(AttributeCase.SENSITIVE);
        }

        @Test
        void aLongSIsNotTheFlagWhetherOrNotItIsEscaped() {
            // The same decoded character, written two ways. Before the fix the literal form made
            // the selector invalid and dropped the rule, while the escaped form was accepted as
            // the flag — so escaping an identifier changed what it matched.
            assertThat(minify("[a=b " + LONG_S + "]{color:red}")).isEmpty();
            assertThat(minify("[a=b \\17f]{color:red}")).isEmpty();
        }

        @Test
        void andAnEscapedAsciiSStillIs() {
            // The other half of that: escaping must not change a name that does match either.
            assertThat(minify("[a=b \\73]{color:red}")).isEqualTo("[a=\"b\"s]{color:red}");
        }
    }

    /**
     * Pseudo-class names, which decide whether the argument is parsed as a selector list at all —
     * so a misparse here reaches specificity, nesting expansion and flattening.
     */
    @Nested
    @DisplayName(":has() and :-webkit-any()")
    class PseudoClassNames {

        @Test
        void asciiNamesTakeASelectorList() {
            assertThat(((PseudoClassSelector) lastSimple("a:has(b){color:red}")).selectors()).isNotNull();
            assertThat(((PseudoClassSelector) lastSimple("a:HAS(b){color:red}")).selectors()).isNotNull();

            assertThat(((PseudoClassSelector) lastSimple("a:-webkit-any(b,c){color:red}")).selectors()).isNotNull();

            assertThat(((PseudoClassSelector) lastSimple("a:-webKit-any(b,c){color:red}")).selectors()).isNotNull();
        }

        @Test
        void aKelvinSignIsNotAK() {
            // -webkit-any is the entry that made this live rather than latent: it is the only
            // name in any of the case-insensitively matched sets that contains a k at all.
            assertThat(((PseudoClassSelector) lastSimple("a:-web"
                                                         + KELVIN
                                                         + "it-any(b,c){color:red}")).selectors()).isNull();
        }

        @Test
        void aLongSIsNotAnS() {
            assertThat(((PseudoClassSelector) lastSimple("a:ha" + LONG_S + "(b){color:red}")).selectors()).isNull();
        }

        @Test
        void andTheParserAgreesWithTheAstAboutIt() {
            // These two used to disagree, because one asked equalsIgnoreCase and the other
            // toLowerCase: `:haſ` was parsed with :has's non-forgiving rules while the node it
            // produced said it took no selector list.
            String name = "ha" + LONG_S;

            assertThat(PseudoClassSelector.takesSelectorList(name)).isFalse();

            assertThat(((PseudoClassSelector) lastSimple("a:" + name + "(b){color:red}")).selectors()).isNull();
        }
    }
}
