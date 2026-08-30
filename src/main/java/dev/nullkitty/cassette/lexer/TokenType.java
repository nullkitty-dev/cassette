package dev.nullkitty.cassette.lexer;

/**
 * The token types of CSS Syntax Module Level 3, §4.
 *
 * <p>{@link #COMMENT} is the one addition. The spec consumes comments and emits nothing;
 * cassette emits them, because the AST keeps comments as real nodes so the passthrough
 * serializer can reproduce them. Every consumer that isn't a formatter should skip
 * {@code COMMENT} alongside {@link #WHITESPACE}.
 *
 * @see <a href="https://www.w3.org/TR/css-syntax-3/#tokenization">CSS Syntax Level 3 §4 Tokenization</a>
 */
public enum TokenType {

    /**
     * An identifier, e.g. {@code color} or {@code caf\e9}.
     */
    IDENT,

    /**
     * An identifier immediately followed by {@code (}, e.g. {@code rgb(}.
     */
    FUNCTION,

    /**
     * {@code @} followed by an identifier, e.g. {@code @media}.
     */
    AT_KEYWORD,

    /**
     * {@code #} followed by ident code points, e.g. {@code #fff} or {@code #main}.
     */
    HASH,

    /**
     * A quoted string.
     */
    STRING,

    /**
     * A string interrupted by a newline: CSS Syntax's bad-string-token.
     *
     * @see <a href="https://www.w3.org/TR/css-syntax-3/#consume-string-token">CSS Syntax Level 3 §4.3.5
     *      Consume a string token</a>
     */
    BAD_STRING,

    /**
     * An unquoted {@code url(...)}.
     *
     * <p>Quoted ones tokenize as {@link #FUNCTION} plus {@link #STRING}.
     */
    URL,

    /**
     * A malformed unquoted url: CSS Syntax's bad-url-token.
     *
     * @see <a href="https://www.w3.org/TR/css-syntax-3/#consume-url-token">CSS Syntax Level 3 §4.3.6
     *      Consume a url token</a>
     */
    BAD_URL,

    /**
     * A single code point with no more specific meaning, e.g. {@code *} or {@code >}.
     */
    DELIM,

    /**
     * A number with no unit.
     */
    NUMBER,

    /**
     * A number followed by {@code %}.
     */
    PERCENTAGE,

    /**
     * A number followed by a unit identifier, e.g. {@code 10px}.
     */
    DIMENSION,

    /**
     * One or more consecutive whitespace code points.
     */
    WHITESPACE,

    /**
     * Comment start: {@code <!--}
     */
    CDO,

    /**
     * Comment end: {@code -->}
     */
    CDC,

    /**
     * A colon ({@code :})
     */
    COLON,

    /**
     * A semi-colon ({@code ;}
     */
    SEMICOLON,

    /**
     * A comma ({@code ,})
     */
    COMMA,

    /**
     * A left-bracket ({@code [})
     */
    LEFT_SQUARE,

    /**
     * A reight-bracket ({@code ]})
     */
    RIGHT_SQUARE,

    /**
     * A left-parenthesis ({@code (})
     */
    LEFT_PAREN,

    /**
     * A right-parenthesis ({@code )}))
     */
    RIGHT_PAREN,

    /**
     * A left-curly-brace (<code>{</code>)
     */
    LEFT_CURLY,

    /**
     * A right-curly-brace (<code>}</code>)
     */
    RIGHT_CURLY,

    /**
     * A {@code /* ... *}{@code /} comment.
     *
     * <p>Not a CSS Syntax token type.
     * The spec's "consume comments" discards them, and the linked algorithm is the closest thing
     * to a definition.
     * See the class docs for why cassette emits them instead.
     *
     * @see <a href="https://www.w3.org/TR/css-syntax-3/#consume-comment">CSS Syntax Level 3 §4.3.2
     *      Consume comments</a>
     */
    COMMENT,

    /**
     * End of input.
     */
    EOF;

    /**
     * Whether this token carries no meaning for the parser: whitespace or a comment.
     */
    public boolean isTrivia() {
        return this == WHITESPACE || this == COMMENT;
    }

    /**
     * Whether this token is a number, percentage or dimension.
     */
    public boolean isNumeric() {
        return this == NUMBER || this == PERCENTAGE || this == DIMENSION;
    }
}
