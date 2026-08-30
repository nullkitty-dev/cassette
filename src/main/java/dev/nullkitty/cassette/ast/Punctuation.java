package dev.nullkitty.cassette.ast;

/**
 * A structural mark that carries no data of its own: {@code ,}, {@code :}, {@code ;},
 * {@code <!--}, {@code -->}.
 *
 * <p>CSS Syntax gives each of these its own token type. They share one record here because they
 * share one shape, which is a fixed spelling and a span and nothing else. Five records differing
 * only in name would buy pattern-matching precision nothing needs. {@link Kind} is still
 * exhaustive, so a {@code switch} over it is checked.
 *
 * @param kind       which mark this is
 * @param packedSpan the packed region of source this token was parsed from
 * @see <a href="https://www.w3.org/TR/css-syntax-3/#tokenization">CSS Syntax Level 3 §4 Tokenization</a>
 */
public record Punctuation(Punctuation.Kind kind, //
                          long packedSpan)
    implements
        PreservedToken {

    /**
     * Builds this node from an unpacked span.
     *
     * @see SourceSpan
     */
    public Punctuation(Punctuation.Kind kind, //
                       SourceSpan span) {
        this(kind, span.packed());
    }

    /**
     * The marks that reach the AST as punctuation.
     */
    public enum Kind {

        /**
         * A comma, {@code ,}: the separator in selector lists and function arguments.
         */
        COMMA(","),

        /**
         * A colon, {@code :}.
         */
        COLON(":"),

        /**
         * A semicolon, {@code ;}, in a position the parser did not consume as a terminator.
         */
        SEMICOLON(";"),

        /**
         * An HTML comment open, {@code <!--}.
         *
         * <p>A relic of hiding CSS from 1990s browsers, still in the grammar, still legal at
         * the top level of a stylesheet.
         */
        CDO("<!--"),

        /**
         * An HTML comment close, {@code -->}.
         */
        CDC("-->"),

        /**
         * An unmatched {@code )}.
         *
         * <p>Only ever appears unmatched. A matched pair becomes a {@link SimpleBlock} or a
         * {@link FunctionValue}, brackets and all, so a closer reaching the tree on its own
         * means the source had one too many, a parse error the spec still preserves.
         */
        RIGHT_PAREN(")"),

        /**
         * An unmatched {@code ]}; see {@link #RIGHT_PAREN}.
         */
        RIGHT_SQUARE("]"),

        /**
         * An unmatched <code>}</code>; see {@link #RIGHT_PAREN}.
         */
        RIGHT_CURLY("}");

        private final String text;

        Kind(String text) {
            this.text = text;
        }

        /**
         * How this mark is spelled.
         *
         * @return the mark's one and only source text
         */
        public String text() {
            return this.text;
        }
    }

    /**
     * How this mark is spelled.
     *
     * @return the mark's source text
     */
    public String text() {
        return this.kind.text();
    }
}
