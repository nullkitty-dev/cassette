package dev.nullkitty.cassette.fuzz;

import java.nio.charset.StandardCharsets;
import java.util.List;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ArbitrarySupplier;
import net.jqwik.api.Combinators;
import net.jqwik.api.Tuple;

/**
 * Generators for input that is <em>shaped</em> like CSS without being valid CSS.
 *
 * <p>Uniform random bytes would spend nearly all their budget on inputs the tokenizer
 * rejects in its first few states. These generators instead splice well-formed fragments
 * together with the constructs that actually carry recovery risk: unterminated strings and
 * {@code url()}s, trailing backslashes, mismatched brackets, stray BOMs mid-stream, and
 * {@code @charset} declarations that disagree with the bytes following them.
 *
 * <p>A fragment is drawn from one of three pools. {@link #WELL_FORMED} and {@link #MALFORMED}
 * are atoms; {@link #SEAMS} are not, and that list's javadoc says why a generator that only
 * concatenates atoms cannot reach what it covers.
 *
 * <p>Use via the nested suppliers:
 *
 * <pre>{@code
 * @Property
 * void recoversWithoutThrowing(@ForAll(supplier = CssLikeArbitraries.Bytes.class) byte[] input) { ... }
 * }</pre>
 *
 * <p>The properties hanging off these, "recovers without throwing", "every diagnostic span
 * lies within the input", "recovered output re-parses to itself", are in
 * {@link TokenizerPropertiesTest}, {@link ParserPropertiesTest} and
 * {@link SerializerPropertiesTest}.
 */
public final class CssLikeArbitraries {

    private static final byte[] UTF8_BOM = { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF };

    private static final List<String> WELL_FORMED =
        List.of("a{color:red}",
                ".c , #i { margin : 0 1px }",
                "@media (min-width:640px){.x{&:hover{top:0}}}",
                "@supports (display:grid){.g{display:grid}}",
                "div > p + span ~ a[href^=\"http\"]:not(.x)::before{content:\"\"}",
                ":root{--tok: 1px solid rgb(0 0 0 / 50%)}",
                "@layer base, components;",
                "@font-face{font-family:\"Caf\\e9\";src:url(a.woff2)}");

    private static final List<String> MALFORMED = List.of("a{color:}",
                                                          "a{:red}",
                                                          "@media{",
                                                          "}}}",
                                                          "a{b:c",
                                                          "/* unterminated",
                                                          "\"unterminated string",
                                                          "url(unterminated",
                                                          "url(has spaces and \" quote)",
                                                          "a{color:red;;;;}",
                                                          "\\",
                                                          "a\\",
                                                          "@",
                                                          "@;",
                                                          "a{color:red!important!important}",
                                                          ":is(:is(:is(",
                                                          "a{--x:{}}",
                                                          " {color:red}",
                                                          "@charset \"utf-16\";a{color:red}",
                                                          "﻿a{color:red}");

    /**
     * Fragments that carry a <em>seam</em>: something that writes text, then whitespace, then
     * something that writes nothing.
     *
     * <p>These are not more atoms, and the list exists for that distinction. Both this
     * generator and the differential harness concatenate fragments, so a defect needing a
     * particular token <em>sequence</em> is drawn essentially never: the last serializer defect
     * found needed {@code c}, whitespace, {@code url(} and {@code "} in that order, four picks
     * from a seventy-fragment pool, and 200,000 samples never produced it. Every serializer
     * defect either generator has found has shared this one shape, so the shape is a fragment
     * here rather than an accident of concatenation.
     *
     * <p>The three values that write nothing are a bad-string, a bad-url and a lone {@code \}
     * delimiter, joined by a {@code url()} whose first argument is not a string; the prefixes
     * written ahead of them are a separator, an indent, the space before an at-rule prelude and
     * an opening brace. The entries below cross the two.
     */
    private static final List<String> SEAMS = List.of(// A separator written before a value that turns out to write nothing.
                                                      "a{b:c url(\"",
                                                      "a{b:c \"",
                                                      "a{b:c \\",
                                                      "@a c url(\"",
                                                      "@a c \"",
                                                      "@a c \\",
                                                      "x url(\"",
                                                      // The same seams, closed, so they do not depend on the fragment drawn next.
                                                      "a{b:c url(x y)}",
                                                      "a{b:c \"x\n}",
                                                      "a{b:c \\\n}",
                                                      "@a c url(x y);",
                                                      "@a c \"x\n;",
                                                      "@a{b:c url(x y)}",
                                                      "@media print{a{b:c url(x y)}}",
                                                      // A value that writes nothing, with whitespace on both sides of it.
                                                      "a{b:c url(x y) d}",
                                                      "a{b:c \\\n d}",
                                                      // ... and one directly in front of a closer.
                                                      "a{b:f(c url(x y))}",
                                                      "a{b:f(url(x y) )}",
                                                      "a{b:[c \\\n]}",
                                                      "@a url(\"\n)",
                                                      "url(\"x\" y)",
                                                      // A dropped value in front of the marks that terminate it.
                                                      "a{b:c url(x y)!important}",
                                                      "a{b:c url(x y)/*c*/}",
                                                      "a{b:c url(x y);d:e}",
                                                      "a url(x y){top:0}");

    /**
     * Whether {@code text} carries one of the {@link #SEAMS} fragments verbatim.
     *
     * <p>Exists so a property can assert that the generator still draws them. A pool that
     * quietly stops covering a shape reports zero failures exactly as convincingly as one that
     * covers it and finds nothing.
     */
    static boolean containsSeam(String text) {
        return SEAMS.stream().anyMatch(text::contains);
    }

    /**
     * CSS-shaped byte input, the form the parser's real entry point takes.
     */
    public static Arbitrary<byte[]> cssLikeBytes() {
        return Combinators.combine(Arbitraries.of(true, false), cssLikeText()).as(CssLikeArbitraries::encode);
    }

    /**
     * The same input as text, for the {@code CharSequence} convenience entry point.
     */
    public static Arbitrary<String> cssLikeText() {
        return fragment().list().ofMinSize(1).ofMaxSize(24).map(fragments -> String.join("", fragments));
    }

    private static Arbitrary<String> fragment() {
        return Arbitraries.frequencyOf(Tuple.of(6, Arbitraries.of(WELL_FORMED)),
                                       Tuple.of(3, Arbitraries.of(MALFORMED)),
                                       Tuple.of(3, Arbitraries.of(SEAMS)),
                                       Tuple.of(1, whitespace()));
    }

    private static Arbitrary<String> whitespace() {
        return Arbitraries.of(" ", "\n", "\t", "\r\n", "\f", "  \n\n");
    }

    private static byte[] encode(boolean withBom, String text) {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        if (!withBom) {
            return body;
        }

        byte[] withPrefix = new byte[UTF8_BOM.length + body.length];
        System.arraycopy(UTF8_BOM, 0, withPrefix, 0, UTF8_BOM.length);
        System.arraycopy(body, 0, withPrefix, UTF8_BOM.length, body.length);
        return withPrefix;
    }

    /**
     * Supplies {@link #cssLikeBytes()} to {@code @ForAll(supplier = ...)}.
     */
    public static final class Bytes implements ArbitrarySupplier<byte[]> {

        @Override
        public Arbitrary<byte[]> get() {
            return cssLikeBytes();
        }
    }

    /**
     * Supplies {@link #cssLikeText()} to {@code @ForAll(supplier = ...)}.
     */
    public static final class Text implements ArbitrarySupplier<String> {

        @Override
        public Arbitrary<String> get() {
            return cssLikeText();
        }
    }

    private CssLikeArbitraries() {
        // utility class
    }
}
