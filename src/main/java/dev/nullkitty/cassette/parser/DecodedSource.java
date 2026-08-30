package dev.nullkitty.cassette.parser;

import java.nio.charset.Charset;

import dev.nullkitty.cassette.lexer.CssEncoding;
import dev.nullkitty.cassette.lexer.SourceText;

/**
 * A stylesheet that has been decoded but not yet parsed.
 *
 * <p>For a caller that needs both the tree and the text every span indexes into, such as a bundler
 * or a source-map generator. Decoding to a {@code String} and handing that string back to
 * {@link CssParser#parse(CharSequence, int)} works and is what
 * {@link CssParser#decode(byte[], Charset, int, java.util.function.Consumer) decode} is for, but it
 * copies the text a second time: the decode already built a buffer, turning it into a string
 * abandons that buffer, and parsing the string builds another. Keeping this skips the second copy.
 *
 * <p>That is two bytes per source character, which {@code BundleBenchmark} measured as most of a
 * 12% gap between bundling and parsing the same sources one at a time. The remaining byte per
 * character is {@link #text()}, which a {@code bundle.SourceIndex} retains for the life of the
 * bundle, and a Latin-1 string retains one byte per character where the buffer underneath costs
 * two. The text is materialized on demand, so a caller that only wants a tree never pays for it.
 *
 * <p>Instances come from {@link CssParser#decodeSource}, which is also where the charset
 * diagnostic is reported. {@link CssParser#parse(DecodedSource)} does <em>not</em> report it
 * again, because a {@code DecodedSource} has been past a sink by construction.
 */
public final class DecodedSource {

    private final SourceText source;
    private final int        base;

    /**
     * Materialized by {@link #text()} and cached.
     *
     * <p>Not volatile. Racing threads at worst decode the same buffer twice and publish equal
     * strings, and a string is immutable and safely published by its own final fields, so either
     * value a reader sees is correct. This is the trade {@code String.hash} makes.
     */
    private String text;

    DecodedSource(SourceText source, //
                  int base) {
        this.source = source;
        this.base = base;
    }

    /**
     * The decoded, preprocessed text: CRLF collapsed, NULL replaced, byte order mark stripped.
     *
     * <p>This source's text alone, not the coordinate space it sits in: at a non-zero
     * {@link #base()} a span's offsets do not index it. Built on first call and kept.
     *
     * @return the text this source contributes
     */
    public String text() {
        String materialized = this.text;
        if (materialized == null) {
            materialized = this.source.toString();
            this.text = materialized;
        }

        return materialized;
    }

    /**
     * How many characters the text holds, without materializing it.
     *
     * <p>Characters after preprocessing and never bytes, which is what the next source's base is
     * computed from, a multi-byte encoding makes the two differ, and CRLF collapsing makes them
     * differ even in ASCII.
     *
     * @return the decoded length
     */
    public int length() {
        return this.source.length();
    }

    /**
     * The offset this source starts at in the coordinate space it was decoded into.
     *
     * @return the base, zero for a source decoded on its own
     */
    public int base() {
        return this.base;
    }

    /**
     * What it was decoded as, for passing down to a sheet this one imports.
     *
     * <p>The same answer {@link CssParser#detectEncoding} gives for the same bytes, without the
     * second pass over them to work it out again.
     *
     * @return the encoding, or {@code null} for the two that are not a {@link Charset}, the
     *         replacement encoding and {@code x-user-defined}, neither of which anything can
     *         inherit
     */
    public Charset encoding() {
        CssEncoding encoding = this.source.encoding();
        return encoding.kind() == CssEncoding.Kind.CHARSET ? encoding.charset() : null;
    }

    @Override
    public String toString() {
        return "DecodedSource[" + this.source.length() + " chars at " + this.base + "]";
    }

    /**
     * The buffer to tokenize.
     */
    SourceText source() {
        return this.source;
    }
}
