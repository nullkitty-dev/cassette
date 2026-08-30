package dev.nullkitty.cassette.ast;

/**
 * A component value that is a single token, rather than a function or a block.
 *
 * <p>CSS Syntax calls these "preserved tokens": everything the parser kept verbatim because
 * it had no reason to look inside. Note what is missing, {@code (}, {@code [},
 * <code>{</code> and their partners never survive as tokens, because the parser turns every
 * balanced pair into a {@link SimpleBlock}, and a {@code FUNCTION} token becomes a
 * {@link FunctionValue}.
 *
 * <p>Escapes are resolved. An ident written {@code caf\e9} arrives as {@code café}, and the
 * serializer re-escapes canonically rather than reproducing the author's escape style. The
 * numeric tokens are the exception: they keep their raw text, because {@code .500},
 * {@code +5} and {@code 1e2} all have to survive a round trip that a {@code double} alone
 * would not preserve.
 *
 * @see <a href="https://www.w3.org/TR/css-syntax-3/#preserved-tokens">CSS Syntax Level 3 §5 Parsing,
 *      preserved tokens</a>
 */
public sealed interface PreservedToken extends ComponentValue
    permits //
        IdentToken,
        AtKeywordToken,
        HashToken,
        StringToken,
        BadStringToken,
        UrlToken,
        BadUrlToken,
        DelimToken,
        NumberToken,
        PercentageToken,
        DimensionToken,
        WhitespaceToken,
        Punctuation,
        Comment {
}
