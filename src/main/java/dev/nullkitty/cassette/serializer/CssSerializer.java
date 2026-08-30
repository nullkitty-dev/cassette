package dev.nullkitty.cassette.serializer;

import java.util.Objects;
import java.util.function.Consumer;

import dev.nullkitty.cassette.ast.Node;
import dev.nullkitty.cassette.ast.SourceSpan;
import dev.nullkitty.cassette.ast.Stylesheet;
import dev.nullkitty.cassette.diagnostics.Diagnostic;
import dev.nullkitty.cassette.diagnostics.SourceResolver;

/**
 * The other end of the library: tree in, CSS text out.
 *
 * <pre>{@code
 * ParseResult result = CssParser.parse(bytes);
 * String css = CssSerializer.serialize(result.ast(), SerializerOptions.builder()
 *         .nesting(NestingMode.FLATTEN)
 *         .formatting(Formatting.MINIFIED)
 *         .build());
 * }</pre>
 *
 * <p>Static and stateless, like {@code CssParser}. The options are an immutable value, so there is
 * nothing to configure on an instance and nothing to make thread-safe.
 *
 * <p>Serialization is a reformatter, not a reproducer. Comments and nesting survive, but
 * whitespace, quote characters, escape style and whether an attribute value was quoted are decided
 * here rather than remembered from the source. The guarantee is idempotence, not identity:
 * {@code serialize(parse(x))} is a fixed point.
 *
 * <p>Nothing here optimizes. {@link Formatting#MINIFIED} only removes whitespace and comments.
 * Changing what a stylesheet means, even harmlessly, is opt-in and lives in {@link Optimizer}.
 */
public final class CssSerializer {

    /**
     * Serializes a stylesheet with the default options: nesting preserved, pretty-printed,
     * literal Unicode.
     *
     * @param stylesheet the tree to write
     * @return the CSS text
     */
    public static String serialize(Stylesheet stylesheet) {
        return serialize(stylesheet, SerializerOptions.DEFAULTS);
    }

    /**
     * Serializes a stylesheet.
     *
     * <p>{@link NestingMode#FLATTEN} rewrites the tree before writing it; the tree passed in
     * is not modified, since every node is immutable.
     *
     * @param stylesheet the tree to write
     * @param options    how to write it
     * @return the CSS text
     */
    public static String serialize(Stylesheet stylesheet, //
                                   SerializerOptions options) {
        return serialize(stylesheet, options, Diagnostic.DISCARD);
    }

    /**
     * Serializes a stylesheet, reporting anything the writer could not spell.
     *
     * <p>Writing is not lossless on every tree that can be built. A value with no spelling that
     * reads back as itself is dropped rather than written wrong: the wreckage tokens a recovered
     * parse leaves behind, and a {@code url()} function whose arguments cannot be a url-token. The
     * parse has already reported the first kind. The second is the serializer's own limitation, and
     * is what this overload surfaces.
     *
     * <p>Diagnostics are pushed as they are found, so {@code diagnostics} may be called any
     * number of times, including none. A {@link java.util.List#add(Object) List::add} on a
     * fresh list is the expected argument.
     *
     * @param stylesheet  the tree to write
     * @param options     how to write it
     * @param diagnostics where to report what could not be written
     * @return the CSS text
     */
    public static String serialize(Stylesheet stylesheet, //
                                   SerializerOptions options,
                                   Consumer<Diagnostic> diagnostics) {
        Objects.requireNonNull(stylesheet, "stylesheet");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(diagnostics, "diagnostics");

        Stylesheet target =
            options.nesting() == NestingMode.FLATTEN ? Flattener.flatten(stylesheet, options.nestingExpansion())
                                                     : stylesheet;

        return new CssWriter(options, diagnostics).write(target);
    }

    /**
     * Serializes a stylesheet and records where in the output each construct came from.
     *
     * <p>The CSS is byte for byte what {@link #serialize(Stylesheet, SerializerOptions)} returns
     * for the same tree and options. A development build emits a map and a production build does
     * not, so both have to ship the same stylesheet.
     *
     * <p>Mappings are recorded for each alternative of a rule's prelude, each declaration and each
     * at-rule, which is the granularity browser devtools consume for CSS, and for nothing finer. A
     * construct whose span resolves to no single source is left out rather than guessed at. That is
     * ordinary for a bundle, where a wrapper synthesized around a nested import covers more than one
     * file and came from none of them.
     *
     * <p>{@code sources} is what turns a span into a file and an offset. For a tree from one parse
     * that is {@code SourceResolver.of(name, text)}, and for a tree from {@code Bundler.bundle} it
     * is {@code BundleResult.sourceIndex()}. Nothing else about the call changes.
     *
     * @param stylesheet the tree to write
     * @param options    how to write it
     * @param sources    where the tree's spans came from
     * @return the CSS and its map
     * @throws NullPointerException if any argument is {@code null}
     */
    public static SerializeResult serializeWithMap(Stylesheet stylesheet,
                                                   SerializerOptions options,
                                                   SourceResolver sources) {
        return serializeWithMap(stylesheet, options, sources, Diagnostic.DISCARD);
    }

    /**
     * Serializes a stylesheet with a map, reporting anything the writer could not spell.
     *
     * @param stylesheet  the tree to write
     * @param options     how to write it
     * @param sources     where the tree's spans came from
     * @param diagnostics where to report what could not be written
     * @return the CSS and its map
     * @throws NullPointerException if any argument is {@code null}
     */
    public static SerializeResult serializeWithMap(Stylesheet stylesheet,
                                                   SerializerOptions options,
                                                   SourceResolver sources,
                                                   Consumer<Diagnostic> diagnostics) {
        Objects.requireNonNull(stylesheet, "stylesheet");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(sources, "sources");
        Objects.requireNonNull(diagnostics, "diagnostics");

        Stylesheet target =
            options.nesting() == NestingMode.FLATTEN ? Flattener.flatten(stylesheet, options.nestingExpansion())
                                                     : stylesheet;

        // The tree's own span length is the source character count, and survives both
        // tree-in/tree-out stages; a hand-built tree reports nothing and takes the floor.
        Mappings mappings = new Mappings(SourceSpan.lengthOf(stylesheet.packedSpan()));
        String css = new CssWriter(options, diagnostics, mappings).write(target);

        return new SerializeResult(css, MapPass.generate(css, mappings, sources));
    }

    /**
     * Serializes any node on its own: a rule, a declaration, a selector, a single component
     * value.
     *
     * <p>Useful for diagnostics and tooling that wants to show a fragment. Flattening is not applied
     * to a fragment, because a nested rule taken out of its stylesheet has no parent selector left
     * to be absolutized against.
     *
     * <p>A {@link Stylesheet} passed here is not a fragment, and is handed to
     * {@link #serialize(Stylesheet, SerializerOptions)} rather than written as one. The two
     * overloads therefore agree on any argument both accept, which is what makes the shared name
     * safe. Which one the compiler picks depends on the static type of the argument, and a whole
     * stylesheet held in a {@code Node} variable must not serialize differently from the same
     * stylesheet held in a {@code Stylesheet} one.
     *
     * @param node    the node to write
     * @param options how to write it
     * @return the CSS text, without a trailing newline unless {@code node} is a stylesheet
     */
    public static String serialize(Node node, SerializerOptions options) {
        return serialize(node, options, Diagnostic.DISCARD);
    }

    /**
     * Serializes any node on its own, reporting anything the writer could not spell.
     *
     * <p>The {@link Stylesheet} guard of {@link #serialize(Node, SerializerOptions)} applies
     * here for the same reason: the two overloads have to agree on an argument both accept.
     *
     * @param node        the node to write
     * @param options     how to write it
     * @param diagnostics where to report what could not be written
     * @return the CSS text, without a trailing newline unless {@code node} is a stylesheet
     */
    public static String serialize(Node node, SerializerOptions options, Consumer<Diagnostic> diagnostics) {
        Objects.requireNonNull(node, "node");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(diagnostics, "diagnostics");

        if (node instanceof Stylesheet stylesheet) {
            return serialize(stylesheet, options, diagnostics);
        }

        return new CssWriter(options, diagnostics).writeFragment(node);
    }

    private CssSerializer() {
        // static-only
    }
}
