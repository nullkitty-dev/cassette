package dev.nullkitty.cassette.text;

/**
 * ASCII case folding, which is the only kind CSS name matching is allowed to do.
 *
 * <p>Java's two obvious spellings are both wrong here, in different ways.
 * {@link String#toLowerCase()} is a Unicode operation, so U+212A KELVIN SIGN folds to {@code k}
 * and an identifier is renamed rather than case-folded. {@link String#equalsIgnoreCase} compares
 * through {@link Character#toUpperCase(char)} and additionally matches U+0130 {@code İ} and U+0131
 * {@code ı} against {@code i}, and U+017F {@code ſ} against {@code s}. Those letters appear in
 * {@code important}, {@code charset}, {@code import}, {@code supports} and {@code has}.
 * {@code AsciiTest} names each one.
 *
 * <p>CSS specifies ASCII case-insensitive matching for identifiers, at-rule names, pseudo-class
 * names and attribute-selector flags. Everything else is a different character, and a stylesheet
 * naming {@code !ımportant} is naming something no browser has ever heard of.
 *
 * <p>A package of its own because these belong wherever names are matched, which includes
 * {@code ast}. {@code ast} imports nothing else in the library, while {@code lexer} imports
 * {@code ast.SourceSpan}, so putting them in {@code lexer} beside the rest of the character
 * utilities would make {@code ast} depend on {@code lexer} and close a cycle. They sit below both
 * instead, in a package that imports nothing and is never exported.
 *
 * <p>Only case folding lives here. The classifying predicates and the {@code isIdent} bit table
 * stay in {@code lexer} beside the tokenizer that reads them, because nothing above needs them and
 * that loop is measured in instructions per token.
 *
 * @see <a href="https://www.w3.org/TR/css-syntax-3/#ascii-case-insensitive">CSS Syntax Level 3
 *      §3 ASCII case-insensitive</a>
 */
public final class Ascii {

    /**
     * Lowercases one ASCII letter, leaving every other code unit alone.
     *
     * @param c the character to fold
     * @return the folded character
     */
    public static char lower(char c) {
        return c >= 'A' && c <= 'Z' ? (char) (c + 0x20) : c;
    }

    /**
     * Lowercases the ASCII letters in {@code text}, leaving everything else alone.
     *
     * @param text the text to fold
     * @return the folded text, or {@code text} itself when it holds no uppercase ASCII
     */
    public static String lower(String text) {
        for (int at = 0; at < text.length(); at++) {
            char c = text.charAt(at);

            if (c >= 'A' && c <= 'Z') {
                return lowerFrom(text, at);
            }
        }

        return text;
    }

    /**
     * Whether two strings are equal ignoring ASCII case, and ignoring nothing else.
     *
     * <p>The replacement for {@link String#equalsIgnoreCase} everywhere a CSS name is matched.
     * Compares code unit by code unit, which is safe without surrogate handling because the only
     * characters it folds are ASCII and a surrogate is never equal to one.
     *
     * @param actual   the name found in the stylesheet
     * @param expected the name to match it against, ordinarily an ASCII literal
     * @return whether they match
     */
    public static boolean equalsIgnoreCase(String actual, //
                                           String expected) {
        if (actual.length() != expected.length()) {
            return false;
        }

        for (int at = 0; at < expected.length(); at++) {
            char found = actual.charAt(at);
            char want = expected.charAt(at);
            if (found != want && lower(found) != lower(want)) {
                return false;
            }
        }

        return true;
    }

    private static String lowerFrom(String text, //
                                    int firstUpper) {
        char[] lowered = text.toCharArray();
        for (int at = firstUpper; at < lowered.length; at++) {
            lowered[at] = lower(lowered[at]);
        }

        // Deliberately not interned. A cache keyed by the string pays the same character
        // scan in hashCode and equals that the allocation costs, and it would be static
        // mutable state with no bound in a library that is otherwise stateless. It would
        // also almost never hit: lower returns its argument untouched when there is no
        // uppercase, and real CSS has next to none. Bootstrap holds 405 uppercase
        // characters and Tailwind 737, so this method is barely reached at all.
        return new String(lowered);
    }

    private Ascii() {
        // static only
    }
}
