package dev.nullkitty.cassette.serializer;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import dev.nullkitty.cassette.ast.AtRule;
import dev.nullkitty.cassette.ast.Comment;
import dev.nullkitty.cassette.ast.ComponentValue;
import dev.nullkitty.cassette.ast.ConditionalGroupRule;
import dev.nullkitty.cassette.ast.Declaration;
import dev.nullkitty.cassette.ast.DimensionToken;
import dev.nullkitty.cassette.ast.HashToken;
import dev.nullkitty.cassette.ast.Node;
import dev.nullkitty.cassette.ast.NumberToken;
import dev.nullkitty.cassette.ast.PercentageToken;
import dev.nullkitty.cassette.ast.Stylesheet;
import dev.nullkitty.cassette.lexer.CodePoints;
import dev.nullkitty.cassette.sourcemap.SourceMap;
import dev.nullkitty.cassette.text.Ascii;

/**
 * The optimizations that ship with the library, each one on its own.
 *
 * <p>None of these is on by default, and none of them is implied by
 * {@link Formatting#MINIFIED}, which only removes whitespace and comments. Everything here
 * changes the bytes of a value rather than the space around it, so it is a decision the
 * caller makes:
 *
 * <pre>{@code
 * Stylesheet smaller = Optimizer.optimize(ast, Optimizations.all());
 * }</pre>
 *
 * <p>None of these is a size feature, which is measured. Over the two real dist builds in the
 * benchmark corpus, {@code all()} costs 11–13% of a parse and buys 0.2% of bytes raw and nothing
 * compressed; on Bootstrap the gzipped output is a few bytes larger, because shortening a colour
 * removes a repetition deflate was exploiting. They are worth running for the rewriting, not for
 * the bytes. {@code ./gradlew minifyRate} prints the table.
 *
 * <p>Where the little there is comes from: {@link #compactNumbers()} is essentially all of it and
 * {@link #dropZeroUnits()} the remainder on Tailwind. {@link #shortenColors()} and
 * {@link #lowercaseNames()} save <em>nothing</em> on either, since real generated CSS already uses
 * short hex and lowercase names. They earn their place on hand-written input and, for
 * {@code lowercaseNames}, on making {@code @MEDIA} and {@code @media} comparable to a tool.
 *
 * <p>Not implemented: shorthand merging, folding {@code margin-top} and its three siblings into one
 * {@code margin}. It needs a property database and a cascade-aware view of a rule that this library
 * does not have, and getting it subtly wrong changes what a stylesheet means.
 *
 * <p>Declined rather than pending: dropping empty rules. Real CSS has none, zero in 41,853 rules
 * across the corpus, and flattening creates none, so it would save nothing. {@code @layer base {}}
 * is also not inert the way {@code @media print {}} is, since it declares the layer and fixes its
 * position in the cascade.
 */
public final class Optimizations {

    /**
     * Units where a zero measurement means the same thing without them.
     *
     * <p>Lengths only. {@code 0s} is not {@code 0}; a time is required where a time is
     * expected, and {@code 0%} is not {@code 0} inside {@code hsl()} or a gradient stop.
     */
    private static final Set<String> ZEROABLE_LENGTHS = Set.of("px",
                                                               "em",
                                                               "rem",
                                                               "ex",
                                                               "ch",
                                                               "cap",
                                                               "ic",
                                                               "lh",
                                                               "rlh",
                                                               "cm",
                                                               "mm",
                                                               "q",
                                                               "in",
                                                               "pt",
                                                               "pc",
                                                               "vw",
                                                               "vh",
                                                               "vi",
                                                               "vb",
                                                               "vmin",
                                                               "vmax",
                                                               "svw",
                                                               "svh",
                                                               "lvw",
                                                               "lvh",
                                                               "dvw",
                                                               "dvh");

    /**
     * Every optimization that rewrites a value, in the order they are cheapest to reason about.
     *
     * <p>{@link #dropCharset()} and {@link #dropSourceMappingUrl()} are absent. Everything in this
     * list rewrites a value and leaves the stylesheet computing exactly what it computed before, so
     * it is safe for a caller who has not thought about it. The two dropping transforms remove an
     * <em>assertion the input made about itself</em>, and whether that assertion has become false
     * depends on what the caller does with the output. This list cannot answer that on anyone's
     * behalf, so each has to be asked for by name.
     *
     * @return the transforms, ready for {@link Optimizer#optimize}
     */
    public static List<NodeTransform<?>> all() {
        return List.of(lowercaseNames(), shortenColors(), dropZeroUnits(), compactNumbers());
    }

    /**
     * Removes every {@code @charset} rule.
     *
     * <p>Enabling this is an assertion only the caller can make. {@code CssSerializer} returns a
     * {@code String}, and whoever encodes it decides what the bytes are, so the library cannot tell
     * whether {@code @charset "shift_jis"} still describes the output. It does when the output is
     * written as Shift_JIS; written as UTF-8, the rule makes the file decode back as Shift_JIS and
     * corrupt. Enabling this asserts that the rule no longer describes the output, so it is not in
     * {@link #all()}.
     *
     * <p>Every one, not only the ones naming something other than UTF-8. A stylesheet with no
     * declared encoding and no protocol charset is UTF-8 by §3.2's fallback, so
     * {@code @charset "utf-8"} asserts what would be assumed anyway, and dropping it is safe.
     * {@code Bundler} already drops all of them for the same reason: by the time a tree exists the
     * text is decoded and the rule has nothing left to describe.
     *
     * <p>Rules directly inside a stylesheet or a conditional group rule. {@code @charset} is
     * only meaningful as the first thing in a file, so there is nowhere else for a real one to
     * be.
     *
     * @return the transform
     * @see <a href="https://www.w3.org/TR/css-syntax-3/#charset-rule">CSS Syntax Level 3 §9.3 The
     *      &#64;charset Rule</a>
     * @see <a href="https://www.w3.org/TR/css-syntax-3/#input-byte-stream">CSS Syntax Level 3 §3.2</a>
     */
    public static NodeTransform<Node> dropCharset() {
        return NodeTransform.of(HOLDERS_OF_CHILDREN,
                                node -> dropChildren(node,
                                                     child -> child instanceof AtRule rule
                                                              && Ascii.equalsIgnoreCase(rule.name(), "charset")));
    }

    /**
     * Removes every {@code sourceMappingURL} comment.
     *
     * <p>The map an annotation names was generated against the <em>input</em>, and every offset
     * in it moved the moment anything reformatted. No way of writing the output makes the old map
     * correct again, so unlike {@link #dropCharset()} this asserts nothing on the caller's behalf.
     *
     * <p>It is still not in {@link #all()}, because a caller who re-serializes as one step of a
     * pipeline that regenerates the map afterwards keeps the annotation on purpose.
     *
     * <p>{@link Formatting#MINIFIED} already removes it along with every other comment, so this is
     * for the formatting path, and for a bundle: concatenation keeps one annotation per input and
     * a tool honours the <em>last</em> one in a file, so the bundle claims whichever input came
     * last for all of it.
     *
     * @return the transform
     * @see SourceMap#isTrailer
     * @see <a href="https://tc39.es/ecma426/#sec-linking-inline">Source Map (ECMA-426) §11.1.2 Linking
     *      through inline annotations</a>
     */
    public static NodeTransform<Node> dropSourceMappingUrl() {
        return NodeTransform.of(HOLDERS_OF_CHILDREN,
                                node -> dropChildren(node,
                                                     child -> child instanceof Comment comment
                                                              && SourceMap.isTrailer(comment.text())));
    }

    /**
     * The node types that hold a rule list, which is where anything droppable sits.
     *
     * <p>A transform cannot remove itself, because {@link NodeTransform#apply} returns a replacement
     * and never null, so removal is expressed as the <em>parent</em> rebuilding its child list. Both
     * types are walked by {@link Optimizer}, so this needs no change there.
     */
    private static final Set<Class<? extends Node>> HOLDERS_OF_CHILDREN =
        Set.of(Stylesheet.class, ConditionalGroupRule.class);

    /**
     * Rebuilds {@code parent} without the children {@code unwanted} accepts, or returns it.
     */
    private static Node dropChildren(Node parent, Predicate<Node> unwanted) {
        List<Node> children =
            parent instanceof Stylesheet sheet ? sheet.children() : ((ConditionalGroupRule) parent).body();
        List<Node> kept = null;

        for (int index = 0; index < children.size(); index++) {
            Node child = children.get(index);
            if (unwanted.test(child)) {
                if (kept == null) {
                    kept = new ArrayList<>(children.subList(0, index));
                }
            }
            else if (kept != null) {
                kept.add(child);
            }
        }

        if (kept == null) {
            // Declining by identity, which is how an unchanged subtree avoids being reallocated.
            return parent;
        }

        return parent instanceof Stylesheet sheet ? new Stylesheet(kept, sheet.packedSpan())
                                                  : rebuild((ConditionalGroupRule) parent, kept);
    }

    private static ConditionalGroupRule rebuild(ConditionalGroupRule rule, List<Node> body) {
        return new ConditionalGroupRule(rule.name(), rule.prelude(), body, rule.packedSpan());
    }

    /**
     * Writes numbers in their shortest equivalent form: {@code 0.500} as {@code .5},
     * {@code +5} as {@code 5}, {@code 5.} as {@code 5}.
     *
     * <p>Numbers written with an exponent are left alone. {@code 1e2} is already short, and
     * whether {@code 100} is shorter depends on the exponent.
     *
     * @return the transform
     */
    public static NodeTransform<ComponentValue> compactNumbers() {
        return NodeTransform.of(Set.<Class<? extends ComponentValue>> of(NumberToken.class,
                                                                         PercentageToken.class,
                                                                         DimensionToken.class),
                                Optimizations::compactNumber);
    }

    private static ComponentValue compactNumber(ComponentValue value) {
        return switch (value) {
            case NumberToken token -> {
                String raw = compact(token.rawText(), token.hasExponent());
                yield raw.equals(token.rawText()) ? token
                                                  : new NumberToken(raw,
                                                                    token.value(),
                                                                    false,
                                                                    token.hasExponent(),
                                                                    token.span());
            }

            case PercentageToken token -> {
                String raw = compact(token.rawText(), token.hasExponent());
                yield raw.equals(token.rawText()) ? token
                                                  : new PercentageToken(raw,
                                                                        token.value(),
                                                                        false,
                                                                        token.hasExponent(),
                                                                        token.span());
            }

            case DimensionToken token -> {
                String raw = compact(token.rawText(), token.hasExponent());
                yield raw.equals(token.rawText()) ? token
                                                  : new DimensionToken(raw,
                                                                       token.value(),
                                                                       token.unit(),
                                                                       false,
                                                                       token.hasExponent(),
                                                                       token.span());
            }

            default -> value;
        };
    }

    /**
     * Drops the unit from a zero length: {@code margin: 0px} becomes {@code margin: 0}.
     *
     * <p>Only at the top level of a declaration's value, never inside a function. In
     * {@code calc()} a unitless zero is a different type from {@code 0px}, and
     * {@code calc(0 + 5%)} does not parse where {@code calc(0px + 5%)} does.
     *
     * @return the transform
     */
    public static NodeTransform<Declaration> dropZeroUnits() {
        return NodeTransform.of(Declaration.class, Optimizations::dropZeroUnits);
    }

    private static Declaration dropZeroUnits(Declaration declaration) {
        if (declaration.isCustomProperty()) {
            return declaration;
        }

        List<ComponentValue> values = declaration.value();
        List<ComponentValue> rebuilt = null;

        for (int index = 0; index < values.size(); index++) {
            ComponentValue value = values.get(index);
            ComponentValue replacement = value;

            if (value instanceof DimensionToken dimension
                && dimension.value() == 0
                && ZEROABLE_LENGTHS.contains(Ascii.lower(dimension.unit()))) {
                replacement = new NumberToken(dimension.rawText(),
                                              dimension.value(),
                                              dimension.hasSign(),
                                              dimension.hasExponent(),
                                              dimension.span());
            }

            if (rebuilt == null && replacement != value) {
                rebuilt = new ArrayList<>(values.size());
                rebuilt.addAll(values.subList(0, index));
            }

            if (rebuilt != null) {
                rebuilt.add(replacement);
            }
        }

        return rebuilt == null ? declaration
                               : new Declaration(declaration.property(),
                                                 rebuilt,
                                                 declaration.important(),
                                                 declaration.span());
    }

    /**
     * Shortens and lowercases hex colors: {@code #AABBCC} becomes {@code #abc},
     * {@code #FF00FF80} becomes {@code #f0f8}.
     *
     * <p>Assumes a hash token in a value is a color, which is what one is in every context this
     * library will meet. A hash token that is an identifier, inside an
     * {@code @supports selector(#Main)} prelude, say, would be lowercased with it, so this is
     * opt-in like everything else here.
     *
     * @return the transform
     */
    public static NodeTransform<ComponentValue> shortenColors() {
        return NodeTransform.of(Set.<Class<? extends ComponentValue>> of(HashToken.class),
                                Optimizations::shortenColorValue);
    }

    private static ComponentValue shortenColorValue(ComponentValue value) {
        return value instanceof HashToken token ? shortenColor(token) : value;
    }

    private static HashToken shortenColor(HashToken token) {
        String value = token.value();
        if (!isHex(value)) {
            return token;
        }

        String lowered = Ascii.lower(value);
        String shortened = switch (lowered.length()) {
            case 6, 8 -> pairsRepeat(lowered) ? halve(lowered) : lowered;
            default -> lowered;
        };

        return shortened.equals(value) ? token : new HashToken(shortened, token.id(), token.span());
    }

    /**
     * Lowercases the names CSS matches case-insensitively: property names and at-keywords.
     *
     * <p>Not values, and not custom properties. {@code font-family: Arial} names something a
     * font-matching algorithm looks up, and {@code --Brand} is a different property from
     * {@code --brand}.
     *
     * @return the transform
     */
    public static NodeTransform<Node> lowercaseNames() {
        return NodeTransform.of(Set.<Class<? extends Node>> of(Declaration.class,
                                                               AtRule.class,
                                                               ConditionalGroupRule.class),
                                Optimizations::lowercaseName);
    }

    private static Node lowercaseName(Node node) {
        return switch (node) {
            case Declaration declaration -> {
                String property = Ascii.lower(declaration.property());
                yield declaration.isCustomProperty()
                      || property.equals(declaration.property()) ? declaration
                                                                 : new Declaration(property,
                                                                                   declaration.value(),
                                                                                   declaration.important(),
                                                                                   declaration.span());
            }

            case AtRule rule -> {
                String name = Ascii.lower(rule.name());
                yield name.equals(rule.name()) ? rule : new AtRule(name, rule.prelude(), rule.block(), rule.span());
            }

            case ConditionalGroupRule rule -> {
                String name = Ascii.lower(rule.name());
                yield name.equals(rule.name()) ? rule
                                               : new ConditionalGroupRule(name,
                                                                          rule.prelude(),
                                                                          rule.body(),
                                                                          rule.span());
            }

            default -> node;
        };
    }

    // -----------------------------------------------------------------------
    // Number text
    // -----------------------------------------------------------------------

    /**
     * Works on the raw text rather than the {@code double}, because the raw text is what
     * round-trips.
     */
    private static String compact(String raw, boolean hasExponent) {
        if (hasExponent || raw.isEmpty()) {
            return raw;
        }

        boolean negative = raw.charAt(0) == '-';
        String unsigned = negative || raw.charAt(0) == '+' ? raw.substring(1) : raw;

        int dot = unsigned.indexOf('.');
        String whole = dot < 0 ? unsigned : unsigned.substring(0, dot);
        String fraction = dot < 0 ? "" : unsigned.substring(dot + 1);

        int end = fraction.length();
        while (end > 0 && fraction.charAt(end - 1) == '0') {
            end--;
        }

        fraction = fraction.substring(0, end);

        int start = 0;
        while (start < whole.length() && whole.charAt(start) == '0') {
            start++;
        }

        whole = whole.substring(start);

        String compacted = whole + (fraction.isEmpty() ? "" : "." + fraction);
        if (compacted.isEmpty() || ".".equals(compacted)) {
            compacted = "0";
        }

        return negative ? "-" + compacted : compacted;
    }

    // -----------------------------------------------------------------------
    // Hex colors
    // -----------------------------------------------------------------------

    private static boolean isHex(String value) {
        if (value.isEmpty()) {
            return false;
        }

        for (int index = 0; index < value.length(); index++) {
            if (!CodePoints.isHexDigit(value.charAt(index))) {
                return false;
            }
        }

        return true;
    }

    private static boolean pairsRepeat(String value) {
        for (int index = 0; index < value.length(); index += 2) {
            if (value.charAt(index) != value.charAt(index + 1)) {
                return false;
            }
        }

        return true;
    }

    private static String halve(String value) {
        StringBuilder halved = new StringBuilder(value.length() / 2);
        for (int index = 0; index < value.length(); index += 2) {
            halved.append(value.charAt(index));
        }

        return halved.toString();
    }

    private Optimizations() {
        // static-only
    }

}
