package dev.nullkitty.cassette.parser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

import dev.nullkitty.cassette.ast.SourceSpan;
import dev.nullkitty.cassette.diagnostics.Diagnostic;
import dev.nullkitty.cassette.lexer.CssEncoding;
import dev.nullkitty.cassette.lexer.SourceText;
import dev.nullkitty.cassette.lexer.TokenBuffer;

/**
 * The entry point: bytes in, {@link ParseResult} out.
 *
 * <pre>{@code
 * ParseResult result = CssParser.parse(Files.readAllBytes(path));
 * for (Diagnostic diagnostic : result.diagnostics()) {
 *     System.err.println(diagnostic);
 * }
 * }</pre>
 *
 * <p>Static and stateless. There is no parser object to configure, so nothing to make thread-safe
 * and no lifecycle to get wrong. There is nothing worth amortizing across calls either, since
 * stylesheets are small and every option is an immutable record.
 *
 * <p>Bytes are the real entry point, not text. CSS Syntax's charset detection is defined over
 * bytes, sniffing a byte order mark, then an {@code @charset} rule, then the encoding the
 * transport claimed, then UTF-8, and decoding to a {@code String} first throws away exactly what
 * it needs. {@link #parse(CharSequence)} exists for already-decoded text and skips detection.
 *
 * @see <a href="https://www.w3.org/TR/css-syntax-3/#input-byte-stream">CSS Syntax Level 3 §3.2 The input
 *      byte stream</a>
 */
public final class CssParser {

    /**
     * Parses a stylesheet, detecting its encoding.
     *
     * @param source the raw stylesheet
     * @return the tree and everything the parser noticed
     */
    public static ParseResult parse(byte[] source) {
        return parse(SourceText.decode(source));
    }

    /**
     * Parses a stylesheet whose transport claimed an encoding.
     *
     * @param source           the raw stylesheet
     * @param protocolEncoding the encoding a {@code Content-Type} charset parameter or an
     *                         HTML {@code <link charset>} named, or {@code null}; a byte
     *                         order mark and an {@code @charset} rule both outrank it
     * @return the tree and everything the parser noticed
     */
    public static ParseResult parse(byte[] source, //
                                    Charset protocolEncoding) {
        return parse(SourceText.decode(source, protocolEncoding));
    }

    /**
     * Reads a stream fully, then parses it.
     *
     * <p>The stream is consumed but not closed, closing belongs to whoever opened it.
     *
     * @param in the stylesheet bytes
     * @return the tree and everything the parser noticed
     * @throws IOException if reading fails
     */
    public static ParseResult parse(InputStream in) throws IOException {
        return parse(in, null);
    }

    /**
     * Reads a stream fully, then parses it with a transport-supplied encoding.
     *
     * @param in               the stylesheet bytes
     * @param protocolEncoding the encoding the transport claimed, or {@code null}
     * @return the tree and everything the parser noticed
     * @throws IOException if reading fails
     */
    public static ParseResult parse(InputStream in, //
                                    Charset protocolEncoding) throws IOException {
        return parse(SourceText.decode(in, protocolEncoding));
    }

    /**
     * Parses already-decoded text, skipping charset detection.
     *
     * <p>Preprocessing still runs: newline normalization and NULL replacement are part of the
     * grammar, not of decoding.
     *
     * @param source the stylesheet source
     * @return the tree and everything the parser noticed
     */
    public static ParseResult parse(CharSequence source) {
        return parse(SourceText.of(source));
    }

    /**
     * Parses already-decoded text sitting at {@code base} in a larger coordinate space.
     *
     * <p>For a caller assembling several sources into one tree. Every span in the result is shifted
     * by {@code base}, so the trees of several sources laid out end to end share one coordinate
     * space and no span from one collides with a span from another. Spans are built that way rather
     * than rewritten afterwards, because rebasing a parsed tree means rebuilding every record in
     * it, some 38,000 nodes for a stylesheet the size of Bootstrap.
     *
     * <p>Bases are allocated from a running cursor over decoded lengths. A source's base is the sum
     * of the {@link #decode(byte[], Charset, int, Consumer) decoded} lengths of everything laid out
     * before it, in characters after BOM-stripping and §3.3 preprocessing, never in bytes. A
     * multi-byte encoding makes the two differ, and a base computed from the wrong one misplaces
     * every source after it.
     *
     * <p>What this costs the caller is {@link SourceSpan#text}. At a non-zero base a span's offsets
     * are not offsets into {@code source}, so slicing that text with them returns the wrong
     * characters, and silently, because the offsets are still in range. Resolving a span back to
     * the source it came from is what {@code bundle.SourceIndex} is for.
     *
     * @param source the stylesheet source
     * @param base   the offset this source starts at; zero parses it on its own
     * @return the tree and everything the parser noticed
     * @throws IllegalArgumentException if {@code base} is negative
     * @see <a href="https://www.w3.org/TR/css-syntax-3/#input-preprocessing">CSS Syntax Level 3 §3.3</a>
     */
    public static ParseResult parse(CharSequence source, //
                                    int base) {
        return parse(SourceText.of(source, base));
    }

    /**
     * Parses a source that has already been decoded, without copying its text again.
     *
     * <p>The pairing for {@link #decodeSource}, and the reason that method exists: a caller
     * needing the text as well as the tree can decode once, parse from the buffer that decode
     * already built, and take the text off the same object. Going through
     * {@link #decode(byte[], Charset, int, Consumer)} and {@link #parse(CharSequence, int)}
     * instead is correct and costs two extra bytes per source character.
     *
     * <p>Spans are based wherever the source was decoded to, so nothing here takes a base.
     *
     * <p>The charset diagnostic is not reported here. It belongs to decoding, and
     * {@link #decodeSource} took a sink and has already reported it, so repeating it would give a
     * bundle two warnings per source read in the wrong encoding. This is the one asymmetry with the
     * other {@code parse} overloads, which are handed text with no encoding left to question.
     *
     * @param source a source from {@link #decodeSource}
     * @return the tree and everything the parser noticed
     * @throws NullPointerException if {@code source} is null
     */
    public static ParseResult parse(DecodedSource source) {
        Objects.requireNonNull(source, "source");

        List<Diagnostic> diagnostics = new ArrayList<>();
        Parser parser = new Parser(TokenBuffer.tokenize(source.source()), diagnostics);

        return new ParseResult(parser.parseStylesheet(), diagnostics);
    }

    private static ParseResult parse(SourceText source) {
        List<Diagnostic> diagnostics = new ArrayList<>();

        reportCharsetFallback(source, diagnostics::add);

        Parser parser = new Parser(TokenBuffer.tokenize(source), diagnostics);

        return new ParseResult(parser.parseStylesheet(), diagnostics);
    }

    /**
     * Decodes a stylesheet without parsing it, reporting what detection had to fall back on.
     *
     * <p>For a caller that needs the text a span indexes into. A span's offsets are
     * <em>post-preprocessing character offsets</em>, into the decoded buffer, after CSS
     * Syntax §3.3 has collapsed CRLF to LF and replaced NULL, so counting lines in the
     * original bytes gives the wrong answer for any input containing a CRLF, by the number of
     * them preceding the offset. Decoding here and then calling {@link #parse(CharSequence)}
     * on the result gives one string that every span is an offset into.
     *
     * <p>That works because decoding is a fixed point of preprocessing: §3.3 leaves behind no
     * CR, FF, NULL or unpaired surrogate for the second pass inside {@code parse} to react to,
     * so it copies the text through and moves no offset. {@code CssParserTest.Decoding} pins
     * it, since it is a property of the current preprocessing rules rather than something the
     * types enforce.
     *
     * <p>Pass the diagnostic sink. An {@code @charset} naming an encoding nothing can resolve is
     * reported here, where the fallback happens, and {@link #parse(CharSequence)} cannot report it
     * afterwards, having been handed text with no encoding left to question. Discarding it loses
     * the one diagnostic that says a stylesheet may have been read in the wrong encoding.
     *
     * @param source           the raw stylesheet
     * @param protocolEncoding the encoding the transport claimed, or {@code null}; a byte order
     *                         mark and an {@code @charset} rule both outrank it
     * @param diagnostics      where to report a charset that could not be honoured
     * @return the decoded, preprocessed text
     * @see <a href="https://www.w3.org/TR/css-syntax-3/#input-preprocessing">CSS Syntax Level 3 §3.3</a>
     */
    public static String decode(byte[] source, //
                                Charset protocolEncoding,
                                Consumer<Diagnostic> diagnostics) {
        return decode(source, protocolEncoding, 0, diagnostics);
    }

    /**
     * Decodes a stylesheet that sits at {@code base} in a larger coordinate space.
     *
     * <p>Everything {@link #decode(byte[], Charset, Consumer)} does, with the base carried only
     * so that the charset diagnostic points at <em>this</em> source. That span is one of the two
     * in the library no token produces, so nothing else shifts it, and left unshifted every
     * source's charset warning in a bundle would point into whichever source sits at offset
     * zero.
     *
     * <p>The returned text is this source's alone, and its length is what the next source's
     * base is computed from.
     *
     * @param source           the raw stylesheet
     * @param protocolEncoding the encoding the transport claimed, or {@code null}
     * @param base             the offset this source starts at; zero decodes it on its own
     * @param diagnostics      where to report a charset that could not be honoured
     * @return the decoded, preprocessed text
     * @throws IllegalArgumentException if {@code base} is negative
     */
    public static String decode(byte[] source, //
                                Charset protocolEncoding,
                                int base,
                                Consumer<Diagnostic> diagnostics) {
        return decodeSource(source, protocolEncoding, base, diagnostics).text();
    }

    /**
     * Decodes a stylesheet and keeps the buffer, for a caller that will parse it too.
     *
     * <p>Everything {@link #decode(byte[], Charset, int, Consumer)} does, including reporting an
     * {@code @charset} that could not be honoured; the difference is only what is handed back.
     * A {@link DecodedSource} can be parsed by {@link #parse(DecodedSource)} without copying its
     * text a second time, and still yields that text on demand for whatever has to resolve spans
     * against it.
     *
     * <p>{@code decode} followed by {@code parse(CharSequence, int)} remains the simpler pairing
     * and stays correct. This one is for the caller doing it per source in a loop, where two
     * bytes per source character stops being a rounding error.
     *
     * @param source           the raw stylesheet
     * @param protocolEncoding the encoding the transport claimed, or {@code null}; a byte order
     *                         mark and an {@code @charset} rule both outrank it
     * @param base             the offset this source starts at; zero decodes it on its own
     * @param diagnostics      where to report a charset that could not be honoured
     * @return the decoded source, ready to parse
     * @throws IllegalArgumentException if {@code base} is negative
     */
    public static DecodedSource decodeSource(byte[] source,
                                             Charset protocolEncoding,
                                             int base,
                                             Consumer<Diagnostic> diagnostics) {
        SourceText text = SourceText.decode(source, protocolEncoding, base);

        reportCharsetFallback(text, diagnostics);

        return new DecodedSource(text, base);
    }

    /**
     * Decodes a stylesheet, discarding any charset diagnostic.
     *
     * @param source           the raw stylesheet
     * @param protocolEncoding the encoding the transport claimed, or {@code null}
     * @return the decoded, preprocessed text
     * @see #decode(byte[], Charset, Consumer) which says why the sink is worth passing
     */
    public static String decode(byte[] source, //
                                Charset protocolEncoding) {
        return decode(source, protocolEncoding, Diagnostic.DISCARD);
    }

    /**
     * Decodes a stylesheet with no transport-supplied encoding, discarding any charset
     * diagnostic.
     *
     * @param source the raw stylesheet
     * @return the decoded, preprocessed text
     * @see #decode(byte[], Charset, Consumer) which says why the sink is worth passing
     */
    public static String decode(byte[] source) {
        return decode(source, null);
    }

    /**
     * The encoding {@link #decode} would settle on, without decoding anything.
     *
     * <p>For a caller that has to pass an encoding <em>down</em>. CSS Syntax §3.2's fallback
     * chain is a byte order mark, then {@code @charset}, then the <em>environment encoding</em>,
     * then UTF-8, and for a sheet reached through an {@code @import} the environment encoding
     * is the encoding of the sheet that imported it. So a bundler resolving an import passes
     * this, taken from the importing sheet, as the imported one's {@code protocolEncoding}:
     * a sheet that determines nothing for itself inherits its parent's, and one that does
     * determine something still outranks it, because a BOM and an {@code @charset} come first
     * in the chain either way.
     *
     * @param source           the raw stylesheet
     * @param protocolEncoding the encoding the transport claimed, or {@code null}
     * @return the encoding it would be decoded with, or {@code null} for the two encodings
     *         that are not a {@link Charset}, the replacement encoding and
     *         {@code x-user-defined}, neither of which anything can inherit
     * @see <a href="https://www.w3.org/TR/css-syntax-3/#input-byte-stream">CSS Syntax Level 3 §3.2</a>
     */
    public static Charset detectEncoding(byte[] source, //
                                         Charset protocolEncoding) {
        CssEncoding detected = SourceText.detectEncoding(source, protocolEncoding);
        return detected.kind() == CssEncoding.Kind.CHARSET ? detected.charset() : null;
    }

    /**
     * Names an {@code @charset} that was declared and could not be honoured.
     *
     * <p>Decoding falls back rather than failing, and the fallback is otherwise invisible, while
     * reading a stylesheet in the wrong encoding changes what it says. This runs before parsing,
     * which is when it happened, and the diagnostic sorts first for the same reason.
     */
    private static void reportCharsetFallback(SourceText source, //
                                              Consumer<Diagnostic> diagnostics) {
        String label = source.unresolvedCharset();
        if (label == null) {
            return;
        }

        String message = charsetFallbackMessage(label, source.encoding().name(), CssEncoding.catalogues(label));
        diagnostics.accept(Diagnostic.warning(message, source.unresolvedCharsetSpan()));
    }

    /**
     * Package-private so both branches can be asserted.
     *
     * <p>The catalogued branch is unreachable on a runtime that has every charset cassette knows
     * about, which is every ordinary JVM. It exists for a build that dropped some, a native image
     * without {@code -H:+AddAllCharsets} being the case in point. A test cannot take a charset away
     * from the JVM it runs on, so the message is tested here instead of through a decode that
     * cannot be provoked.
     *
     * @param label      the label the stylesheet declared
     * @param used       the name of the encoding actually decoded with
     * @param catalogued whether cassette knows the label, per {@link CssEncoding#catalogues}
     * @return the diagnostic message
     */
    static String charsetFallbackMessage(String label, //
                                         String used,
                                         boolean catalogued) {
        // Catalogued means a real encoding this build cannot supply, so the stylesheet is not
        // at fault and the message should not imply it is.
        String fault = catalogued ? "\" names an encoding this build cannot decode; decoded as "
                                  : "\" names no known encoding; decoded as ";
        return "@charset \"" + label + fault + used + " instead";
    }

    private CssParser() {
        // static-only
    }

}
