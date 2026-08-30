package dev.nullkitty.cassette.serializer;

/**
 * How identifiers and strings carrying non-ASCII characters are written.
 *
 * <p>Escapes are always canonical either way, the author's own escape style is not
 * recorded in the AST and cannot be reproduced. This chooses only how much gets escaped.
 */
public enum IdentifierEncoding {

    /**
     * Emit non-ASCII characters as themselves: {@code café}.
     *
     * <p>Correct for any engine that reads the output as UTF-8, which is every engine that
     * is told the encoding or finds a BOM.
     */
    LITERAL,

    /**
     * Escape every non-ASCII character: {@code caf\e9 }.
     *
     * <p>Makes the output pure ASCII, so it survives being served without a charset, or
     * being read by an engine whose Unicode handling is not trusted, the same old-hardware
     * concern {@link NestingExpansion#DUPLICATE} exists for.
     */
    ASCII
}
