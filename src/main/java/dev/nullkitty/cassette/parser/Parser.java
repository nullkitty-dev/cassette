package dev.nullkitty.cassette.parser;

import java.util.List;

import dev.nullkitty.cassette.ast.AtRule;
import dev.nullkitty.cassette.ast.Comment;
import dev.nullkitty.cassette.ast.ComponentValue;
import dev.nullkitty.cassette.ast.ConditionalGroupRule;
import dev.nullkitty.cassette.ast.Declaration;
import dev.nullkitty.cassette.ast.DelimToken;
import dev.nullkitty.cassette.ast.IdentToken;
import dev.nullkitty.cassette.ast.Node;
import dev.nullkitty.cassette.ast.Rule;
import dev.nullkitty.cassette.ast.SelectorList;
import dev.nullkitty.cassette.ast.SimpleBlock;
import dev.nullkitty.cassette.ast.StyleRule;
import dev.nullkitty.cassette.ast.Stylesheet;
import dev.nullkitty.cassette.diagnostics.Diagnostic;
import dev.nullkitty.cassette.lexer.TokenBuffer;
import dev.nullkitty.cassette.lexer.TokenType;
import dev.nullkitty.cassette.text.Ascii;

/**
 * CSS Syntax Module Level 3 §5, amended by CSS Nesting Module Level 1.
 *
 * <p>Recursive descent, one pass, no backtracking except the bounded lookahead in
 * {@link #looksLikeDeclaration()}. Nothing here throws for malformed input: every algorithm
 * that can fail reports a {@link Diagnostic} and resynchronizes, which is what the spec
 * defines rather than a courtesy this parser adds.
 *
 * <p>Nesting shows up in two places. A style rule's block is a <em>style block</em>,
 * declarations, nested rules and comments interleaved, rather than the declaration list
 * Level 3 alone would give it. And a conditional group rule's block is parsed as rules, or
 * as a style block when it is itself nested, instead of being kept as opaque tokens.
 *
 * @see <a href="https://www.w3.org/TR/css-syntax-3/#parsing">CSS Syntax Level 3 §5</a>
 * @see <a href="https://www.w3.org/TR/css-nesting-1/">CSS Nesting Module Level 1</a>
 */
final class Parser extends TokenCursor {

    Parser(TokenBuffer tokens, //
           List<Diagnostic> diagnostics) {
        // The limit excludes the buffer's trailing EOF entry: past it, peek() reports
        // end-of-input, which is what every algorithm below already checks for.
        super(tokens, diagnostics, new NodeStack(), 0, tokens.eofIndex());
    }

    /**
     * §5.3.3 Parse a stylesheet.
     *
     * @see <a href="https://www.w3.org/TR/css-syntax-3/#parse-stylesheet">CSS Syntax Level 3 §5.3.3</a>
     */
    Stylesheet parseStylesheet() {
        List<Node> children = consumeRules(true, false);

        // Weak on its own; a list leaked further down would have been swept into its parent's
        // take() long before here, leaving the stack balanced, so the load-bearing check is
        // the one in SelectorParser.unwindIfNull, next to the paths that can actually leak.
        // This one only says the outermost list closed.
        assert this.stack.isEmpty(0) : "child list left on the stack";
        return new Stylesheet(children, this.tokens.packedSpanOfSource());
    }

    // -----------------------------------------------------------------------
    // §5.4.1 Consume a list of rules
    // -----------------------------------------------------------------------

    /**
     * @param topLevel    whether this is the stylesheet's own rule list, where {@code <!--}
     *                    and {@code -->} are discarded rather than treated as the start of a
     *                    rule, the spec's one concession to CSS hidden inside 1990s HTML
     * @param stopAtCurly whether a {@code }} ends the list, as it does inside a group rule,
     *                    rather than being a stray the parser complains about
     */
    private List<Node> consumeRules(boolean topLevel, boolean stopAtCurly) {
        int mark = this.stack.mark();

        while (true) {
            switch (peek()) {
                case WHITESPACE -> advance();

                case COMMENT -> this.stack.push(consumeComment());

                case EOF -> {
                    return this.stack.take(mark);
                }

                case RIGHT_CURLY -> {
                    if (stopAtCurly) {
                        return this.stack.take(mark);
                    }

                    error("unexpected '}'", span());

                    advance();
                }

                case CDO, CDC -> {
                    if (topLevel) {
                        advance();
                    }
                    else {
                        this.stack.pushIfPresent(consumeQualifiedRule(false));
                    }
                }

                case AT_KEYWORD -> this.stack.push(consumeAtRule(false));

                default -> this.stack.pushIfPresent(consumeQualifiedRule(false));
            }
        }
    }

    // -----------------------------------------------------------------------
    // §5.4.2 Consume an at-rule
    // -----------------------------------------------------------------------

    /**
     * @param nested whether this at-rule sits inside a style rule, which decides whether a
     *               group rule's block holds rules or a style block
     */
    private Rule consumeAtRule(boolean nested) {
        int start = this.at;
        String name = this.tokens.value(this.at);
        advance();

        int prelude = this.stack.mark();

        while (true) {
            switch (peek()) {
                case SEMICOLON -> {
                    advance();

                    return statementAtRule(start, name, prelude);
                }

                case EOF -> {
                    error("unclosed at-rule '@" + name + "'", spanFrom(start));

                    return statementAtRule(start, name, prelude);
                }

                case RIGHT_CURLY -> {
                    // The enclosing block's closer. Leaving it unconsumed is what lets the
                    // caller finish its own block instead of losing it to this at-rule.
                    error("at-rule '@" + name + "' has no block or ';'", spanFrom(start));

                    return statementAtRule(start, name, prelude);
                }

                case LEFT_CURLY -> {
                    // Taken before the call rather than inside it: the block about to be
                    // consumed pushes onto the same stack, and the prelude has to be off it
                    // first. Argument evaluation order is what guarantees that.
                    return consumeAtRuleBlock(start, name, this.stack.takeTrimmed(prelude), nested);
                }

                default -> this.stack.push(consumeComponentValue());
            }
        }
    }

    private AtRule statementAtRule(int start, //
                                   String name,
                                   int prelude) {
        return new AtRule(name, this.stack.takeTrimmed(prelude), null, packedSpanFrom(start));
    }

    private Rule consumeAtRuleBlock(int start, //
                                    String name,
                                    List<ComponentValue> prelude,
                                    boolean nested) {
        if (!ConditionalGroupRule.isConditionalGroupName(name)) {
            // Everything else keeps its block as tokens, which is all Level 3's grammar
            // knows about @font-face and @keyframes too.
            ComponentValue block = consumeComponentValue();

            List<ComponentValue> contents =
                block instanceof SimpleBlock simple ? trimWhitespace(simple.contents()) : List.<ComponentValue> of();

            return new AtRule(name, prelude, contents, packedSpanFrom(start));
        }

        int brace = this.at;

        advance();

        List<Node> body = nested ? consumeStyleBlock() : consumeRules(false, true);

        expectCloseCurly(brace, "'@" + name + "'");

        return new ConditionalGroupRule(name, prelude, body, packedSpanFrom(start));
    }

    // -----------------------------------------------------------------------
    // §5.4.3 Consume a qualified rule
    // -----------------------------------------------------------------------

    /**
     * Consumes a style rule, or the wreckage of one.
     *
     * <p>The prelude is not built as component values. It is located as a token range and
     * handed to {@link SelectorParser}, which needs the tokens themselves, adjacency is
     * part of the selector grammar, and a component-value list has already thrown it away.
     *
     * @param nested whether this rule sits inside another rule's block
     * @return the rule, or {@code null} if it was dropped; the block is consumed either way,
     *         so the caller resumes at the right place
     */
    private Rule consumeQualifiedRule(boolean nested) {
        int start = this.at;
        int brace = findBlock(start, nested);
        if (brace < 0) {
            return null;
        }

        if (preludeStartsACustomProperty(start, brace)) {
            skipBlock(brace);
            return null;
        }

        // A nested rule's prelude is a relative selector list, so it may open with a
        // combinator: '> .title { }' inside another rule means '& > .title'.
        SelectorList selectors = SelectorParser.parse(this.tokens, this.diagnostics, this.stack, start, brace, nested);

        this.at = brace;

        advance();

        List<Node> body = consumeStyleBlock();

        expectCloseCurly(brace, "style rule");

        // An invalid selector list invalidates the rule, but only after its block has been
        // consumed; otherwise the block's contents would be reparsed as top-level rules.
        return selectors == null ? null : new StyleRule(selectors, body, packedSpanFrom(start));
    }

    /**
     * §5.5.3: a prelude whose first two significant values are an identifier starting with
     * {@code --} and a colon is a custom property that went wrong, and the rule is dropped
     * rather than read as a selector.
     *
     * <p>Without this, {@code --x:hover { }} parses as the entirely plausible selector
     * {@code --x:hover}, an identifier and a pseudo-class, and a stylesheet that meant to
     * declare a custom property would silently grow a rule instead.
     *
     * <p>Only the non-nested case reaches here. Inside a style block a custom property is
     * always a declaration, because {@link #looksLikeDeclaration()} short-circuits on the
     * {@code --} prefix before a qualified rule is ever attempted, which is the same order
     * the spec's own nested case works out to.
     *
     * @see <a href="https://drafts.csswg.org/css-syntax-3/#consume-qualified-rule">CSS Syntax Level 3
     *      editor's draft §5.5.3</a>
     */
    private boolean preludeStartsACustomProperty(int start, //
                                                 int brace) {
        int name = nextSignificant(start);
        if (name >= brace || this.tokens.type(name) != TokenType.IDENT || !this.tokens.valueStartsWith(name, "--")) {
            return false;
        }

        int colon = nextSignificant(name + 1);
        if (colon >= brace || this.tokens.type(colon) != TokenType.COLON) {
            return false;
        }

        error("'" + this.tokens.value(name) + ":' starts a custom property, not a selector",
              this.tokens.span(start, brace));

        return true;
    }

    /**
     * Consumes a block whole and discards it, leaving the cursor just past its closer.
     */
    private void skipBlock(int brace) {
        this.at = brace;
        int close = findMatchingClose(brace);
        this.at = close < this.limit ? close + 1 : this.limit;
    }

    /**
     * Scans forward for the {@code {} that opens a qualified rule's block.
     *
     * @return the token index of the opening brace, or -1 if the rule is malformed, in which
     *         case the cursor has been moved to where parsing should resume
     */
    private int findBlock(int start, //
                          boolean nested) {
        int depth = 0;

        for (int index = start; index < this.limit; index++) {
            TokenType type = this.tokens.type(index);
            if (depth == 0) {
                if (type == TokenType.LEFT_CURLY) {
                    return index;
                }

                if (type == TokenType.RIGHT_CURLY) {
                    error("style rule has no block", this.tokens.span(start, index));
                    this.at = index;
                    return -1;
                }

                if (nested && type == TokenType.SEMICOLON) {
                    error("expected a declaration or a nested rule", this.tokens.span(start, index + 1));
                    this.at = index + 1;
                    return -1;
                }
            }

            depth = nextDepth(depth, type);
        }

        error("style rule has no block", this.tokens.span(start, this.limit));

        this.at = this.limit;

        return -1;
    }

    // -----------------------------------------------------------------------
    // §5.4.4 Consume a style block's contents, as amended by CSS Nesting
    // -----------------------------------------------------------------------

    /**
     * Consumes a style rule's body; the opening brace has already been consumed.
     */
    private List<Node> consumeStyleBlock() {
        int mark = this.stack.mark();

        while (true) {
            switch (peek()) {
                // A stray ';' between declarations is legal and means nothing.
                case WHITESPACE, SEMICOLON -> advance();

                case COMMENT -> this.stack.push(consumeComment());

                case EOF, RIGHT_CURLY -> {
                    return this.stack.take(mark);
                }

                case AT_KEYWORD -> this.stack.push(consumeAtRule(true));

                default -> {
                    if (looksLikeDeclaration()) {
                        this.stack.pushIfPresent(consumeDeclaration());
                    }
                    else {
                        this.stack.pushIfPresent(consumeQualifiedRule(true));
                    }
                }
            }
        }
    }

    /**
     * Tells {@code color: red} from {@code a:hover { }}, which start identically.
     *
     * <p>Both are an identifier followed by a colon, so the colon settles nothing. What does
     * is which comes first at bracket depth zero: an opening brace means the rest was a
     * selector, and a {@code ;} or {@code }} or end of input means it was a value.
     *
     * <p>Custom properties short-circuit this. Their value is arbitrary token soup by
     * definition, braces included, so {@code --x: {a}} is a declaration however much it
     * looks like a rule.
     */
    private boolean looksLikeDeclaration() {
        if (peek() != TokenType.IDENT) {
            return false;
        }

        int colon = nextSignificant(this.at + 1);
        if (colon >= this.limit || this.tokens.type(colon) != TokenType.COLON) {
            return false;
        }

        if (this.tokens.valueStartsWith(this.at, "--")) {
            return true;
        }

        int depth = 0;

        for (int index = colon + 1; index < this.limit; index++) {
            TokenType type = this.tokens.type(index);
            if (depth == 0) {
                switch (type) {
                    case LEFT_CURLY -> {
                        return false;
                    }

                    case RIGHT_CURLY, SEMICOLON -> {
                        return true;
                    }

                    default -> {
                        // Nothing else settles it.
                    }
                }
            }

            depth = nextDepth(depth, type);
        }

        return true;
    }

    // -----------------------------------------------------------------------
    // §5.4.5 Consume a declaration
    // -----------------------------------------------------------------------

    private Declaration consumeDeclaration() {
        int start = this.at;
        String property = this.tokens.value(this.at);

        advance();
        skipTrivia();

        if (peek() != TokenType.COLON) {
            error("expected ':' after '" + property + "'", span());
            consumeBadDeclaration();
            return null;
        }

        advance();

        int value = this.stack.mark();
        int end = this.at;

        while (true) {
            TokenType type = peek();
            if (type == TokenType.EOF || type == TokenType.RIGHT_CURLY) {
                break;
            }

            if (type == TokenType.SEMICOLON) {
                advance();
                break;
            }

            this.stack.push(consumeComponentValue());

            if (type != TokenType.WHITESPACE) {
                // Trailing whitespace is trimmed out of the value, so letting it extend the
                // span would leave the two disagreeing about where the declaration ends.
                end = this.at;
            }
        }

        boolean important = stripImportant(value);
        return new Declaration(property, this.stack.takeTrimmed(value), important, this.tokens.packedSpan(start, end));
    }

    /**
     * §5.4.6 Consume the remnants of a bad declaration: everything up to the {@code ;} or the
     * {@code }} that ends it, with blocks skipped whole so a {@code ;} inside one does not
     * end the recovery early.
     *
     * @see <a href="https://www.w3.org/TR/css-syntax-3/#consume-declaration">CSS Syntax Level 3
     *      §5.4.6</a>
     */
    private void consumeBadDeclaration() {
        while (true) {
            TokenType type = peek();
            if (type == TokenType.EOF || type == TokenType.RIGHT_CURLY) {
                return;
            }

            if (type == TokenType.SEMICOLON) {
                advance();
                return;
            }

            consumeComponentValue();
        }
    }

    /**
     * Removes a trailing {@code !important} from a value.
     *
     * <p>Trailing in the loose sense: {@code red ! important /* why *}{@code /} still counts,
     * because whitespace and comments are allowed between the {@code !} and the keyword and
     * after it.
     *
     * @param value the mark the value being built starts at; the stack is truncated in place
     * @return whether an {@code !important} was found and removed
     */
    private boolean stripImportant(int value) {
        int keyword = lastSignificant(value, this.stack.mark() - 1);
        if (keyword < value
            || !(this.stack.get(keyword) instanceof IdentToken ident)
            || !Ascii.equalsIgnoreCase(ident.value(), "important")) {
            return false;
        }

        int bang = lastSignificant(value, keyword - 1);
        if (bang < value || !(this.stack.get(bang) instanceof DelimToken delim) || !delim.is('!')) {
            return false;
        }

        this.stack.truncate(bang);

        return true;
    }

    /**
     * The index of the last non-trivia value at or before {@code from}.
     *
     * @param value the mark the value starts at
     * @param from  the absolute index to search back from
     * @return the index, or one below {@code value} if there is no such value
     */
    private int lastSignificant(int value, int from) {
        int index = from;
        while (index >= value && ((ComponentValue) this.stack.get(index)).isTrivia()) {
            index--;
        }

        return index;
    }

    // -----------------------------------------------------------------------
    // Shared
    // -----------------------------------------------------------------------

    private Comment consumeComment() {
        Comment comment = commentAt(this.at);

        advance();

        return comment;
    }

    private void expectCloseCurly(int brace, String what) {
        if (peek() == TokenType.RIGHT_CURLY) {
            advance();
            return;
        }

        error("unclosed block in " + what, this.tokens.span(brace, this.at));
    }
}
