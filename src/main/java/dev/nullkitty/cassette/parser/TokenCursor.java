package dev.nullkitty.cassette.parser;

import java.util.Arrays;
import java.util.List;

import dev.nullkitty.cassette.ast.AtKeywordToken;
import dev.nullkitty.cassette.ast.BadStringToken;
import dev.nullkitty.cassette.ast.BadUrlToken;
import dev.nullkitty.cassette.ast.Comment;
import dev.nullkitty.cassette.ast.ComponentValue;
import dev.nullkitty.cassette.ast.DelimToken;
import dev.nullkitty.cassette.ast.DimensionToken;
import dev.nullkitty.cassette.ast.FunctionValue;
import dev.nullkitty.cassette.ast.HashToken;
import dev.nullkitty.cassette.ast.IdentToken;
import dev.nullkitty.cassette.ast.NumberToken;
import dev.nullkitty.cassette.ast.PercentageToken;
import dev.nullkitty.cassette.ast.PreservedToken;
import dev.nullkitty.cassette.ast.Punctuation;
import dev.nullkitty.cassette.ast.SimpleBlock;
import dev.nullkitty.cassette.ast.SourceSpan;
import dev.nullkitty.cassette.ast.StringToken;
import dev.nullkitty.cassette.ast.UrlToken;
import dev.nullkitty.cassette.ast.WhitespaceToken;
import dev.nullkitty.cassette.diagnostics.Diagnostic;
import dev.nullkitty.cassette.lexer.TokenBuffer;
import dev.nullkitty.cassette.lexer.TokenType;

/**
 * A position in a {@link TokenBuffer}, plus the CSS Syntax algorithms that both grammars
 * need: §5.4.7 consume a component value, §5.4.8 consume a simple block, §5.4.9 consume a
 * function.
 *
 * <p>{@link Parser} and {@link SelectorParser} are two cursors over the same buffer, and both hit
 * component values: a declaration's value on one side, a functional pseudo-class's arguments on
 * the other. Sharing a base class is what keeps there from being two implementations of "consume a
 * simple block" that drift apart.
 *
 * <p>{@link #limit} is what lets a selector parser work inside a rule's prelude without
 * copying tokens: past it, {@link #peek()} reads end-of-input, so an algorithm that runs off
 * its range stops rather than wandering into the block that follows.
 *
 * @see <a href="https://www.w3.org/TR/css-syntax-3/#consume-component-value">CSS Syntax Level 3
 *      §5.4.7</a>
 * @see <a href="https://www.w3.org/TR/css-syntax-3/#consume-simple-block">CSS Syntax Level 3 §5.4.8</a>
 * @see <a href="https://www.w3.org/TR/css-syntax-3/#consume-function">CSS Syntax Level 3 §5.4.9</a>
 */
abstract class TokenCursor {

    /**
     * Depth of nested blocks past which parsing stops rather than recursing further.
     */
    private static final int MAX_DEPTH = 512;

    protected final TokenBuffer tokens;

    protected final List<Diagnostic> diagnostics;

    /**
     * Scratch space for building child lists, shared by every cursor in one parse.
     *
     * <p>Shared rather than per-cursor because a {@link SelectorParser} is built per rule and
     * again per nested functional pseudo-class: giving each its own would put back one array
     * allocation per rule, which is the cost this is here to remove.
     */
    protected final NodeStack stack;

    /**
     * Index one past the last token this cursor may read.
     *
     * <p>Not final only so {@link #componentValuesIn} can narrow it and put it back.
     */
    protected int limit;

    protected int at;

    private int depth;

    /**
     * Whether {@link #MAX_DEPTH} has already been reported by this cursor.
     *
     * <p>Once the bound is hit, every remaining opener in a run of them hits it again, so
     * input that is nothing but brackets would otherwise report thousands of times. The
     * first report is the only informative one.
     */
    private boolean depthReported;

    /**
     * Expected closers by nesting level, for {@link #nextDepth}; allocated on first use.
     */
    private TokenType[] closerStack;

    protected TokenCursor(TokenBuffer tokens, //
                          List<Diagnostic> diagnostics,
                          NodeStack stack,
                          int from,
                          int limit) {
        this.tokens = tokens;
        this.diagnostics = diagnostics;
        this.stack = stack;
        this.at = from;
        this.limit = limit;
    }

    // -----------------------------------------------------------------------
    // Navigation
    // -----------------------------------------------------------------------

    /**
     * The type of the current token, or {@link TokenType#EOF} at or past the limit.
     */
    protected final TokenType peek() {
        return peek(0);
    }

    /**
     * The type of the token {@code offset} ahead.
     *
     * @param offset how far ahead to look
     * @return the type, or {@link TokenType#EOF} past the limit
     */
    protected final TokenType peek(int offset) {
        int index = this.at + offset;
        return index < this.limit ? this.tokens.type(index) : TokenType.EOF;
    }

    /**
     * Whether this cursor has reached its limit or end of input.
     */
    protected final boolean atEnd() {
        return peek() == TokenType.EOF;
    }

    /**
     * Moves past the current token.
     */
    protected final void advance() {
        this.at++;
    }

    /**
     * Moves past whitespace, leaving comments in place for whoever preserves them.
     */
    protected final void skipWhitespace() {
        while (peek() == TokenType.WHITESPACE) {
            advance();
        }
    }

    /**
     * Moves past whitespace and comments alike.
     */
    protected final void skipTrivia() {
        while (peek().isTrivia()) {
            advance();
        }
    }

    /**
     * Moves past whitespace, reporting whether there was any.
     *
     * <p>The selector grammar needs the answer: whitespace between two compound selectors is
     * the descendant combinator, and whitespace before a {@code >} is not.
     *
     * @return whether any whitespace was skipped
     */
    protected final boolean skipWhitespaceSeen() {
        int before = this.at;
        skipWhitespace();
        return this.at != before;
    }

    /**
     * Moves past comments but not whitespace.
     *
     * <p>This library keeps the comments the tokenizer would otherwise discard, so every grammar
     * that cares about adjacency has to look through them: {@code .a/*x*}{@code /.b} is one
     * compound selector, not two. Comments inside a selector are the one place they are dropped
     * rather than preserved, since the selector grammar has no node to hang them on.
     */
    protected final void skipComments() {
        while (peek() == TokenType.COMMENT) {
            advance();
        }
    }

    /**
     * The index of the next token that is neither whitespace nor a comment.
     *
     * @param from where to start looking
     * @return the index, which may be {@link #limit} if there is no such token
     */
    protected final int nextSignificant(int from) {
        int index = from;
        while (index < this.limit && this.tokens.type(index).isTrivia()) {
            index++;
        }

        return index;
    }

    /**
     * Whether the current token is a delimiter of exactly {@code codePoint}.
     *
     * @param codePoint the delimiter to test for
     * @return whether it matches
     */
    protected final boolean isDelim(char codePoint) {
        return isDelim(0, codePoint);
    }

    /**
     * Whether the token {@code offset} ahead is a delimiter of exactly {@code codePoint}.
     *
     * @param offset    how far ahead to look
     * @param codePoint the delimiter to test for
     * @return whether it matches, {@code false} past the limit
     */
    protected final boolean isDelim(int offset, //
                                    char codePoint) {
        int index = this.at + offset;
        return index < this.limit && this.tokens.isDelim(index, codePoint);
    }

    /**
     * A short description of a token, for error messages.
     *
     * @param index the token index
     * @return the token quoted, or a name for the ones that would quote badly
     */
    protected final String describe(int index) {
        if (index >= this.limit) {
            return "end of input";
        }

        return switch (this.tokens.type(index)) {
            case EOF -> "end of input";
            case WHITESPACE -> "whitespace";
            case COMMENT -> "a comment";
            default -> "'" + this.tokens.raw(index) + "'";
        };
    }

    /**
     * A short description of the current token, for error messages.
     */
    protected final String describeCurrent() {
        return describe(this.at);
    }

    /**
     * Whether tokens {@code index} and {@code index + 1} are written with nothing between
     * them.
     *
     * <p>Adjacency decides meaning in the selector grammar, where {@code a|b} is a namespaced type
     * selector and {@code a |b} is not.
     *
     * @param index index of the first of the two tokens
     * @return whether the second starts exactly where the first ends
     */
    protected final boolean isAdjacent(int index) {
        return this.tokens.end(index) == this.tokens.start(index + 1);
    }

    /**
     * The span of the current token.
     */
    protected final SourceSpan span() {
        return this.tokens.span(this.at);
    }

    /**
     * The span covering everything from token {@code from} up to the current position.
     *
     * @param from index of the first token
     * @return the covering span
     */
    protected final SourceSpan spanFrom(int from) {
        return this.tokens.span(from, this.at);
    }

    /**
     * {@link #span()} packed, for building a node rather than a diagnostic.
     */
    protected final long packedSpan() {
        return this.tokens.packedSpan(this.at);
    }

    /**
     * {@link #spanFrom(int)} packed, for building a node rather than a diagnostic.
     *
     * @param from index of the first token
     * @return the packed covering span
     */
    protected final long packedSpanFrom(int from) {
        return this.tokens.packedSpan(from, this.at);
    }

    // -----------------------------------------------------------------------
    // Diagnostics
    // -----------------------------------------------------------------------

    protected final void error(String message, SourceSpan span) {
        this.diagnostics.add(Diagnostic.error(message, span));
    }

    protected final void warning(String message, SourceSpan span) {
        this.diagnostics.add(Diagnostic.warning(message, span));
    }

    // -----------------------------------------------------------------------
    // §5.4.7-5.4.9 Component values
    // -----------------------------------------------------------------------

    /**
     * §5.4.7 Consume a component value: a preserved token, a function, or a simple block.
     *
     * @return the value; never {@code null}, since every token is one of the three
     * @see <a href="https://www.w3.org/TR/css-syntax-3/#consume-component-value">CSS Syntax Level 3
     *      §5.4.7</a>
     */
    protected final ComponentValue consumeComponentValue() {
        return switch (peek()) {
            case LEFT_CURLY -> consumeSimpleBlock('{');
            case LEFT_SQUARE -> consumeSimpleBlock('[');
            case LEFT_PAREN -> consumeSimpleBlock('(');
            case FUNCTION -> consumeFunction();
            default -> consumePreservedToken();
        };
    }

    /**
     * §5.4.8 Consume a simple block.
     *
     * <p>Recursion is bounded: past {@link #MAX_DEPTH} nested brackets the block is closed
     * where it stands and the rest is consumed flat. Real CSS nests a handful deep, and a
     * {@code StackOverflowError} is not one of the failure modes "never throws on malformed
     * input" is allowed to have.
     *
     * @see <a href="https://www.w3.org/TR/css-syntax-3/#consume-simple-block">CSS Syntax Level 3
     *      §5.4.8</a>
     */
    private ComponentValue consumeSimpleBlock(char open) {
        int start = this.at;
        TokenType closer = closerFor(open);

        advance();

        List<ComponentValue> contents = consumeUntil(closer, start, describeBlock(open));
        return new SimpleBlock(open, contents, spanFrom(start));
    }

    /**
     * §5.4.9 Consume a function.
     *
     * @see <a href="https://www.w3.org/TR/css-syntax-3/#consume-function">CSS Syntax Level 3 §5.4.9</a>
     */
    private ComponentValue consumeFunction() {
        int start = this.at;
        String name = this.tokens.value(start);

        advance();

        List<ComponentValue> arguments = consumeUntil(TokenType.RIGHT_PAREN, start, "function " + name + "()");
        return new FunctionValue(name, arguments, spanFrom(start));
    }

    /**
     * Collects component values up to and including {@code closer}, reporting the construct
     * as unclosed if end of input arrives first.
     */
    private List<ComponentValue> consumeUntil(TokenType closer, int start, String what) {
        int mark = this.stack.mark();

        if (++this.depth > MAX_DEPTH) {
            this.depth--;

            if (!this.depthReported) {
                this.depthReported = true;
                error("nesting too deep, stopped at " + MAX_DEPTH + " levels", this.tokens.span(start));
            }

            return List.of();
        }

        while (true) {
            if (atEnd()) {
                // Loud on purpose: an unclosed bracket does not fail locally, it eats every
                // rule that follows while it looks for a partner that never arrives.
                error("unclosed " + what + ", which consumed everything after it", this.tokens.span(start, this.at));
                break;
            }

            if (peek() == closer) {
                advance();
                break;
            }

            this.stack.push(consumeComponentValue());
        }

        this.depth--;

        return this.stack.take(mark);
    }

    /**
     * Turns the current token into its AST node and moves past it.
     *
     * <p>Bracket tokens do not arrive here except unmatched: an opener would have become a
     * block, and a closer only survives when the source had one too many.
     */
    private PreservedToken consumePreservedToken() {
        int index = this.at;

        // Packed, not a SourceSpan: this runs once per token, and only the two malformed
        // cases below ever need the unpacked form.
        long tokenSpan = this.tokens.packedSpan(index);

        TokenType type = peek();

        advance();

        return switch (type) {
            case IDENT -> new IdentToken(this.tokens.value(index), tokenSpan);

            case AT_KEYWORD -> new AtKeywordToken(this.tokens.value(index), tokenSpan);

            case HASH -> new HashToken(this.tokens.value(index), this.tokens.isIdHash(index), tokenSpan);

            case STRING -> stringToken(index, tokenSpan);

            case BAD_STRING -> {
                // Not merely unterminated: the newline that ended it is still unconsumed, and
                // everything before it on that line (a ';' included) went into the token.
                error("unterminated string, ended by a newline", SourceSpan.unpack(tokenSpan));
                yield new BadStringToken(tokenSpan);
            }

            case URL -> urlToken(index, tokenSpan);

            case BAD_URL -> {
                error("malformed url()", SourceSpan.unpack(tokenSpan));
                yield new BadUrlToken(tokenSpan);
            }

            case DELIM -> delimToken(index, tokenSpan);

            case NUMBER -> new NumberToken(this.tokens.raw(index),
                                           this.tokens.numericValue(index),
                                           this.tokens.hasSign(index),
                                           this.tokens.hasExponent(index),
                                           tokenSpan);

            case PERCENTAGE -> new PercentageToken(this.tokens.value(index),
                                                   this.tokens.numericValue(index),
                                                   this.tokens.hasSign(index),
                                                   this.tokens.hasExponent(index),
                                                   tokenSpan);

            case DIMENSION -> new DimensionToken(this.tokens.prefix(index),
                                                 this.tokens.numericValue(index),
                                                 this.tokens.value(index),
                                                 this.tokens.hasSign(index),
                                                 this.tokens.hasExponent(index),
                                                 tokenSpan);

            case WHITESPACE -> new WhitespaceToken(tokenSpan);

            case COMMENT -> commentAt(index);

            case COMMA -> punctuation(Punctuation.Kind.COMMA, tokenSpan);

            case COLON -> punctuation(Punctuation.Kind.COLON, tokenSpan);

            case SEMICOLON -> punctuation(Punctuation.Kind.SEMICOLON, tokenSpan);

            case CDO -> punctuation(Punctuation.Kind.CDO, tokenSpan);

            case CDC -> punctuation(Punctuation.Kind.CDC, tokenSpan);

            case RIGHT_PAREN -> unmatched(Punctuation.Kind.RIGHT_PAREN, tokenSpan);

            case RIGHT_SQUARE -> unmatched(Punctuation.Kind.RIGHT_SQUARE, tokenSpan);

            case RIGHT_CURLY -> unmatched(Punctuation.Kind.RIGHT_CURLY, tokenSpan);

            // Openers become blocks and a FUNCTION becomes a function, both in
            // consumeComponentValue, and EOF never advances past itself; any of them
            // arriving here is a bug in this class, not in the input.
            case FUNCTION, LEFT_PAREN, LEFT_SQUARE, LEFT_CURLY, EOF -> throw new IllegalStateException("not a preserved token: "
                                                                                                       + type);
        };
    }

    /**
     * Builds a comment node, reporting one that end of input cut short.
     *
     * <p>Shared with {@link Parser}, which meets comments between rules rather than inside a
     * value, so that both places say the same thing about the same comment.
     *
     * @param index the comment token's index; the cursor is not moved
     * @return the node
     */
    protected final Comment commentAt(int index) {
        boolean terminated = this.tokens.isTerminated(index);
        long tokenSpan = this.tokens.packedSpan(index);
        if (!terminated) {
            error("unterminated comment", SourceSpan.unpack(tokenSpan));
        }

        return new Comment(this.tokens.value(index), terminated, tokenSpan);
    }

    /**
     * Builds a string node, reporting one that end of input closed rather than a quote.
     *
     * <p>§4.3.5 calls that a parse error and still produces a string-token. The newline case
     * produces a bad-string-token instead. Both are worth telling the caller about; only one is
     * malformed.
     *
     * @see <a href="https://www.w3.org/TR/css-syntax-3/#consume-string-token">CSS Syntax Level 3
     *      §4.3.5</a>
     */
    private StringToken stringToken(int index, //
                                    long tokenSpan) {
        boolean terminated = this.tokens.isTerminated(index);
        if (!terminated) {
            error("unterminated string at end of input", SourceSpan.unpack(tokenSpan));
        }

        return new StringToken(this.tokens.value(index), terminated, tokenSpan);
    }

    /**
     * The url() equivalent of {@link #stringToken}, §4.3.6.
     *
     * @see <a href="https://www.w3.org/TR/css-syntax-3/#consume-url-token">CSS Syntax Level 3 §4.3.6</a>
     */
    private UrlToken urlToken(int index, //
                              long tokenSpan) {
        boolean terminated = this.tokens.isTerminated(index);
        if (!terminated) {
            error("unterminated url() at end of input", SourceSpan.unpack(tokenSpan));
        }

        return new UrlToken(this.tokens.value(index), terminated, tokenSpan);
    }

    /**
     * A backslash that did not start an escape is the only delimiter worth reporting.
     *
     * <p>§4.3.8 says a backslash before a newline does not start a valid escape, so it
     * survives as a plain delimiter, the one construct in the grammar that reaches the tree
     * having already failed. The serializer drops it for want of anything that reads back as
     * itself, which is a silent loss unless the parse says something about it.
     *
     * @see <a href="https://www.w3.org/TR/css-syntax-3/#starts-with-a-valid-escape">CSS Syntax Level 3
     *      §4.3.8</a>
     */
    private PreservedToken delimToken(int index, //
                                      long tokenSpan) {
        int codePoint = this.tokens.delimCodePoint(index);
        if (codePoint == '\\') {
            error("stray '\\' before a newline, which does not start an escape", SourceSpan.unpack(tokenSpan));
        }

        return new DelimToken(codePoint, tokenSpan);
    }

    private PreservedToken punctuation(Punctuation.Kind kind, long tokenSpan) {
        return new Punctuation(kind, tokenSpan);
    }

    private PreservedToken unmatched(Punctuation.Kind kind, long tokenSpan) {
        error("unmatched " + kind.text(), SourceSpan.unpack(tokenSpan));
        return new Punctuation(kind, tokenSpan);
    }

    // Not an EnumMap: the key is a char, not an enum, and three cases over dense small
    // chars are already a jump table once javac is done with them.
    private static TokenType closerFor(char open) {
        return switch (open) {
            case '(' -> TokenType.RIGHT_PAREN;
            case '[' -> TokenType.RIGHT_SQUARE;
            default -> TokenType.RIGHT_CURLY;
        };
    }

    private static String describeBlock(char open) {
        return switch (open) {
            case '(' -> "( ) block";
            case '[' -> "[ ] block";
            default -> "{ } block";
        };
    }

    /**
     * Consumes every component value in {@code [from, to)}, leaving this cursor where it was.
     *
     * <p>For the places a sub-range has to be read out of order: a functional pseudo-class's
     * arguments, which the selector parser reaches only after it has found the matching
     * {@code )}.
     *
     * @param from index of the first token to read
     * @param to   index one past the last token to read
     * @return the component values, whitespace-trimmed at both ends
     */
    protected final List<ComponentValue> componentValuesIn(int from, //
                                                           int to) {
        int savedAt = this.at;
        int savedLimit = this.limit;

        this.at = from;
        this.limit = to;

        int mark = this.stack.mark();

        while (!atEnd()) {
            this.stack.push(consumeComponentValue());
        }

        this.at = savedAt;
        this.limit = savedLimit;

        return this.stack.takeTrimmed(mark);
    }

    /**
     * Finds the token closing the bracket or function opened at {@code opener}.
     *
     * @param opener index of a {@code FUNCTION} or opening-bracket token
     * @return index of the matching closer, or {@link #limit} if the source never closed it
     */
    protected final int findMatchingClose(int opener) {
        int depth = 0;
        for (int index = opener; index < this.limit; index++) {
            int next = nextDepth(depth, this.tokens.type(index));
            if (next == 0 && depth == 1) {
                return index;
            }

            depth = next;
        }

        return this.limit;
    }

    /**
     * Advances a bracket-nesting depth by one token.
     *
     * <p>A closer that does not match the innermost opener is <em>ignored</em> rather than
     * counted, because that is what the spec does with it: an unmatched {@code ]} is a
     * preserved token, so <code>{ ] }</code> is one block and not the start of two. Counting
     * every closer alike instead ends a block at the first stray one, which loses the rest of
     * it, the trap the {@code wpt-custom-property-rule-ambiguity} fixture pins down.
     *
     * <p>The stack behind this is a field, reused across calls. Every caller is a
     * straight-line scan, so no two uses on one cursor are ever interleaved.
     *
     * @param depth the depth before this token
     * @param type  the token's type
     * @return the depth after it
     */
    protected final int nextDepth(int depth, //
                                  TokenType type) {
        TokenType closer = closerFor(type);
        if (closer != null) {
            if (this.closerStack == null) {
                this.closerStack = new TokenType[16];
            }
            else if (depth == this.closerStack.length) {
                this.closerStack = Arrays.copyOf(this.closerStack, depth * 2);
            }

            this.closerStack[depth] = closer;

            return depth + 1;
        }

        return depth > 0 && type == this.closerStack[depth - 1] ? depth - 1 : depth;
    }

    private static TokenType closerFor(TokenType type) {
        return switch (type) {
            case FUNCTION, LEFT_PAREN -> TokenType.RIGHT_PAREN;
            case LEFT_SQUARE -> TokenType.RIGHT_SQUARE;
            case LEFT_CURLY -> TokenType.RIGHT_CURLY;
            default -> null;
        };
    }

    // -----------------------------------------------------------------------
    // Shared list hygiene
    // -----------------------------------------------------------------------

    /**
     * Drops whitespace from both ends of a component-value list.
     *
     * <p>Whitespace between values is meaningful, since {@code 1px solid} is two values and the gap
     * is what says so. Whitespace at the edges of a value or prelude never is, and keeping it would
     * leave the serializer trimming what the parser could have.
     *
     * @param values the list to trim, consumed in place
     * @return the trimmed list
     */
    protected static List<ComponentValue> trimWhitespace(List<ComponentValue> values) {
        int from = 0;
        int to = values.size();

        while (from < to && values.get(from) instanceof WhitespaceToken) {
            from++;
        }

        while (to > from && values.get(to - 1) instanceof WhitespaceToken) {
            to--;
        }

        return from == 0 && to == values.size() ? values : List.copyOf(values.subList(from, to));
    }
}
