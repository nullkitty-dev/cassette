package dev.nullkitty.cassette.parser;

import java.util.List;

import dev.nullkitty.cassette.ast.AttributeCase;
import dev.nullkitty.cassette.ast.AttributeMatcher;
import dev.nullkitty.cassette.ast.AttributeSelector;
import dev.nullkitty.cassette.ast.ClassSelector;
import dev.nullkitty.cassette.ast.Combinator;
import dev.nullkitty.cassette.ast.CombinatorStep;
import dev.nullkitty.cassette.ast.ComplexSelector;
import dev.nullkitty.cassette.ast.CompoundSelector;
import dev.nullkitty.cassette.ast.IdSelector;
import dev.nullkitty.cassette.ast.NestingSelector;
import dev.nullkitty.cassette.ast.PseudoClassSelector;
import dev.nullkitty.cassette.ast.PseudoElementSelector;
import dev.nullkitty.cassette.ast.SelectorList;
import dev.nullkitty.cassette.ast.SimpleSelector;
import dev.nullkitty.cassette.ast.SourceSpan;
import dev.nullkitty.cassette.ast.TypeSelector;
import dev.nullkitty.cassette.diagnostics.Diagnostic;
import dev.nullkitty.cassette.lexer.TokenBuffer;
import dev.nullkitty.cassette.lexer.TokenType;
import dev.nullkitty.cassette.text.Ascii;

/**
 * Selectors Level 4, parsed straight from the token buffer over a rule's prelude.
 *
 * <p>Over tokens rather than over component values. A component-value list has already turned
 * {@code [href^="x"]} into a block and {@code .card} into a delimiter plus an identifier, so
 * rebuilding the selector from it means undoing work while having lost the one thing the grammar
 * leans on hardest: whether two tokens were written with nothing between them. {@code a|b} is a
 * namespaced type selector and {@code a |b} is not.
 *
 * <p>Failure is all-or-nothing per selector list, because an invalid selector makes its rule
 * invalid and there is nothing to salvage. The exception is the forgiving selector lists inside
 * {@code :is()} and {@code :where()}, where the spec says to drop only the bad alternatives.
 *
 * @see <a href="https://www.w3.org/TR/selectors-4/#grammar">Selectors Level 4 §16 Grammar</a>
 * @see <a href="https://www.w3.org/TR/selectors-4/#forgiving-selector">Selectors Level 4 §16.1
 *      &lt;forgiving-selector-list&gt;</a>
 */
final class SelectorParser extends TokenCursor {

    /**
     * The first thing that went wrong, held back so one bad selector reports once.
     */
    private Diagnostic failure;

    /**
     * Whether a selector here may open with a combinator, as {@code :has(> img)} does.
     *
     * <p>True inside {@code :has()}, and true for a nested rule's prelude: CSS Nesting makes
     * that a relative selector list too, which is what lets {@code > .title { }} sit inside
     * another rule. Everywhere else a leading combinator is a syntax error.
     */
    private final boolean relative;

    private SelectorParser(TokenBuffer tokens, //
                           List<Diagnostic> diagnostics,
                           NodeStack stack,
                           int from,
                           int to,
                           boolean relative) {
        super(tokens, diagnostics, stack, from, to);
        this.relative = relative;
    }

    /**
     * Parses the tokens in {@code [from, to)} as a selector list, reporting a diagnostic if
     * they are not one.
     *
     * @param tokens      the buffer to read
     * @param diagnostics where to report a malformed selector
     * @param stack       the enclosing parse's scratch stack, shared rather than per-prelude
     * @param from        index of the first prelude token
     * @param to          index one past the last prelude token
     * @param relative    whether a selector may open with a combinator, which a nested rule's
     *                    prelude may and a top-level rule's may not
     * @return the selector list, or {@code null} if the prelude is not a valid one
     */
    static SelectorList parse(TokenBuffer tokens,
                              List<Diagnostic> diagnostics,
                              NodeStack stack,
                              int from,
                              int to,
                              boolean relative) {
        SelectorParser parser = new SelectorParser(tokens, diagnostics, stack, from, to, relative);
        SelectorList selectors = parser.parseSelectorList();
        if (selectors == null) {
            diagnostics.add(parser.failureOr("invalid selector", tokens.span(from, to)));
        }

        return selectors;
    }

    private Diagnostic failureOr(String message, SourceSpan span) {
        return this.failure != null ? this.failure : Diagnostic.error(message, span);
    }

    // -----------------------------------------------------------------------
    // <selector-list> = <complex-selector>#
    // -----------------------------------------------------------------------

    /**
     * Unwinds the scratch stack when a selector is abandoned part-built.
     *
     * <p>The three {@code parse*} methods below each push as they go and each can give up half
     * way, which is the one way the stack discipline can break: alternatives left behind would
     * be taken by whatever list closes next. Every abandonment funnels through here rather than
     * through a {@code reset} at each {@code return null}, of which there are seven.
     */
    private <T> T unwindIfNull(int mark, T result) {
        if (result == null) {
            this.stack.reset(mark);
        }

        // Checked here, at each build, rather than once at the end of the parse: a leak does
        // not survive to be found later, because the enclosing list's take() would sweep the
        // stray values up as its own children and leave the stack looking balanced. The
        // symptom would be a neighbouring selector growing an alternative, one level up.
        assert this.stack.isEmpty(mark) : "selector left values on the stack";
        return result;
    }

    private SelectorList parseSelectorList() {
        int mark = this.stack.mark();
        return unwindIfNull(mark, parseSelectorListFrom(mark));
    }

    private SelectorList parseSelectorListFrom(int mark) {
        skipTrivia();
        int start = this.at;
        if (atEnd()) {
            return fail("empty selector", this.tokens.span(start, this.limit));
        }

        int end = start;

        while (true) {
            ComplexSelector complex = parseComplexSelector();
            if (complex == null) {
                return null;
            }

            this.stack.push(complex);

            end = this.at;

            skipTrivia();

            if (atEnd()) {
                break;
            }

            if (peek() != TokenType.COMMA) {
                return fail("expected ',' or end of selector but found " + describeCurrent(), span());
            }

            advance();
        }

        return new SelectorList(this.stack.take(mark), this.tokens.packedSpan(start, end));
    }

    // -----------------------------------------------------------------------
    // <complex-selector> = <compound-selector> [ <combinator>? <compound-selector> ]*
    // -----------------------------------------------------------------------

    private ComplexSelector parseComplexSelector() {
        int mark = this.stack.mark();
        return unwindIfNull(mark, parseComplexSelectorFrom(mark));
    }

    private ComplexSelector parseComplexSelectorFrom(int mark) {
        skipTrivia();

        int start = this.at;

        // A relative selector may open with a combinator, which relates its first compound
        // selector to the scoping element rather than to anything written before it.
        Combinator leading = Combinator.NONE;
        if (this.relative) {
            Combinator explicit = parseCombinator();
            if (explicit != null) {
                leading = explicit;
                skipTrivia();
            }
        }

        CompoundSelector first = parseCompoundSelector();
        if (first == null) {
            return null;
        }

        this.stack.push(new CombinatorStep(leading, first, this.tokens.packedSpan(start, this.at)));

        int end = this.at;

        while (true) {
            int resume = this.at;
            boolean spaced = skipWhitespaceSeen();
            skipComments();
            spaced |= skipWhitespaceSeen();

            int stepStart = this.at;
            Combinator combinator = parseCombinator();

            if (combinator == null) {
                // Only whitespace stands between the two, which is the descendant
                // combinator, unless there is nothing after it, in which case the
                // whitespace was trailing and belongs to whoever comes next.
                if (!spaced || atEnd() || peek() == TokenType.COMMA) {
                    this.at = resume;
                    break;
                }

                combinator = Combinator.DESCENDANT;
                stepStart = resume;
            }
            else {
                skipTrivia();
            }

            CompoundSelector next = parseCompoundSelector();
            if (next == null) {
                return null;
            }

            this.stack.push(new CombinatorStep(combinator, next, this.tokens.packedSpan(stepStart, this.at)));

            end = this.at;
        }

        return new ComplexSelector(this.stack.take(mark), this.tokens.packedSpan(start, end));
    }

    /**
     * The explicit combinators. Whitespace is handled by the caller, which is the only place
     * that knows whether there was any.
     *
     * @return the combinator, or {@code null} if the cursor is not on one
     */
    private Combinator parseCombinator() {
        if (isDelim('>')) {
            advance();
            return Combinator.CHILD;
        }

        if (isDelim('+')) {
            advance();
            return Combinator.NEXT_SIBLING;
        }

        if (isDelim('~')) {
            advance();
            return Combinator.SUBSEQUENT_SIBLING;
        }

        // Two adjacent pipes; one is the namespace separator, which belongs to a type
        // selector and is not a combinator at all.
        if (isDelim('|') && isDelim(1, '|') && isAdjacent(this.at)) {
            advance();
            advance();
            return Combinator.COLUMN;
        }

        return null;
    }

    // -----------------------------------------------------------------------
    // <compound-selector> = [ <type-selector>? <subclass-selector>* ]!
    // -----------------------------------------------------------------------

    private CompoundSelector parseCompoundSelector() {
        int mark = this.stack.mark();
        return unwindIfNull(mark, parseCompoundSelectorFrom(mark));
    }

    private CompoundSelector parseCompoundSelectorFrom(int mark) {
        skipComments();
        int start = this.at;

        SimpleSelector type = parseTypeSelector();
        if (type != null) {
            this.stack.push(type);
        }
        else if (this.failure != null) {
            return null;
        }

        while (true) {
            skipComments();
            SimpleSelector subclass = parseSubclassSelector();

            if (subclass == null) {
                if (this.failure != null) {
                    return null;
                }

                break;
            }

            this.stack.push(subclass);
        }

        if (this.stack.isEmpty(mark)) {
            return fail("expected a selector but found " + describeCurrent(), span());
        }

        return new CompoundSelector(this.stack.take(mark), this.tokens.packedSpan(start, this.at));
    }

    /**
     * {@code <type-selector> = <wq-name> | <ns-prefix>? '*'}
     *
     * @return the selector, or {@code null} if the cursor is not on a type selector, which
     *         is legal, since a compound selector may start with a subclass selector
     */
    private SimpleSelector parseTypeSelector() {
        int start = this.at;
        String namespace = parseNamespacePrefix();

        if (peek() == TokenType.IDENT) {
            String name = this.tokens.value(this.at);
            advance();
            return new TypeSelector(namespace, name, this.tokens.packedSpan(start, this.at));
        }

        if (isDelim('*')) {
            advance();
            return new TypeSelector(namespace, "*", this.tokens.packedSpan(start, this.at));
        }

        if (namespace != null) {
            return fail("expected an element name after the namespace separator", span());
        }

        return null;
    }

    /**
     * The {@code ns|} in {@code svg|circle}, {@code *|circle} and {@code |circle}.
     *
     * <p>Only consumes anything when a name follows, so a bare {@code a} is not mistaken for
     * a prefix and {@code a || b} keeps its column combinator.
     *
     * @return the prefix, an identifier, {@code *}, or {@code ""} for the explicit no-namespace
     *         form, or {@code null} if no prefix was written
     */
    private String parseNamespacePrefix() {
        if (isDelim('|') && isAdjacent(this.at) && startsName(1)) {
            advance();
            return "";
        }

        boolean named = peek() == TokenType.IDENT;
        if (!named && !isDelim('*')) {
            return null;
        }

        // `a|b` is a prefix; `a||b` is a type selector followed by a column combinator.
        if (!isDelim(1, '|') || isDelim(2, '|') || !isAdjacent(this.at) || !isAdjacent(this.at + 1) || !startsName(2)) {
            return null;
        }

        String prefix = named ? this.tokens.value(this.at) : "*";

        advance();
        advance();

        return prefix;
    }

    /**
     * Whether the token {@code offset} ahead could be an element or attribute name.
     */
    private boolean startsName(int offset) {
        return peek(offset) == TokenType.IDENT || isDelim(offset, '*');
    }

    // -----------------------------------------------------------------------
    // <subclass-selector> = <id> | <class> | <attribute> | <pseudo-class> | '&'
    // -----------------------------------------------------------------------

    private SimpleSelector parseSubclassSelector() {
        return switch (peek()) {
            case HASH -> {
                if (!this.tokens.isIdHash(this.at)) {
                    yield fail("'" + this.tokens.raw(this.at) + "' is not a valid id selector", span());
                }

                SimpleSelector selector = new IdSelector(this.tokens.value(this.at), span());

                advance();

                yield selector;
            }

            case LEFT_SQUARE -> parseAttributeSelector();

            case COLON -> parsePseudoSelector();

            case DELIM -> {
                if (isDelim('.')) {
                    yield parseClassSelector();
                }

                if (isDelim('&')) {
                    SimpleSelector selector = new NestingSelector(span());
                    advance();
                    yield selector;
                }

                yield null;
            }

            default -> null;
        };
    }

    private SimpleSelector parseClassSelector() {
        int start = this.at;

        if (peek(1) != TokenType.IDENT || !isAdjacent(this.at)) {
            return fail("expected a class name after '.'", this.tokens.span(start));
        }

        advance();

        String name = this.tokens.value(this.at);

        advance();

        return new ClassSelector(name, this.tokens.packedSpan(start, this.at));
    }

    /**
     * {@code '[' <wq-name> [ <attr-matcher> [ <string> | <ident> ] <attr-modifier>? ]? ']'}
     *
     * <p>Whitespace is allowed everywhere inside the brackets, unlike in the compound
     * selector around them.
     */
    private SimpleSelector parseAttributeSelector() {
        int start = this.at;

        advance();
        skipTrivia();

        String namespace = parseNamespacePrefix();

        if (peek() != TokenType.IDENT) {
            return fail("expected an attribute name but found " + describeCurrent(), span());
        }

        String name = this.tokens.value(this.at);

        advance();
        skipTrivia();

        if (peek() == TokenType.RIGHT_SQUARE) {
            advance();

            return new AttributeSelector(namespace,
                                         name,
                                         AttributeMatcher.PRESENT,
                                         null,
                                         AttributeCase.UNSPECIFIED,
                                         this.tokens.packedSpan(start, this.at));
        }

        AttributeMatcher matcher = parseAttributeMatcher();
        if (matcher == null) {
            return null;
        }

        skipTrivia();

        if (peek() != TokenType.STRING && peek() != TokenType.IDENT) {
            return fail("expected an attribute value but found " + describeCurrent(), span());
        }

        String value = this.tokens.value(this.at);

        advance();

        skipTrivia();

        AttributeCase caseMode = AttributeCase.UNSPECIFIED;
        if (peek() == TokenType.IDENT) {
            if (this.tokens.valueEqualsIgnoreCase(this.at, "i")) {
                caseMode = AttributeCase.INSENSITIVE;
            }
            else if (this.tokens.valueEqualsIgnoreCase(this.at, "s")) {
                caseMode = AttributeCase.SENSITIVE;
            }
            else {
                return fail("expected 'i' or 's' but found " + describeCurrent(), span());
            }

            advance();
            skipTrivia();
        }

        if (peek() != TokenType.RIGHT_SQUARE) {
            return fail("unclosed attribute selector", this.tokens.span(start, this.at));
        }

        advance();

        return new AttributeSelector(namespace, name, matcher, value, caseMode, this.tokens.packedSpan(start, this.at));
    }

    /**
     * The seven attribute matchers. All but {@code =} are two adjacent delimiters, because
     * the tokenizer has no reason to fuse {@code ^} and {@code =} into one token.
     */
    private AttributeMatcher parseAttributeMatcher() {
        if (isDelim('=')) {
            advance();
            return AttributeMatcher.EXACT;
        }

        // Not a lookup map: the switch is over a char, so an EnumMap does not apply and a
        // Map<Character, ?> would box every probe. A switch over dense small chars already
        // compiles to a bounds check and an indexed jump, which is the table.
        AttributeMatcher matcher = switch (this.tokens.delimCodePoint(this.at)) {
            case '~' -> AttributeMatcher.INCLUDES;
            case '|' -> AttributeMatcher.DASH;
            case '^' -> AttributeMatcher.PREFIX;
            case '$' -> AttributeMatcher.SUFFIX;
            case '*' -> AttributeMatcher.SUBSTRING;
            default -> null;
        };

        if (matcher == null || !isDelim(1, '=') || !isAdjacent(this.at)) {
            return fail("expected an attribute matcher but found " + describeCurrent(), span());
        }

        advance();
        advance();

        return matcher;
    }

    // -----------------------------------------------------------------------
    // Pseudo-classes and pseudo-elements
    // -----------------------------------------------------------------------

    private SimpleSelector parsePseudoSelector() {
        int start = this.at;

        advance();

        boolean doubleColon = peek() == TokenType.COLON;
        if (doubleColon) {
            advance();
        }

        skipComments();

        if (peek() == TokenType.IDENT) {
            String name = this.tokens.value(this.at);

            advance();

            SourceSpan whole = this.tokens.span(start, this.at);
            if (doubleColon) {
                return new PseudoElementSelector(name, true, false, List.of(), whole);
            }

            if (PseudoElementSelector.isLegacyName(name)) {
                return new PseudoElementSelector(name, false, false, List.of(), whole);
            }

            return PseudoClassSelector.plain(name, whole);
        }

        if (peek() == TokenType.FUNCTION) {
            return parseFunctionalPseudo(start, doubleColon);
        }

        return fail("expected a pseudo-class or pseudo-element name after ':'", span());
    }

    private SimpleSelector parseFunctionalPseudo(int start, //
                                                 boolean doubleColon) {
        int opener = this.at;
        String name = this.tokens.value(opener);
        int close = findMatchingClose(opener);
        int argFrom = opener + 1;
        int argTo = close;

        // Position past the whole function before building anything, so every exit below
        // leaves the cursor somewhere sane.
        this.at = Math.min(close + 1, this.limit);

        SourceSpan whole = this.tokens.span(start, this.at);
        if (close == this.limit) {
            return fail("unclosed '" + (doubleColon ? "::" : ":") + name + "()'", whole);
        }

        if (doubleColon) {
            return new PseudoElementSelector(name, true, true, componentValuesIn(argFrom, argTo), whole);
        }

        if (PseudoClassSelector.takesSelectorList(name)) {
            return functionalWithSelectors(name, argFrom, argTo, whole);
        }

        if (PseudoClassSelector.takesNthOfSelectorList(name)) {
            return nthWithOptionalOf(name, argFrom, argTo, whole);
        }

        return new PseudoClassSelector(name, true, componentValuesIn(argFrom, argTo), null, whole);
    }

    /**
     * {@code :is()}, {@code :where()}, {@code :not()} and {@code :has()}.
     *
     * <p>The first two take a <em>forgiving</em> selector list: an alternative the parser
     * cannot understand is dropped and the rest still work, which is what lets a stylesheet
     * use a selector some browsers do not know without losing the whole rule. {@code :not()}
     * and {@code :has()} are not forgiving, one bad alternative invalidates the lot.
     */
    private SimpleSelector functionalWithSelectors(String name, //
                                                   int from,
                                                   int to,
                                                   SourceSpan whole) {
        boolean has = Ascii.equalsIgnoreCase(name, "has");
        boolean forgiving = !has && !Ascii.equalsIgnoreCase(name, "not");
        SelectorList arguments = forgiving ? parseForgivingList(name, from, to) : parseNested(from, to, has);
        if (arguments == null) {
            return fail("invalid selector in ':" + name + "()'", whole);
        }

        return new PseudoClassSelector(name, true, List.of(), arguments, whole);
    }

    /**
     * {@code :nth-child(An+B [ of <selector-list> ]?)} and its last-child twin.
     *
     * <p>The {@code An+B} half stays unparsed; it is a micro-grammar with no bearing on
     * flattening, but the part after {@code of} is a real selector list, and specificity
     * depends on it.
     *
     * @see <a href="https://www.w3.org/TR/selectors-4/#child-index">Selectors Level 4 §13.3
     *      Child-indexed Pseudo-classes</a>
     */
    private SimpleSelector nthWithOptionalOf(String name, //
                                             int from,
                                             int to,
                                             SourceSpan whole) {
        int of = indexOfTopLevelOf(from, to);
        if (of < 0) {
            return new PseudoClassSelector(name, true, componentValuesIn(from, to), null, whole);
        }

        SelectorList arguments = parseNested(of + 1, to, false);
        if (arguments == null) {
            return fail("invalid selector after 'of' in ':" + name + "()'", whole);
        }

        return new PseudoClassSelector(name, true, componentValuesIn(from, of), arguments, whole);
    }

    /**
     * The index of a bare {@code of} identifier at bracket depth zero, or -1.
     */
    private int indexOfTopLevelOf(int from, int to) {
        int depth = 0;

        for (int index = from; index < to; index++) {
            switch (this.tokens.type(index)) {
                case FUNCTION, LEFT_PAREN, LEFT_SQUARE, LEFT_CURLY -> depth++;
                case RIGHT_PAREN, RIGHT_SQUARE, RIGHT_CURLY -> depth--;
                case IDENT -> {
                    if (depth == 0 && this.tokens.valueEqualsIgnoreCase(index, "of")) {
                        return index;
                    }
                }
                default -> {
                    // Nothing else can be the keyword or change depth.
                }
            }
        }

        return -1;
    }

    /**
     * Parses a sub-range as a selector list, propagating its failure to this parser.
     */
    private SelectorList parseNested(int from, //
                                     int to,
                                     boolean relativeList) {
        SelectorParser inner = new SelectorParser(this.tokens, this.diagnostics, this.stack, from, to, relativeList);

        SelectorList selectors = inner.parseSelectorList();
        if (selectors == null && this.failure == null) {
            this.failure = inner.failure;
        }

        return selectors;
    }

    /**
     * Splits a sub-range on top-level commas and keeps the alternatives that parse.
     *
     * <p>Each dropped alternative is a warning rather than an error: the rule survives, and
     * a silent drop is exactly the kind of thing someone debugging a stylesheet needs told.
     */
    private SelectorList parseForgivingList(String name, //
                                            int from,
                                            int to) {
        int mark = this.stack.mark();
        int start = from;
        int depth = 0;

        for (int index = from; index <= to; index++) {
            boolean split = index == to;
            if (!split) {
                switch (this.tokens.type(index)) {
                    case FUNCTION, LEFT_PAREN, LEFT_SQUARE, LEFT_CURLY -> depth++;
                    case RIGHT_PAREN, RIGHT_SQUARE, RIGHT_CURLY -> depth--;
                    case COMMA -> split = depth == 0;
                    default -> {
                        // Nothing else separates alternatives.
                    }
                }
            }

            if (!split) {
                continue;
            }

            SelectorList one =
                new SelectorParser(this.tokens, this.diagnostics, this.stack, start, index, false).parseSelectorList();

            if (one == null) {
                warning("ignoring an unparseable selector inside ':" + name + "()'", this.tokens.span(start, index));
            }
            else {
                for (ComplexSelector alternative : one.selectors()) {
                    this.stack.push(alternative);
                }
            }

            start = index + 1;
        }

        // A forgiving list that kept nothing is still a valid, if useless, argument.
        return new SelectorList(this.stack.take(mark), this.tokens.packedSpan(from, to));
    }

    /**
     * Records the first thing that went wrong and stops.
     *
     * <p>Generic so it can be returned directly from methods of any selector type; the value
     * is always {@code null}.
     */
    private <T> T fail(String message, SourceSpan span) {
        if (this.failure == null) {
            this.failure = Diagnostic.error(message, span);
        }

        return null;
    }
}
