package dev.nullkitty.cassette.serializer;

import java.util.List;
import java.util.function.Consumer;

import dev.nullkitty.cassette.ast.AtKeywordToken;
import dev.nullkitty.cassette.ast.AtRule;
import dev.nullkitty.cassette.ast.AttributeCase;
import dev.nullkitty.cassette.ast.AttributeMatcher;
import dev.nullkitty.cassette.ast.AttributeSelector;
import dev.nullkitty.cassette.ast.BadStringToken;
import dev.nullkitty.cassette.ast.BadUrlToken;
import dev.nullkitty.cassette.ast.ClassSelector;
import dev.nullkitty.cassette.ast.Combinator;
import dev.nullkitty.cassette.ast.CombinatorStep;
import dev.nullkitty.cassette.ast.Comment;
import dev.nullkitty.cassette.ast.ComplexSelector;
import dev.nullkitty.cassette.ast.ComponentValue;
import dev.nullkitty.cassette.ast.CompoundSelector;
import dev.nullkitty.cassette.ast.ConditionalGroupRule;
import dev.nullkitty.cassette.ast.Declaration;
import dev.nullkitty.cassette.ast.DelimToken;
import dev.nullkitty.cassette.ast.DimensionToken;
import dev.nullkitty.cassette.ast.FunctionValue;
import dev.nullkitty.cassette.ast.HashToken;
import dev.nullkitty.cassette.ast.IdSelector;
import dev.nullkitty.cassette.ast.IdentToken;
import dev.nullkitty.cassette.ast.NestingSelector;
import dev.nullkitty.cassette.ast.Node;
import dev.nullkitty.cassette.ast.NumberToken;
import dev.nullkitty.cassette.ast.PercentageToken;
import dev.nullkitty.cassette.ast.PseudoClassSelector;
import dev.nullkitty.cassette.ast.PseudoElementSelector;
import dev.nullkitty.cassette.ast.Punctuation;
import dev.nullkitty.cassette.ast.Rule;
import dev.nullkitty.cassette.ast.SelectorList;
import dev.nullkitty.cassette.ast.SimpleBlock;
import dev.nullkitty.cassette.ast.SimpleSelector;
import dev.nullkitty.cassette.ast.StringToken;
import dev.nullkitty.cassette.ast.StyleRule;
import dev.nullkitty.cassette.ast.Stylesheet;
import dev.nullkitty.cassette.ast.TypeSelector;
import dev.nullkitty.cassette.ast.UrlToken;
import dev.nullkitty.cassette.ast.WhitespaceToken;
import dev.nullkitty.cassette.diagnostics.Diagnostic;
import dev.nullkitty.cassette.text.Ascii;

/**
 * The tree, back to text.
 *
 * <p>One writer per serialization, holding the buffer and the only two pieces of genuine state:
 * how deep the indentation is, and whether a dropped comment left two tokens that would fuse if
 * written next to each other.
 *
 * <p>Whitespace is the whole of the difference between the two formatting modes, so it is decided
 * in one place per construct rather than by threading a "pretty" flag through the string literals.
 */
final class CssWriter {

    private static final String INDENT = "  ";

    private final StringBuilder     out = new StringBuilder();
    private final SerializerOptions options;
    private final boolean           minified;

    /**
     * Set when a comment was dropped between two values, and cleared by the next token
     * written.
     *
     * <p>{@code a/*x*}{@code /b} is two identifiers. Dropping the comment and writing
     * {@code ab} would make it one, so a space takes its place, but only where the two
     * would fuse, since {@code a/*x*}{@code /,b} needs nothing.
     */
    private boolean pendingSeparator;

    /**
     * Where a node the writer cannot spell is reported.
     *
     * <p>Only {@link #urlFunction} reports today, and most callers pass a sink that discards,
     * so this is a push channel rather than a list the writer hands back.
     */
    private final Consumer<Diagnostic> diagnostics;

    /**
     * Where each mapped construct was written, or {@code null} when no map is being built.
     *
     * <p>The field never changes within a serialization, so every recording site is a branch on
     * a constant and the maps-off path writes nothing extra. Which constructs are recorded is
     * {@link #record}; what happens to a recording the writer takes back is {@link #rollback}.
     */
    private final Mappings mappings;

    CssWriter(SerializerOptions options, Consumer<Diagnostic> diagnostics) {
        this(options, diagnostics, null);
    }

    CssWriter(SerializerOptions options, Consumer<Diagnostic> diagnostics, Mappings mappings) {
        this.options = options;
        this.minified = options.isMinified();
        this.diagnostics = diagnostics;
        this.mappings = mappings;
    }

    /**
     * Writes any node, and everything under it.
     *
     * @param node the node to serialize
     * @return the CSS text
     */
    String write(Node node) {
        node(node, 0);

        return this.out.toString();
    }

    /**
     * Writes a node that is not a whole stylesheet, without the trailing newline the constructs
     * that end in one would leave.
     *
     * <p>Trailing whitespace goes through {@link #rollback} rather than through
     * {@link String#stripTrailing()} on the result, so that a mapping recorded inside what is
     * removed goes with it. Nothing records that close to the end today, and a path added later
     * cannot break this.
     *
     * @param node the node to serialize
     * @return the CSS text, with no trailing whitespace
     */
    String writeFragment(Node node) {
        node(node, 0);

        int end = this.out.length();

        // Character.isWhitespace is what String.stripTrailing tests, and no whitespace code
        // point is supplementary, so scanning chars rather than code points is the same answer.
        while (end > 0 && Character.isWhitespace(this.out.charAt(end - 1))) {
            end--;
        }

        rollback(end);

        return this.out.toString();
    }

    /**
     * Takes the output back to {@code mark}, and the mappings with it.
     *
     * <p>The writer commits output before it knows whether what follows produces any text, and has
     * to take it back when it does not. That happens in six places, all of them the
     * prefix-before-nothing problem in a different guise. A mapping recorded inside a region that
     * is taken back would point past the end of the output, or into text that means something
     * else, so the two truncations belong in one place rather than at six sites that are supposed
     * to remember each other.
     *
     * <p>No mapping is dropped here today. Every region taken back holds component values or a
     * single punctuation mark, and nothing at that granularity is recorded: 283,700 rollbacks
     * over the three corpus entries and 200,000 differential-harness samples removed not one
     * mapping. A recording site added at a finer granularity would land inside these regions,
     * and needs to know nothing about them.
     */
    private void rollback(int mark) {
        this.out.setLength(mark);

        if (this.mappings == null) {
            return;
        }

        this.mappings.truncateFrom(mark);
    }

    /**
     * Records that {@code node} starts at the next character to be written.
     *
     * <p>Called at the three granularities a map is built at, which are a rule's prelude
     * alternatives, a declaration and an at-rule, and nowhere finer. That is what browser devtools
     * consume for CSS, and anything finer costs an entry in each array, ten characters of encoded
     * output and a step of the post-pass, for resolution nothing asks for.
     */
    private void record(Node node) {
        if (this.mappings == null) {
            return;
        }

        this.mappings.add(this.out.length(), node.packedSpan());
    }

    private void node(Node node, int depth) {
        switch (node) {
            case Stylesheet stylesheet -> children(stylesheet.children(), depth);
            case StyleRule rule -> styleRule(rule, depth);
            case ConditionalGroupRule rule -> groupRule(rule, depth);
            case AtRule rule -> atRule(rule, depth);
            case Declaration declaration -> declaration(declaration, depth, false);
            case SelectorList list -> selectorList(list, depth, false);
            case ComplexSelector selector -> complex(selector);
            case CompoundSelector selector -> compound(selector);
            case SimpleSelector selector -> simple(selector);
            case Comment comment -> comment(comment, depth);
            case ComponentValue value -> value(value);
        }
    }

    // -----------------------------------------------------------------------
    // Rules
    // -----------------------------------------------------------------------

    /**
     * A rule list or a style block: the two are the same problem, differing only in what
     * their children may be.
     *
     * <p>A blank line follows a rule, so rules stand apart from each other and from the
     * declarations around them, and nothing else introduces one.
     */
    private void children(List<Node> items, int depth) {
        Node previous = null;

        for (Node item : items) {
            if (this.minified && item instanceof Comment comment && !isBangComment(comment)) {
                continue;
            }

            if (!this.minified && previous instanceof Rule) {
                this.out.append('\n');
            }

            switch (item) {
                case Declaration declaration -> declaration(declaration, depth, true);
                case Comment comment -> comment(comment, depth);
                default -> node(item, depth);
            }

            previous = item;
        }
    }

    private void styleRule(StyleRule rule, int depth) {
        indent(depth);
        selectorList(rule.selectors(), depth, true);
        openBlock();
        children(rule.body(), depth + 1);
        closeBlock(depth, true);
    }

    private void groupRule(ConditionalGroupRule rule, int depth) {
        indent(depth);
        record(rule);
        atKeyword(rule.name(), rule.prelude());
        openBlock();
        children(rule.body(), depth + 1);
        closeBlock(depth, true);
    }

    private void atRule(AtRule rule, int depth) {
        indent(depth);
        record(rule);
        atKeyword(rule.name(), rule.prelude());

        if (rule.isStatement()) {
            this.out.append(';');
            newline();
            return;
        }

        if (rule.block().isEmpty()) {
            emptyBlock();
            return;
        }

        int mark = this.out.length();

        openBlock();

        int afterOpen = this.out.length();

        opaqueBlock(rule.block(), depth + 1);

        if (this.out.length() == afterOpen) {
            // A block of nothing but wreckage writes nothing, and an at-rule spells that
            // '{}' rather than as a brace, a newline and a brace, which is what re-parsing
            // this output would produce, so the two have to agree here.
            rollback(mark);
            emptyBlock();
            return;
        }

        // Not a declaration list: a ';' in there is a token the spec preserves, and eating
        // the last one would eat another on every round trip.
        closeBlock(depth, false);
    }

    /**
     * An at-rule whose block holds nothing the writer can emit.
     */
    private void emptyBlock() {
        this.out.append(this.minified ? "{}" : " {}");
        newline();
    }

    private void atKeyword(String name, //
                           List<ComponentValue> prelude) {
        this.out.append('@');

        Escaping.ident(this.out, name, encoding());

        if (prelude.isEmpty()) {
            return;
        }

        // Minified, a prelude opening on '(' needs no separator at all: '(' cannot continue
        // the ident just written, so '@media(min-width:1px)' re-parses to this same rule.
        // Worth the byte because a stylesheet's media queries are usually all of this shape.
        //
        // Only '(', and the two near misses are why. '@import url(x)' would become the single
        // at-keyword '@importurl'; any ident, dimension or hash first token is the same trap.
        // And a StringToken looks safe by the same argument but is not, because '@charset' is
        // matched as a BYTE SEQUENCE before any of this parses (CSS Syntax 3.2), and that
        // sequence carries exactly one space -- '@charset"utf-8"' stops being a charset rule.
        if (this.minified && prelude.get(0) instanceof SimpleBlock block && block.open() == '(') {
            values(prelude);
            return;
        }

        // The separating space goes in before the prelude is known to produce anything, and a
        // prelude of nothing but wreckage produces nothing at all. Left behind, it re-parses
        // as no prelude and a different byte count, so '@a ;' would never be a fixed point.
        int mark = this.out.length();
        this.out.append(' ');

        int afterSpace = this.out.length();

        values(prelude);

        if (this.out.length() == afterSpace) {
            rollback(mark);
        }
    }

    /**
     * The block of an at-rule this parser keeps opaque: {@code @font-face},
     * {@code @keyframes}, anything it has no grammar for.
     *
     * <p>There is no structure to indent by, so the one cue the token stream does give, a
     * top-level {@code ;} or {@code { ... }} ends something, is used to break lines.
     * Without it a whole {@code @keyframes} would come back as one line, since the AST does
     * not record the author's newlines.
     */
    private void opaqueBlock(List<ComponentValue> block, //
                             int depth) {
        if (this.minified) {
            values(block);
            return;
        }

        boolean lineStart = true;
        boolean spaced = false;

        for (ComponentValue value : block) {
            if (value instanceof WhitespaceToken) {
                spaced = true;
                continue;
            }

            int mark = this.out.length();
            boolean wasLineStart = lineStart;

            if (lineStart) {
                indent(depth);
                lineStart = false;
            }
            else if (spaced && !isCloser(value)) {
                this.out.append(' ');
            }

            int afterPrefix = this.out.length();
            value(value);

            if (this.out.length() == afterPrefix) {
                // A bad-string or bad-url token writes nothing, and the indent or separator
                // written in front of it would be left behind, as a blank line when it was
                // an indent, which re-parses to nothing and breaks idempotence.
                rollback(mark);
                lineStart = wasLineStart;
                continue;
            }

            spaced = false;

            if (endsALine(value)) {
                this.out.append('\n');
                lineStart = true;
            }
        }

        if (!lineStart) {
            this.out.append('\n');
        }
    }

    private static boolean isCloser(ComponentValue value) {
        return value instanceof Punctuation punctuation
               && (punctuation.kind() == Punctuation.Kind.SEMICOLON || punctuation.kind() == Punctuation.Kind.COMMA);
    }

    private static boolean endsALine(ComponentValue value) {
        if (value instanceof Punctuation punctuation) {
            return punctuation.kind() == Punctuation.Kind.SEMICOLON;
        }

        return value instanceof SimpleBlock block && block.open() == '{';
    }

    private void declaration(Declaration declaration, int depth, boolean terminate) {
        indent(depth);
        record(declaration);

        Escaping.ident(this.out, declaration.property(), encoding());
        this.out.append(':');

        if (!declaration.value().isEmpty()) {
            // A value of nothing but bad tokens writes nothing, and the space before it
            // would then be all that was left of it.
            int beforeSpace = this.out.length();

            superfluousSpace();

            int afterSpace = this.out.length();

            values(declaration.value());

            if (this.out.length() == afterSpace) {
                rollback(beforeSpace);
            }
        }

        if (declaration.important()) {
            superfluousSpace();
            this.out.append("!important");
        }

        if (terminate) {
            this.out.append(';');
        }

        newline();
    }

    private void comment(Comment comment, int depth) {
        if (this.minified && !isBangComment(comment)) {
            return;
        }

        indent(depth);
        this.out.append("/*").append(comment.text()).append("*/");

        newline();
    }

    /**
     * Whether a comment survives {@link Formatting#MINIFIED}.
     *
     * <p>{@code /*!} is the convention every minifier honours, and what it carries in
     * practice is a licence header: a third-party stylesheet that has to ship its terms
     * writes them that way, so dropping it changes what the file is licensed under rather
     * than merely making it smaller. Font Awesome, Bootstrap and Normalize all rely on it.
     *
     * <p>Nothing else is kept. A comment a build tool left for itself is exactly what
     * minifying is for, and a caller who wants the banner gone too can strip it from the
     * tree before serializing.
     */
    private static boolean isBangComment(Comment comment) {
        return comment.text().startsWith("!");
    }

    // -----------------------------------------------------------------------
    // Component values
    // -----------------------------------------------------------------------

    /**
     * A component-value list: a declaration's value, an at-rule's prelude, a function's
     * arguments, a block's contents.
     *
     * <p>Whitespace at either edge is dropped in both modes; a declaration value arrives
     * already trimmed, and inside a function or a block the edges are the brackets, which
     * need no separating from anything.
     */
    private void values(List<ComponentValue> values) {
        int to = values.size();
        boolean wrote = false;

        for (int index = 0; index < to; index++) {
            ComponentValue value = values.get(index);
            if (value instanceof WhitespaceToken) {
                if (!wrote || !writesAnythingAfter(values, index, to)) {
                    // Nothing on one side of it, because a bad token writes nothing: this
                    // space would be separating a value from its own delimiter.
                    continue;
                }

                if (!this.minified || keepsWhitespace(values, index, to)) {
                    // Mandatory, not decorative: '1px solid' is two values because of it.
                    separator();
                }

                continue;
            }

            if (this.minified && value instanceof Comment comment && !isBangComment(comment)) {
                this.pendingSeparator = true;
                continue;
            }

            int before = this.out.length();

            value(value);

            wrote |= this.out.length() > before;
        }

        this.pendingSeparator = false;
    }

    /**
     * Whether a {@code url()} function has no spelling that reads back as itself.
     *
     * <p>{@code url} is the one name whose contents decide how the whole token lexes. §4.3.4
     * emits a function token for {@code url(} only when a quote is what follows the paren;
     * otherwise §4.3.6 lexes a url-token instead, whose body admits no whitespace, quotes or
     * parentheses and which swallows everything up to the next {@code )}. So writing
     * {@code url(} in front of contents that do not open with a quote produces a bad-url,
     * however faithful the contents themselves are.
     *
     * <p>Escaping the name does not sidestep it: §4.3.4 matches "url" against the ident
     * sequence's <em>decoded</em> value, so {@code \75 rl(} is a url-token too.
     *
     * <p>The test is therefore purely structural. The decision has to be readable off the tree
     * rather than off the output, because every forward scan below needs it for a value it has not
     * written yet. The first argument that is not whitespace settles it, since whitespace at the
     * leading edge is dropped in both modes: a string writes the quote the tokenizer needs to see,
     * and anything else does not.
     *
     * <p>This gives up a url whose contents happen to spell a legal url-token body:
     * {@code url("} plus a newline plus {@code foo)} could be written as {@code url(foo)} rather
     * than dropped. Recovering that case means deciding from rendered text, which the scans
     * cannot see.
     *
     * @see <a href="https://www.w3.org/TR/css-syntax-3/#consume-ident-like-token">CSS Syntax Level 3
     *      §4.3.4</a>
     * @see <a href="https://www.w3.org/TR/css-syntax-3/#consume-url-token">CSS Syntax Level 3 §4.3.6</a>
     */
    private static boolean isUnspellableUrl(FunctionValue function) {
        if (!Ascii.equalsIgnoreCase(function.name(), "url")) {
            return false;
        }

        for (ComponentValue argument : function.arguments()) {
            if (argument instanceof WhitespaceToken) {
                continue;
            }

            return !(argument instanceof StringToken);
        }

        // 'url()' holding nothing at all is spelled by the empty url-token, which reads back
        // as itself. Only a hand-built tree has one: the tokenizer lexes the source form as a
        // UrlToken, never as a function.
        return false;
    }

    /**
     * Whether this value produces no text at all.
     *
     * <p>The wreckage tokens, and the one delimiter with nothing that reads back as itself.
     * Every forward scan over a value list has to look through these: they are present in the
     * tree but absent from the output, so a decision made about "the next value" that stops on
     * one of them is a decision about something the reader will never see.
     */
    private static boolean writesNothing(ComponentValue value) {
        return value instanceof BadStringToken
               || value instanceof BadUrlToken
               || (value instanceof DelimToken delim && delim.is('\\'))
               || (value instanceof FunctionValue function && isUnspellableUrl(function));
    }

    /**
     * Whether anything after {@code index} produces output at all.
     */
    private boolean writesAnythingAfter(List<ComponentValue> values, int index, int to) {
        for (int next = index + 1; next < to; next++) {
            ComponentValue value = values.get(next);
            if (value instanceof WhitespaceToken || writesNothing(value)) {
                continue;
            }

            if (this.minified && value instanceof Comment comment && !isBangComment(comment)) {
                continue;
            }

            return true;
        }

        return false;
    }

    /**
     * Whether a whitespace token still separates anything once minified.
     *
     * <p>Only the cases where the grammar cannot possibly need it: next to a bracket that
     * opens, or before one of the marks that closes. Everything else stays, because
     * {@code 1px solid} and {@code calc(1px + 2px)} both mean less without it.
     */
    private boolean keepsWhitespace(List<ComponentValue> values, int index, int to) {
        char previous = lastChar();
        if (previous == ','
            || previous == '('
            || previous == '['
            || previous == '{'
            || previous == ']'
            || previous == '}'
            || previous == ':'
            || previous == ';') {
            return false;
        }

        for (int next = index + 1; next < to; next++) {
            ComponentValue value = values.get(next);
            if (value instanceof WhitespaceToken || value instanceof Comment || writesNothing(value)) {
                continue;
            }

            if (value instanceof Punctuation punctuation) {
                return switch (punctuation.kind()) {
                    case COMMA, SEMICOLON, RIGHT_PAREN, RIGHT_SQUARE, RIGHT_CURLY -> false;
                    default -> true;
                };
            }

            // A '{' or '[' cannot continue whatever precedes it, so nothing separates them.
            // A '(' can: 'and (' is two tokens and 'and(' is one function token.
            return !(value instanceof SimpleBlock block) || block.open() == '(';
        }

        return false;
    }

    private void value(ComponentValue value) {
        switch (value) {
            case IdentToken token -> {
                separate(firstOf(token.value()));
                Escaping.ident(this.out, token.value(), encoding());
            }

            case AtKeywordToken token -> {
                separate('@');
                this.out.append('@');
                Escaping.ident(this.out, token.name(), encoding());
            }

            case HashToken token -> {
                separate('#');
                this.out.append('#');
                if (token.id()) {
                    Escaping.ident(this.out, token.value(), encoding());
                }
                else {
                    Escaping.name(this.out, token.value(), encoding());
                }
            }

            case StringToken token -> {
                separate('"');
                Escaping.string(this.out, token.value(), encoding());
            }

            case UrlToken token -> {
                separate('u');
                Escaping.url(this.out, token.value(), encoding());
            }

            case NumberToken token -> {
                separate(firstOf(token.rawText()));
                this.out.append(token.rawText());
            }

            case PercentageToken token -> {
                separate(firstOf(token.rawText()));
                this.out.append(token.rawText()).append('%');
            }

            case DimensionToken token -> {
                separate(firstOf(token.rawText()));
                this.out.append(token.rawText());
                Escaping.unit(this.out, token.unit(), encoding());
            }

            case DelimToken token when writesNothing(token) -> this.pendingSeparator = true;

            case DelimToken token -> {
                separate(firstOf(token.text()));
                this.out.append(token.text());
            }

            case Punctuation token -> {
                separate(firstOf(token.text()));
                this.out.append(token.text());
            }

            case Comment token -> {
                separate('/');
                this.out.append("/*").append(token.text()).append("*/");
            }

            case WhitespaceToken ignored -> separator();

            case FunctionValue function when isUnspellableUrl(function) -> {
                this.pendingSeparator = true;
                this.diagnostics.accept(Diagnostic.warning("url() contents cannot be written as a url-token, and were dropped",
                                                           function.span()));
            }

            case FunctionValue function -> {
                separate(firstOf(function.name()));
                Escaping.ident(this.out, function.name(), encoding());
                this.out.append('(');
                values(function.arguments());
                this.out.append(')');
            }

            case SimpleBlock block -> {
                separate(block.open());
                this.out.append(block.open());
                values(block.contents());
                this.out.append(block.close());
            }

            // A bad-string or bad-url token is the wreckage of a construct that never
            // parsed. There is nothing to write that would read back as itself, and writing
            // the source text verbatim would reproduce the error. A lone '\' delimiter, above,
            // is the same case: §4.3.8 says a backslash before a newline does not start an
            // escape, and written verbatim it would start one against whatever comes next.
            case BadStringToken ignored -> this.pendingSeparator = true;

            case BadUrlToken ignored -> this.pendingSeparator = true;
        }
    }

    // -----------------------------------------------------------------------
    // Selectors
    // -----------------------------------------------------------------------

    /**
     * @param prelude whether this list is a rule's own prelude, which two things ask and
     *                neither can answer for itself: alternatives go on their own lines there
     *                and not inside a {@code :is()} argument, and each alternative is a mapping
     *                point there and not inside one
     */
    private void selectorList(SelectorList list, int depth, boolean prelude) {
        List<ComplexSelector> selectors = list.selectors();

        for (int index = 0; index < selectors.size(); index++) {
            if (index > 0) {
                this.out.append(',');

                if (this.minified) {
                    // nothing
                }
                else if (prelude) {
                    this.out.append('\n');
                    indent(depth);
                }
                else {
                    this.out.append(' ');
                }
            }

            ComplexSelector selector = selectors.get(index);
            if (prelude) {
                record(selector);
            }

            complex(selector);
        }
    }

    private void complex(ComplexSelector selector) {
        List<CombinatorStep> steps = selector.steps();
        for (int index = 0; index < steps.size(); index++) {
            CombinatorStep step = steps.get(index);
            combinator(step.combinator(), index == 0);
            compound(step.compound());
        }
    }

    private void combinator(Combinator combinator, boolean leading) {
        switch (combinator) {
            case NONE -> {
                // Nothing precedes the first step of an absolute selector.
            }

            case DESCENDANT -> this.out.append(' ');

            default -> {
                if (this.minified) {
                    this.out.append(combinator.text());
                }
                else if (leading) {
                    // A relative selector, as :has(> img) is: nothing to its left to space off.
                    this.out.append(combinator.text()).append(' ');
                }
                else {
                    this.out.append(' ').append(combinator.text()).append(' ');
                }
            }
        }
    }

    private void compound(CompoundSelector compound) {
        for (SimpleSelector simple : compound.simples()) {
            simple(simple);
        }
    }

    private void simple(SimpleSelector selector) {
        switch (selector) {
            case TypeSelector type -> {
                namespace(type.namespace());
                if (type.isUniversal()) {
                    this.out.append('*');
                }
                else {
                    Escaping.ident(this.out, type.name(), encoding());
                }
            }

            case ClassSelector selected -> {
                this.out.append('.');
                Escaping.ident(this.out, selected.name(), encoding());
            }

            case IdSelector selected -> {
                this.out.append('#');
                Escaping.ident(this.out, selected.name(), encoding());
            }

            case NestingSelector ignored -> this.out.append('&');

            case AttributeSelector attribute -> attribute(attribute);

            case PseudoClassSelector pseudo -> pseudoClass(pseudo);

            case PseudoElementSelector pseudo -> {
                this.out.append(pseudo.doubleColon() ? "::" : ":");
                Escaping.ident(this.out, pseudo.name(), encoding());
                if (pseudo.functional()) {
                    this.out.append('(');
                    values(pseudo.arguments());
                    this.out.append(')');
                }
            }
        }
    }

    private void namespace(String namespace) {
        if (namespace == null) {
            return;
        }

        if (!namespace.isEmpty() && !"*".equals(namespace)) {
            Escaping.ident(this.out, namespace, encoding());
        }
        else {
            this.out.append(namespace);
        }

        this.out.append('|');
    }

    private void attribute(AttributeSelector attribute) {
        this.out.append('[');
        namespace(attribute.namespace());
        Escaping.ident(this.out, attribute.name(), encoding());

        if (attribute.matcher() != AttributeMatcher.PRESENT) {
            this.out.append(attribute.matcher().text());

            // Always quoted: whether the author wrote a string or an identifier is not
            // recorded, and a string is legal wherever either is.
            Escaping.string(this.out, attribute.value(), encoding());
        }

        if (attribute.caseMode() != AttributeCase.UNSPECIFIED) {
            superfluousSpace();
            this.out.append(attribute.caseMode().text());
        }

        this.out.append(']');
    }

    private void pseudoClass(PseudoClassSelector pseudo) {
        this.out.append(':');
        Escaping.ident(this.out, pseudo.name(), encoding());

        if (!pseudo.functional()) {
            return;
        }

        this.out.append('(');

        if (pseudo.selectors() == null) {
            values(pseudo.arguments());
        }
        else {
            if (!pseudo.arguments().isEmpty()) {
                // :nth-child(2n+1 of .a): the An+B half stayed unparsed, the selector half
                // did not, and the keyword between them needs its spaces.
                values(pseudo.arguments());
                this.out.append(" of ");
            }

            selectorList(pseudo.selectors(), 0, false);
        }

        this.out.append(')');
    }

    // -----------------------------------------------------------------------
    // Output primitives
    // -----------------------------------------------------------------------
    //
    // Every primitive below branches on `minified`, and that is deliberately not hoisted
    // into a lambda chosen once in the constructor. The field is final, so each test is a
    // load and a perfectly predicted branch; a lambda would replace it with a virtual call
    // through a call site that sees two implementations, and scatter each primitive's two
    // halves away from each other.

    private void openBlock() {
        this.out.append(this.minified ? "{" : " {\n");
    }

    /**
     * @param dropTrailingSemicolon whether a trailing {@code ;} is a declaration separator
     *                              the grammar does not need, rather than a token of its own
     */
    private void closeBlock(int depth, boolean dropTrailingSemicolon) {
        if (this.minified) {
            if (dropTrailingSemicolon && lastChar() == ';') {
                rollback(this.out.length() - 1);
            }

            this.out.append('}');
            return;
        }

        indent(depth);
        this.out.append("}\n");
    }

    /**
     * Appends {@code depth} levels of indent, a level at a time.
     *
     * <p>{@code INDENT.repeat(depth)} allocates a string per call at every depth above one,
     * which is every declaration inside an {@code @media}. Appending in a loop allocates nothing,
     * and the depths reached here are small enough that the repeated calls cost no more than the
     * one bulk copy they replace.
     */
    private void indent(int depth) {
        if (this.minified) {
            return;
        }

        for (int level = 0; level < depth; level++) {
            this.out.append(INDENT);
        }
    }

    private void newline() {
        if (!this.minified) {
            this.out.append('\n');
        }
    }

    /**
     * A space that is there to be read, and that minification drops.
     */
    private void superfluousSpace() {
        if (!this.minified) {
            this.out.append(' ');
        }

        this.pendingSeparator = false;
    }

    /**
     * A space the grammar needs, which survives minification.
     *
     * <p>Never two in a row. The tokenizer collapses a run of whitespace into one token, so a
     * second space could only come from a bad token between them writing nothing, and the output
     * would no longer be a fixed point of its own round trip.
     */
    private void separator() {
        if (lastChar() != ' ') {
            this.out.append(' ');
        }

        this.pendingSeparator = false;
    }

    /**
     * Puts a space where a dropped comment was, if the two sides would otherwise fuse.
     */
    private void separate(char first) {
        if (!this.pendingSeparator) {
            return;
        }

        this.pendingSeparator = false;

        if (fuses(lastChar()) && fuses(first)) {
            this.out.append(' ');
        }
    }

    /**
     * Whether a character can continue an identifier, a number or a hash.
     *
     * <p>Ordered for reading rather than for speed, unlike the predicates in the tokenizer:
     * the only caller returns before reaching this unless a comment or a bad token was just
     * dropped, so it runs a handful of times in a whole stylesheet.
     */
    private static boolean fuses(char c) {
        return Character.isLetterOrDigit(c)
               || c >= 0x80
               || c == '-'
               || c == '_'
               || c == '.'
               || c == '%'
               || c == '#'
               || c == '@'
               || c == '\\'
               || c == '+';
    }

    private char lastChar() {
        return this.out.isEmpty() ? '\0' : this.out.charAt(this.out.length() - 1);
    }

    private static char firstOf(String text) {
        return text.isEmpty() ? '\0' : text.charAt(0);
    }

    private IdentifierEncoding encoding() {
        return this.options.identifierEncoding();
    }
}
