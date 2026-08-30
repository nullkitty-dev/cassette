package dev.nullkitty.cassette.bundle;

import java.nio.charset.Charset;
import java.util.Objects;

/**
 * One stylesheet to be bundled: what to call it, and its bytes.
 *
 * <p>Bytes rather than text, for the reason the parser takes bytes. CSS Syntax's charset detection
 * is defined over bytes, sniffing a byte order mark, then an {@code @charset} rule, then what the
 * transport claimed, then UTF-8, and decoding to a {@code String} first throws away exactly what it
 * needs. Every source is decoded on its own, so a UTF-8 index importing a Shift_JIS partial works.
 * There is no single bundle encoding, only a single bundle coordinate space, and that space is
 * decoded characters.
 *
 * <p>{@code id} is opaque to cassette. It is compared for cycle detection, printed in diagnostics
 * and banners, and never interpreted: a filesystem importer returns a canonical absolute path, a
 * classpath importer a resource name, a test importer whatever key its map uses. Canonicalizing is
 * the caller's job, so two ids differing by a {@code ../} are two different sources as far as this
 * package is concerned.
 *
 * @param id               what to call this source
 * @param content          its raw bytes, held by reference, not copied, and not to be modified
 * @param protocolEncoding the encoding the transport claimed, or {@code null}. Leave it null unless
 *                         something said so. It is the environment encoding of CSS Syntax
 *                         §3.2, which a byte order mark and an {@code @charset} both outrank, and
 *                         which for a source reached through an {@code @import} is otherwise
 *                         inherited from the sheet that imported it. Setting it defeats that
 *                         inheritance, so it should carry something cassette cannot know, such as
 *                         an HTTP {@code Content-Type} parameter, and nothing else.
 * @see <a href="https://www.w3.org/TR/css-syntax-3/#environment-encoding">CSS Syntax Level 3 §3.2</a>
 */
public record Source(String id, //
                     byte[] content,
                     Charset protocolEncoding) {

    /**
     * @throws NullPointerException if {@code id} or {@code content} is null
     */
    public Source {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(content, "content");
    }

    /**
     * A source with no transport-supplied encoding, which is the ordinary case.
     *
     * @param id      what to call this source
     * @param content its raw bytes
     */
    public Source(String id, byte[] content) {
        this(id, content, null);
    }

    /**
     * The array itself, not a copy.
     *
     * <p>Bundles are megabytes and this is read once, so the array is not copied. Do not modify
     * it.
     *
     * @return the raw bytes
     */
    @Override
    public byte[] content() {
        return this.content;
    }

    /**
     * Identity by id. {@code content} is an array, so the generated equality would compare
     * references and two sources built from the same bytes would differ.
     *
     * @param other what to compare with
     * @return whether both are sources with the same id and encoding
     */
    @Override
    public boolean equals(Object other) {
        return other instanceof Source source
               && this.id.equals(source.id)
               && Objects.equals(this.protocolEncoding, source.protocolEncoding);
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.id, this.protocolEncoding);
    }

    @Override
    public String toString() {
        return "Source["
               + this.id
               + ", "
               + this.content.length
               + " bytes"
               + (this.protocolEncoding == null ? "" : ", " + this.protocolEncoding.name())
               + "]";
    }
}
