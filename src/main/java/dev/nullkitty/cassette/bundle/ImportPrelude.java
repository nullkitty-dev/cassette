package dev.nullkitty.cassette.bundle;

import java.util.ArrayList;
import java.util.List;

import dev.nullkitty.cassette.ast.AtRule;
import dev.nullkitty.cassette.ast.ComponentValue;
import dev.nullkitty.cassette.ast.ConditionalGroupRule;
import dev.nullkitty.cassette.ast.FunctionValue;
import dev.nullkitty.cassette.ast.IdentToken;
import dev.nullkitty.cassette.ast.Node;
import dev.nullkitty.cassette.ast.Punctuation;
import dev.nullkitty.cassette.ast.SimpleBlock;
import dev.nullkitty.cassette.ast.SourceSpan;
import dev.nullkitty.cassette.ast.StringToken;
import dev.nullkitty.cassette.ast.UrlToken;
import dev.nullkitty.cassette.ast.WhitespaceToken;
import dev.nullkitty.cassette.text.Ascii;

/**
 * An {@code @import} prelude, split into the parts that decide what wraps the imported sheet.
 *
 * <p>The grammar is
 * {@code <url> [layer | layer(<name>)]? [supports(<condition>)]? <media-query-list>?}, and every
 * part of it has an exact group-rule equivalent:
 *
 * <table border="1">
 * <caption>prelude to wrapping</caption>
 * <tr><th>prelude</th><th>wrapping, outermost first</th></tr>
 * <tr><td>{@code url(b.css)}</td><td>none, contents spliced in directly</td></tr>
 * <tr><td>{@code url(b.css) screen}</td><td>{@code @media screen}</td></tr>
 * <tr><td>{@code url(b.css) supports(display:grid)}</td><td>{@code @supports (display:grid)}</td></tr>
 * <tr><td>{@code url(b.css) layer(base)}</td><td>{@code @layer base}</td></tr>
 * <tr><td>{@code url(b.css) layer}</td><td>{@code @layer} (anonymous)</td></tr>
 * </table>
 *
 * <p>Layer outermost, then supports, then media. Layer assignment applies to the contents whether
 * or not the conditions match, and the conditions apply within the layer.
 *
 * <p>Wrapping re-emits the prelude's own token list. Nothing here evaluates a media query or a
 * supports condition. cassette does not decide whether a condition is true, only rebuilds the rule
 * that asks.
 *
 * @see <a href="https://www.w3.org/TR/css-cascade-5/#at-import">CSS Cascading and Inheritance Level 5 §2
 *      Importing Style Sheets: the &#64;import rule</a>
 */
final class ImportPrelude {

    private final String               specifier;
    private final List<ComponentValue> layer;
    private final boolean              hasLayer;
    private final List<ComponentValue> supports;
    private final List<ComponentValue> media;

    /**
     * The {@code layer} or {@code layer(...)} value exactly as the prelude wrote it, or null.
     *
     * <p>Kept beside {@link #layer}, which holds the arguments for building the {@code @layer}
     * wrapper. This holds the whole value, for putting a hoisted import's prelude back
     * together.
     */
    private final ComponentValue layerValue;

    /**
     * The {@code supports(...)} function exactly as the prelude wrote it, or null.
     */
    private final ComponentValue supportsValue;

    private ImportPrelude(String specifier,
                          List<ComponentValue> layer,
                          boolean hasLayer,
                          List<ComponentValue> supports,
                          List<ComponentValue> media,
                          ComponentValue layerValue,
                          ComponentValue supportsValue) {
        this.specifier = specifier;
        this.layer = layer;
        this.hasLayer = hasLayer;
        this.supports = supports;
        this.media = media;
        this.layerValue = layerValue;
        this.supportsValue = supportsValue;
    }

    /**
     * Splits a prelude.
     *
     * @param prelude the {@code @import}'s prelude
     * @return the parts, or {@code null} when there is no single specifier, which means this
     *         was never a valid {@code @import} and the caller should say so
     */
    static ImportPrelude of(List<ComponentValue> prelude) {
        String specifier = null;
        List<ComponentValue> layer = new ArrayList<>();
        boolean hasLayer = false;
        List<ComponentValue> supports = new ArrayList<>();
        List<ComponentValue> media = new ArrayList<>();
        ComponentValue layerValue = null;
        ComponentValue supportsValue = null;

        for (ComponentValue value : prelude) {
            if (value.isTrivia()) {
                if (!media.isEmpty()) {
                    media.add(value);
                }

                continue;
            }

            if (specifier == null) {
                String named = specifierOf(value);
                if (named == null) {
                    return null;
                }

                specifier = named;
                continue;
            }

            if (media.isEmpty() && !hasLayer && isLayer(value)) {
                hasLayer = true;
                layerValue = value;
                if (value instanceof FunctionValue function) {
                    layer.addAll(function.arguments());
                }

                continue;
            }

            if (media.isEmpty()
                && supports.isEmpty()
                && value instanceof FunctionValue function
                && Ascii.equalsIgnoreCase(function.name(), "supports")) {
                supports.addAll(condition(function));
                supportsValue = value;
                continue;
            }

            media.add(value);
        }

        if (specifier == null) {
            return null;
        }

        return new ImportPrelude(specifier,
                                 List.copyOf(layer),
                                 hasLayer,
                                 List.copyOf(supports),
                                 List.copyOf(trimTrailingTrivia(media)),
                                 layerValue,
                                 supportsValue);
    }

    /**
     * Whether the media query list starts with something that cannot begin one, which means the
     * rule was malformed.
     *
     * @return whether a bare string or url follows the specifier
     */
    boolean hasSecondSpecifier() {
        return !this.media.isEmpty() && specifierOf(this.media.get(0)) != null;
    }

    /**
     * The url or string the import names, decoded.
     */
    String specifier() {
        return this.specifier;
    }

    /**
     * Whether this import puts a group rule around what it pulls in.
     *
     * <p>What it decides is what happens to an unresolved {@code @import} found <em>inside</em>
     * the imported sheet: hoisting it to the top of the bundle would carry it out of this
     * wrapper and drop the condition, so a bare import may be hoisted and a wrapping one may
     * not.
     *
     * @return whether any of layer, supports or media is present
     */
    boolean wraps() {
        return this.hasLayer || !this.supports.isEmpty() || !this.media.isEmpty();
    }

    /**
     * Wraps imported contents in the group rules this prelude implies.
     *
     * <p>The wrapper carries the span of the content it wraps, not of the {@code @import} that
     * caused it. Taking the union of the two would produce a node overlapping its own siblings,
     * since everything else in the importing sheet sits between them, and the causal link is
     * not lost, because {@link SourceIndex.Segment#importedFrom()} records which import pulled
     * each source in.
     *
     * <p>A wrapper is the one node assembled from two files, so two invariants do not hold for
     * it:
     *
     * <ul>
     *   <li><b>Its prelude lies outside its own span.</b> The prelude is re-emitted from the
     *       importing sheet's tokens and keeps their spans, since that is where the author wrote
     *       the media query and where a diagnostic about it should point. Parent-contains-children
     *       therefore holds for a wrapper's body but not for its prelude.</li>
     *   <li><b>Its span may cover several sources.</b> An imported sheet that imported others
     *       occupies the space its whole subtree does, so {@code SourceIndex.resolve} refuses such
     *       a span rather than picking one source. {@code importedFrom} is the question that has
     *       an answer for it.</li>
     * </ul>
     *
     * @param contents what the imported sheet parsed to
     * @param span     the extent of the imported source and everything it imported
     * @return the contents, wrapped
     */
    List<Node> wrap(List<Node> contents, SourceSpan span) {
        List<Node> wrapped = contents;
        if (!this.media.isEmpty()) {
            wrapped = List.of(new ConditionalGroupRule("media", this.media, wrapped, span));
        }

        if (!this.supports.isEmpty()) {
            wrapped = List.of(new ConditionalGroupRule("supports", this.supports, wrapped, span));
        }

        if (this.hasLayer) {
            wrapped = List.of(new ConditionalGroupRule("layer", this.layer, wrapped, span));
        }

        return wrapped;
    }

    /**
     * A prelude for an {@code @import} being hoisted out of the wrappers enclosing it.
     *
     * <p>The inverse of {@link #wrap}. The wrapping turned a prelude into group rules, and hoisting
     * has to turn them back into a prelude, because the top of the bundle is outside them and an
     * import that only moved there would stop being conditional. The {@code @import} grammar is
     * isomorphic to the wrapping, which is what makes the exact cases exact: each wrapper kind maps
     * to one slot of {@code url [layer] [supports()] [media]}. The slots are written in
     * <em>grammar</em> order rather than nesting order, since a {@code @media} outside a
     * {@code @layer} is reachable and would otherwise come out backwards.
     *
     * <p>Two shapes are refused, both because hoisting them would change what the stylesheet
     * means. The caller then leaves the rule where it is and warns:
     *
     * <ul>
     *   <li><b>Two of any one kind.</b> Two media conditions would need {@code and} between them,
     *       and a comma in either would need distributing over the other; two named layers would
     *       need joining into a dotted path. Both are expressible, and neither is modelled,
     *       because a media query is opaque component values here and combining them means
     *       interpreting one.
     *   <li><b>An anonymous layer.</b> {@code layer} in an import prelude creates a <em>new</em>
     *       anonymous layer, and anonymous layers are distinct from each other, so the hoisted
     *       import would land in a different layer from the block it came out of, with nothing in
     *       the output to show it.
     * </ul>
     *
     * @param rule      the import being hoisted, for its specifier and for a span to hang
     *                  synthesized whitespace on
     * @param enclosing the wrapping preludes, outermost first, and this rule's own last
     * @return the prelude to give the hoisted rule, or {@code null} when the rule is malformed
     *         or hoisting it would change what the stylesheet means
     * @see <a href="https://www.w3.org/TR/css-cascade-5/#layering">CSS Cascading and Inheritance Level 5
     *      §6.4 Cascade Layers</a>
     */
    static List<ComponentValue> reattached(AtRule rule, List<ImportPrelude> enclosing) {
        ComponentValue layer = null;
        ComponentValue supports = null;
        List<ComponentValue> media = List.of();

        for (ImportPrelude prelude : enclosing) {
            if (prelude.hasLayer) {
                if (layer != null || prelude.isAnonymousLayer()) {
                    return null;
                }

                layer = prelude.layerValue;
            }

            if (prelude.supportsValue != null) {
                if (supports != null) {
                    return null;
                }

                supports = prelude.supportsValue;
            }

            if (!prelude.media.isEmpty()) {
                if (!media.isEmpty()) {
                    return null;
                }

                media = prelude.media;
            }
        }

        ComponentValue specifier = specifierValue(rule.prelude());
        if (specifier == null) {
            return null;
        }

        // Zero-length, at the hoisted rule's own start: the same shape the banner comments use,
        // and it resolves to the file that wrote the import rather than to whichever file wrote
        // the condition being re-attached.
        long gap = SourceSpan.pack(SourceSpan.startOf(rule.packedSpan()), 0);

        List<ComponentValue> out = new ArrayList<>();
        out.add(specifier);

        if (layer != null) {
            out.add(new WhitespaceToken(gap));
            out.add(layer);
        }

        if (supports != null) {
            out.add(new WhitespaceToken(gap));
            out.add(supports);
        }

        if (!media.isEmpty()) {
            out.add(new WhitespaceToken(gap));
            out.addAll(media);
        }

        return List.copyOf(out);
    }

    /**
     * Whether this prelude names no layer but asks for one, which nothing can re-attach.
     */
    private boolean isAnonymousLayer() {
        return this.hasLayer && this.layer.isEmpty();
    }

    /**
     * The url or string, as the token that carried it.
     */
    private static ComponentValue specifierValue(List<ComponentValue> prelude) {
        for (ComponentValue value : prelude) {
            if (!value.isTrivia()) {
                return specifierOf(value) == null ? null : value;
            }
        }

        return null;
    }

    /**
     * The condition {@code supports()} carries, as {@code @supports} would spell it.
     *
     * <p>{@code supports()} takes a condition <em>or</em> a bare declaration, while
     * {@code @supports} takes a bare condition, so {@code supports(display: grid)} becomes
     * {@code @supports (display: grid)}, with the parentheses put back, and
     * {@code supports((a) or (b))} becomes {@code @supports (a) or (b)}, with them not. The wrong
     * way round produces a condition that is always false.
     *
     * <p>A top-level colon is what tells the two apart: a declaration has one and a condition
     * cannot, since a declaration inside a condition is always already parenthesized.
     */
    private static List<ComponentValue> condition(FunctionValue supports) {
        List<ComponentValue> arguments = supports.arguments();
        boolean declaration = arguments.stream() //
                                       .anyMatch(value -> value instanceof Punctuation punctuation
                                                          && punctuation.kind() == Punctuation.Kind.COLON);
        if (!declaration) {
            return arguments;
        }

        return List.of(new SimpleBlock('(', arguments, SourceSpan.unpack(supports.packedSpan())));
    }

    private static boolean isLayer(ComponentValue value) {
        return value instanceof IdentToken ident && Ascii.equalsIgnoreCase(ident.value(), "layer")
               || value instanceof FunctionValue function && Ascii.equalsIgnoreCase(function.name(), "layer");
    }

    /**
     * The url or string a value names, or {@code null} if it names neither.
     */
    private static String specifierOf(ComponentValue value) {
        return switch (value) {
            case UrlToken url -> url.value();
            case StringToken string -> string.value();

            case FunctionValue function when Ascii.equalsIgnoreCase(function.name(), "url") -> function.arguments() //
                                                                                                       .stream() //
                                                                                                       .filter(StringToken.class::isInstance) //
                                                                                                       .map(argument -> ((StringToken) argument).value()) //
                                                                                                       .findFirst() //
                                                                                                       .orElse(null);

            default -> null;
        };
    }

    private static List<ComponentValue> trimTrailingTrivia(List<ComponentValue> values) {
        int end = values.size();
        while (end > 0 && values.get(end - 1).isTrivia()) {
            end--;
        }

        return values.subList(0, end);
    }
}
