package dev.nullkitty.cassette.bundle;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import dev.nullkitty.cassette.ast.AtRule;
import dev.nullkitty.cassette.ast.Comment;
import dev.nullkitty.cassette.ast.ComponentValue;
import dev.nullkitty.cassette.ast.Node;
import dev.nullkitty.cassette.ast.Rule;
import dev.nullkitty.cassette.ast.SourceSpan;
import dev.nullkitty.cassette.ast.Stylesheet;
import dev.nullkitty.cassette.diagnostics.Diagnostic;
import dev.nullkitty.cassette.parser.CssParser;
import dev.nullkitty.cassette.parser.DecodedSource;
import dev.nullkitty.cassette.parser.ParseResult;
import dev.nullkitty.cassette.text.Ascii;

/**
 * Bundles several stylesheets into one, in cascade order, with {@code @import} resolved.
 *
 * <pre>{@code
 * BundleResult bundled = Bundler.bundle(
 *         List.of(new Source("app.css", Files.readAllBytes(app))),
 *         BundleOptions.builder().importer(files).build());
 * String css = CssSerializer.serialize(bundled.ast(), options);
 * }</pre>
 *
 * <p>Concatenates and inlines, and optimizes nothing. A source imported twice is inlined twice,
 * because the cascade depends on where each copy sits. Without an {@linkplain Importer importer}
 * every {@code @import} is left in place, which makes bundling pure concatenation.
 *
 * <p>Each source is decoded and parsed at the offset it occupies in one coordinate space, so every
 * span in the result is already global and no tree is ever rebased. {@link SourceIndex} maps a span
 * back to the source it came from.
 *
 * <p>A resolved import is replaced by the imported contents, wrapped in whatever group rules the
 * prelude implied; see {@link ImportPrelude}. A declined one stays in the output verbatim, so the
 * CSS still resolves it at runtime. A cycle is cut, and depth is bounded.
 *
 * <p>Two at-rules end up in positions CSS calls invalid, and concatenation corrects both. Every
 * {@code @charset} is dropped, since the text is already decoded by the time a tree exists. Every
 * surviving {@code @import} is hoisted to the front, in first-seen order, because only
 * {@code @charset} and {@code @layer} may precede one.
 *
 * <p>Everything above except the dropped {@code @charset} is reported as a diagnostic.
 *
 * @see <a href="https://www.w3.org/TR/css-syntax-3/#charset-rule">CSS Syntax Level 3 §9.3 The
 *      &#64;charset Rule</a>
 * @see <a href="https://www.w3.org/TR/css-cascade-5/#at-import">CSS Cascading and Inheritance Level 5 §2
 *      Importing Style Sheets: the &#64;import rule</a>
 */
public final class Bundler {

    /**
     * Bundles sources with the default options, which resolve no imports.
     *
     * @param sources the stylesheets, in cascade order, later ones win a tie
     * @return the assembled tree, the diagnostics, and the segment table
     * @throws NullPointerException if {@code sources} or any source is null
     */
    public static BundleResult bundle(List<Source> sources) {
        return bundle(sources, BundleOptions.DEFAULTS);
    }

    /**
     * Bundles one source, which is a parse with imports resolved and the prologue normalized.
     *
     * @param source  the stylesheet
     * @param options what to do while bundling
     * @return the assembled tree, the diagnostics, and the segment table
     * @throws NullPointerException if either argument is null
     */
    public static BundleResult bundle(Source source, //
                                      BundleOptions options) {
        return bundle(List.of(source), options);
    }

    /**
     * Bundles sources.
     *
     * @param sources the stylesheets, in cascade order, later ones win a tie
     * @param options what to do while bundling
     * @return the assembled tree, the diagnostics, and the segment table
     * @throws NullPointerException if any argument or source is null
     */
    public static BundleResult bundle(List<Source> sources, //
                                      BundleOptions options) {
        Objects.requireNonNull(sources, "sources");
        Objects.requireNonNull(options, "options");

        return new Assembly(options).run(sources);
    }

    /**
     * One bundling run, holding the state that import resolution carries through its recursion.
     */
    private static final class Assembly {

        private final BundleOptions options;

        private final List<Diagnostic> diagnostics = new ArrayList<>();

        private final SourceIndex.Builder layout = SourceIndex.builder();

        /**
         * Resolved ids currently being resolved, innermost last; the cycle check.
         */
        private final List<String> active = new ArrayList<>();

        private final List<AtRule> hoisted = new ArrayList<>();

        private boolean depthReported;

        private int rulesSoFar;

        Assembly(BundleOptions options) {
            this.options = options;
        }

        BundleResult run(List<Source> sources) {
            List<List<Node>> perSource = new ArrayList<>(sources.size());
            List<String> ids = new ArrayList<>(sources.size());
            List<Integer> bases = new ArrayList<>(sources.size());

            for (Source source : sources) {
                Objects.requireNonNull(source, "source");

                bases.add(this.layout.nextBase());
                ids.add(source.id());

                this.active.add(source.id());

                perSource.add(read(source, null, null, 0, List.of()));

                this.active.remove(this.active.size() - 1);
            }

            SourceIndex index = this.layout.build();

            return new BundleResult(assemble(ids, bases, perSource, index), this.diagnostics, index);
        }

        /**
         * Decodes, parses and resolves one source, returning what it contributes.
         *
         * @param source            what to read
         * @param inheritedEncoding the importing sheet's encoding, or {@code null} at the top
         * @param importedFrom      where the {@code @import} that pulled it in sits, or
         *                          {@code null} for a source the caller named
         * @param depth             how many imports deep this is
         */
        private List<Node> read(Source source, //
                                Charset inheritedEncoding,
                                Origin importedFrom,
                                int depth,
                                List<ImportPrelude> enclosing) {
            int base = this.layout.nextBase();

            // An importer that set an encoding knows something cassette does not (an HTTP
            // Content-Type parameter, say), so it outranks inheritance, which is the fallback
            // for when it says nothing. A BOM and an @charset outrank both, inside decode.
            Charset environment = source.protocolEncoding() != null ? source.protocolEncoding() : inheritedEncoding;

            // Decoded and parsed as two steps, so that the text handed to the index is the one
            // every span indexes into. The sink matters here for the same reason it does in a
            // CLI: an @charset naming an encoding nothing can resolve is reported by the decode
            // and by nothing afterwards; parse(DecodedSource) deliberately does not repeat it.
            //
            // Keeping the decoded source rather than its text is worth two bytes per source
            // character: parsing a String copies it into a fresh buffer, and decode already
            // built one. It also answers encoding() from what the decode settled on, instead of
            // sniffing the bytes for a BOM and an @charset a second time.
            DecodedSource decoded = CssParser.decodeSource(source.content(), environment, base, this.diagnostics::add);

            Charset encoding = decoded.encoding();
            ParseResult parsed = CssParser.parse(decoded);

            this.diagnostics.addAll(parsed.diagnostics());
            this.layout.add(source.id(), decoded.text(), importedFrom);

            List<Node> kept = new ArrayList<>(parsed.ast().children().size());

            for (Node child : parsed.ast().children()) {
                if (isCharset(child)) {
                    continue;
                }

                if (child instanceof AtRule rule && isImport(rule)) {
                    // Counted from what it produced, not as one: an import that resolved
                    // contributes its whole sheet, and a later hoist has moved past all of it.
                    int before = kept.size();

                    kept.addAll(resolve(rule, source, base, encoding, depth, enclosing));
                    count(kept.subList(before, kept.size()));

                    continue;
                }

                kept.add(child);
                count(List.of(child));
            }

            return kept;
        }

        /**
         * Resolves one {@code @import}.
         *
         * @return what replaces it: the imported contents wrapped, or nothing when the rule was
         *         dropped or hoisted out
         */
        private List<Node> resolve(AtRule rule, //
                                   Source from,
                                   int base,
                                   Charset encoding,
                                   int depth,
                                   List<ImportPrelude> enclosing) {
            ImportPrelude prelude = ImportPrelude.of(rule.prelude());

            if (prelude == null || prelude.hasSecondSpecifier()) {
                this.diagnostics.add(Diagnostic.error("@import names no single stylesheet, so it is not a valid @import",
                                                      rule.span()));
                return List.of();
            }

            if (!this.options.resolvesImports()) {
                return strand(rule, enclosing, prelude);
            }

            if (depth >= this.options.maxImportDepth()) {
                reportDepth(rule);
                return List.of();
            }

            Origin origin = new Origin(from.id(), rule.span().start() - base);
            Optional<Source> imported = this.options.resolve(prelude.specifier(), origin);

            if (imported.isEmpty()) {
                this.diagnostics.add(Diagnostic.warning("@import \""
                                                        + prelude.specifier()
                                                        + "\" was not resolved, so it is left in the output",
                                                        rule.span()));
                return strand(rule, enclosing, prelude);
            }

            Source target = imported.get();

            if (this.active.contains(target.id())) {
                this.diagnostics.add(Diagnostic.error("@import cycle: " + String.join(" -> ", chain(target.id())),
                                                      rule.span()));
                return List.of();
            }

            this.active.add(target.id());

            int importedBase = this.layout.nextBase();
            List<Node> contents = read(target, //
                                       encoding,
                                       origin,
                                       depth + 1,
                                       prelude.wraps() ? append(enclosing, prelude) : enclosing);

            this.active.remove(this.active.size() - 1);

            return prelude.wrap(contents, new SourceSpan(importedBase, this.layout.nextBase() - importedBase));
        }

        /**
         * How many rules a later hoist would have to move past.
         */
        private void count(List<Node> added) {
            for (Node node : added) {
                if (node instanceof Rule) {
                    this.rulesSoFar++;
                }
            }
        }

        /**
         * The chain as a reader can act on it: everything active, then the repeat.
         */
        private List<String> chain(String repeated) {
            List<String> chain = new ArrayList<>(this.active);
            chain.add(repeated);

            return chain;
        }

        /**
         * Reported once per bundle rather than once per level.
         */
        private void reportDepth(AtRule rule) {
            if (this.depthReported) {
                return;
            }

            this.depthReported = true;
            this.diagnostics.add(Diagnostic.error("@import nested deeper than "
                                                  + this.options.maxImportDepth()
                                                  + " levels; this chain was cut here, and any "
                                                  + "others that hit the bound are not reported",
                                                  rule.span()));
        }

        /**
         * Decides what to do with an {@code @import} that is staying in the output.
         *
         * <p>Only {@code @charset} and {@code @layer} may precede an {@code @import}, so
         * concatenation has left it somewhere invalid and it belongs at the front. The top of the
         * bundle is outside any wrapper this sheet was imported into, so moving the rule there
         * alone drops that condition and changes what the stylesheet means.
         *
         * <p>The conditions therefore travel with it. {@link ImportPrelude#reattached} rebuilds
         * the prelude out of the wrappers, exactly rather than approximately, because the
         * {@code @import} grammar is isomorphic to the wrapping. Two conditions of one kind, or an
         * anonymous layer no prelude can name, cannot be rebuilt; the rule then stays where it is
         * and the warning says the output is invalid there.
         *
         * @param enclosing the wrapping preludes, outermost first
         * @param own       this rule's own prelude, which joins them and is refused for the same
         *                  reason two wrappers are
         * @return what replaces the rule: nothing when hoisted, itself when left in place
         * @see <a href="https://www.w3.org/TR/css-cascade-5/#at-import">CSS Cascading and Inheritance
         *      Level 5 §2 Importing Style Sheets: the &#64;import rule</a>
         */
        private List<Node> strand(AtRule rule, List<ImportPrelude> enclosing, ImportPrelude own) {
            if (enclosing.isEmpty()) {
                hoist(rule);
                return List.of();
            }

            List<ComponentValue> prelude = ImportPrelude.reattached(rule, append(enclosing, own));

            if (prelude == null) {
                this.diagnostics.add(Diagnostic.warning("@import is inside a conditional import "
                                                        + "whose condition cannot be moved onto an @import prelude, so hoisting "
                                                        + "it would change what this stylesheet means; it is left where it is, "
                                                        + "and an @import is not valid there",
                                                        rule.span()));
                return List.of(rule);
            }

            hoist(new AtRule(rule.name(), prelude, rule.block(), rule.packedSpan()));

            return List.of();
        }

        /**
         * The chain with one more prelude on the end, which is the only way it grows.
         */
        private static List<ImportPrelude> append(List<ImportPrelude> chain, ImportPrelude last) {
            List<ImportPrelude> longer = new ArrayList<>(chain.size() + 1);
            longer.addAll(chain);
            longer.add(last);

            return List.copyOf(longer);
        }

        /**
         * Moves an {@code @import} to the front, saying what it jumped.
         */
        private void hoist(AtRule rule) {
            this.hoisted.add(rule);

            if (this.rulesSoFar == 0) {
                return;
            }

            this.diagnostics.add(Diagnostic.warning("@import moved to the top of the bundle, past "
                                                    + this.rulesSoFar
                                                    + (this.rulesSoFar == 1 ? " rule" : " rules")
                                                    + "; an @import may only be preceded by @charset and @layer",
                                                    rule.span()));
        }

        /**
         * Lays the children out: hoisted imports, then each source's contents behind its banner.
         *
         * <p>A hoisted import is no longer in any source's run, so it goes in front of every
         * banner rather than behind the one naming the source it came from.
         */
        private Stylesheet assemble(List<String> ids,
                                    List<Integer> bases,
                                    List<List<Node>> perSource,
                                    SourceIndex index) {
            List<Node> children = new ArrayList<>(this.hoisted);

            for (int at = 0; at < perSource.size(); at++) {
                List<Node> kept = perSource.get(at);

                if (this.options.banners() && !kept.isEmpty()) {
                    children.add(banner(ids.get(at), bases.get(at)));
                }

                children.addAll(kept);
            }

            return new Stylesheet(children, new SourceSpan(0, index.length()));
        }

        /**
         * A comment naming a source, at the start of the run it introduces.
         *
         * <p>Zero-width at the segment's base rather than {@code SourceSpan.NONE}: it is a
         * synthesized node with no text of its own, but it has a real position, and one at the base
         * resolves through {@link SourceIndex} to the source it names instead of to whatever sits
         * at offset zero.
         *
         * <p>The id is caller-supplied text, and a banner terminating its own comment would turn
         * everything after it into garbage. A <code>&#42;&#47;</code> in it is therefore broken
         * with a backslash, which means nothing inside a CSS comment and reads back unchanged.
         */
        private static Comment banner(String sourceId, int base) {
            return new Comment(" " + sourceId.replace("*/", "*\\/") + " ", true, new SourceSpan(base, 0));
        }

        private static boolean isCharset(Node node) {
            return node instanceof AtRule rule && Ascii.equalsIgnoreCase(rule.name(), "charset");
        }

        private static boolean isImport(AtRule rule) {
            return Ascii.equalsIgnoreCase(rule.name(), "import");
        }
    }

    private Bundler() {
        // utility class
    }
}
