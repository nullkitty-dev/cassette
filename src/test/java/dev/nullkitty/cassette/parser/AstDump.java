package dev.nullkitty.cassette.parser;

import java.util.List;

import dev.nullkitty.cassette.ast.AtKeywordToken;
import dev.nullkitty.cassette.ast.AtRule;
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
import dev.nullkitty.cassette.ast.SelectorList;
import dev.nullkitty.cassette.ast.SimpleBlock;
import dev.nullkitty.cassette.ast.SourceSpan;
import dev.nullkitty.cassette.ast.StringToken;
import dev.nullkitty.cassette.ast.StyleRule;
import dev.nullkitty.cassette.ast.Stylesheet;
import dev.nullkitty.cassette.ast.TypeSelector;
import dev.nullkitty.cassette.ast.UrlToken;
import dev.nullkitty.cassette.ast.WhitespaceToken;
import dev.nullkitty.cassette.diagnostics.Diagnostic;

/**
 * Renders a parsed stylesheet as an indented tree, for golden fixtures.
 *
 * <pre>
 * Stylesheet 0..24
 *   StyleRule 0..24
 *     SelectorList 0..5
 *       Complex 0..5 (0,1,0)
 *         Compound 0..5
 *           Class |card|
 *     Declaration |color| 8..18
 *       Ident |red|
 * </pre>
 *
 * <p>Every node prints its span, because spans are what error recovery is built on and a
 * dump that hid them would let an off-by-one through unnoticed. Diagnostics are appended
 * after the tree by {@link #withDiagnostics}, so one golden file covers both halves of a
 * {@link ParseResult}.
 */
public final class AstDump {

    private static final String INDENT = "  ";

    private final StringBuilder out = new StringBuilder();

    /**
     * Parses already-decoded text and dumps the tree alone.
     */
    static String of(String css) {
        return of(CssParser.parse(css).ast());
    }

    /**
     * Dumps a tree.
     */
    public static String of(Stylesheet stylesheet) {
        AstDump dump = new AstDump();
        dump.node(stylesheet, 0);
        return dump.out.toString();
    }

    /**
     * Dumps raw bytes, exercising charset detection, with the diagnostics appended.
     *
     * <p>The two belong in one file: a fixture that asserted the tree but not the
     * diagnostics would pass just as happily whether a malformed rule was recovered or
     * silently swallowed.
     */
    static String withDiagnostics(byte[] css) {
        ParseResult result = CssParser.parse(css);
        return withDiagnostics(result.ast(), result.diagnostics());
    }

    /**
     * Dumps a tree already built, with its diagnostics appended.
     *
     * <p>The form a bundle needs, since its tree comes from several parses and an assembly
     * pass rather than from one call.
     *
     * @param stylesheet  the tree
     * @param diagnostics what to append under it
     * @return the dump
     */
    public static String withDiagnostics(Stylesheet stylesheet, List<Diagnostic> diagnostics) {
        StringBuilder text = new StringBuilder(of(stylesheet));
        if (!diagnostics.isEmpty()) {
            text.append("\ndiagnostics\n");

            for (Diagnostic diagnostic : diagnostics) {
                text.append(INDENT).append(diagnostic.severity()).append(' ').append(span(diagnostic.span()))
                    .append(' ').append(diagnostic.message()).append('\n');
            }
        }

        return text.toString();
    }

    // -----------------------------------------------------------------------
    // Nodes
    // -----------------------------------------------------------------------

    private void node(Node node, int depth) {
        switch (node) {
            case Stylesheet stylesheet -> {
                line(depth, "Stylesheet " + span(stylesheet.span()));
                nodes(stylesheet.children(), depth + 1);
            }

            case StyleRule rule -> {
                line(depth, "StyleRule " + span(rule.span()));
                node(rule.selectors(), depth + 1);
                nodes(rule.body(), depth + 1);
            }

            case ConditionalGroupRule rule -> {
                line(depth, "ConditionalGroupRule @" + rule.name() + " " + span(rule.span()));
                labelled("prelude", rule.prelude(), depth + 1);
                nodes(rule.body(), depth + 1);
            }

            case AtRule rule -> {
                String block = rule.isStatement() ? " statement" : "";
                line(depth, "AtRule @" + rule.name() + " " + span(rule.span()) + block);
                labelled("prelude", rule.prelude(), depth + 1);

                if (!rule.isStatement()) {
                    labelled("block", rule.block(), depth + 1);
                }
            }

            case Declaration declaration -> {
                line(depth,
                     "Declaration "
                            + text(declaration.property())
                            + " "
                            + span(declaration.span())
                            + (declaration.important() ? " important" : ""));
                nodes(declaration.value(), depth + 1);
            }

            case SelectorList list -> {
                line(depth, "SelectorList " + span(list.span()));

                for (ComplexSelector selector : list.selectors()) {
                    node(selector, depth + 1);
                }
            }

            case ComplexSelector selector -> {
                line(depth, "Complex " + span(selector.span()) + " " + selector.specificity());

                for (CombinatorStep step : selector.steps()) {
                    step(step, depth + 1);
                }
            }

            case CompoundSelector compound -> compound(compound, "", depth);

            case ComponentValue value -> value(value, depth);

            default -> line(depth, node.getClass().getSimpleName() + " " + span(node.span()));
        }
    }

    private void step(CombinatorStep step, int depth) {
        String combinator = step.combinator() == Combinator.NONE ? "" : " " + step.combinator().name();
        compound(step.compound(), combinator, depth);
    }

    private void compound(CompoundSelector compound, String suffix, int depth) {
        line(depth, "Compound " + span(compound.span()) + suffix);

        for (Node simple : compound.simples()) {
            simple(simple, depth + 1);
        }
    }

    private void simple(Node selector, int depth) {
        switch (selector) {
            case TypeSelector type -> line(depth, "Type " + namespace(type.namespace()) + text(type.name()));

            case ClassSelector selected -> line(depth, "Class " + text(selected.name()));

            case IdSelector selected -> line(depth, "Id " + text(selected.name()));

            case NestingSelector ignored -> line(depth, "Nesting");

            case AttributeSelector attribute -> attribute(attribute, depth);

            case PseudoClassSelector pseudo -> {
                line(depth, "PseudoClass " + text(pseudo.name()) + (pseudo.functional() ? " functional" : ""));
                labelled("arguments", pseudo.arguments(), depth + 1);

                if (pseudo.selectors() != null) {
                    node(pseudo.selectors(), depth + 1);
                }
            }

            case PseudoElementSelector pseudo -> {
                line(depth,
                     "PseudoElement "
                            + (pseudo.doubleColon() ? "::" : ":")
                            + text(pseudo.name())
                            + (pseudo.functional() ? " functional" : ""));
                labelled("arguments", pseudo.arguments(), depth + 1);
            }

            default -> line(depth, selector.getClass().getSimpleName());
        }
    }

    private void attribute(AttributeSelector attribute, int depth) {
        StringBuilder text =
            new StringBuilder("Attribute ").append(namespace(attribute.namespace())).append(text(attribute.name()));
        if (attribute.matcher() != AttributeMatcher.PRESENT) {
            text.append(' ') //
                .append(attribute.matcher().name()) //
                .append(' ') //
                .append(text(attribute.value()));
        }

        switch (attribute.caseMode()) {
            case INSENSITIVE -> text.append(" i");
            case SENSITIVE -> text.append(" s");
            case UNSPECIFIED -> {
                // The common case says nothing.
            }
        }

        line(depth, text.toString());
    }

    // -----------------------------------------------------------------------
    // Component values
    // -----------------------------------------------------------------------

    private void value(ComponentValue value, int depth) {
        switch (value) {
            case IdentToken token -> line(depth, "Ident " + text(token.value()));
            case AtKeywordToken token -> line(depth, "AtKeyword @" + text(token.name()));
            case HashToken token -> line(depth, "Hash " + text(token.value()) + (token.id() ? " id" : ""));
            case StringToken token -> line(depth, "String " + text(token.value()) + terminated(token.terminated()));
            case BadStringToken ignored -> line(depth, "BadString");
            case UrlToken token -> line(depth, "Url " + text(token.value()) + terminated(token.terminated()));
            case BadUrlToken ignored -> line(depth, "BadUrl");
            case DelimToken token -> line(depth, "Delim " + text(token.text()));

            case NumberToken token -> line(depth,
                                           "Number "
                                                  + text(token.rawText())
                                                  + " = "
                                                  + number(token.value())
                                                  + (token.isInteger() ? " integer" : ""));

            case PercentageToken token -> line(depth,
                                               "Percentage " + text(token.rawText()) + " = " + number(token.value()));

            case DimensionToken token -> line(depth,
                                              "Dimension "
                                                     + text(token.rawText())
                                                     + " unit="
                                                     + text(token.unit())
                                                     + " = "
                                                     + number(token.value()));

            case WhitespaceToken ignored -> line(depth, "Whitespace");
            case Punctuation token -> line(depth, "Punctuation " + text(token.text()));

            case Comment token -> line(depth,
                                       "Comment "
                                              + span(token.span())
                                              + " "
                                              + text(token.text())
                                              + terminated(token.terminated()));

            case FunctionValue function -> {
                line(depth, "Function " + text(function.name()) + " " + span(function.span()));

                for (ComponentValue argument : function.arguments()) {
                    value(argument, depth + 1);
                }
            }

            case SimpleBlock block -> {
                line(depth, "Block " + text(block.open() + "" + block.close()) + " " + span(block.span()));

                for (ComponentValue content : block.contents()) {
                    value(content, depth + 1);
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Formatting
    // -----------------------------------------------------------------------

    private void nodes(List<? extends Node> children, int depth) {
        for (Node child : children) {
            node(child, depth);
        }
    }

    /**
     * Prints a named child list, and nothing at all when it is empty.
     */
    private void labelled(String label, List<ComponentValue> values, int depth) {
        if (values == null || values.isEmpty()) {
            return;
        }

        line(depth, label);

        for (ComponentValue value : values) {
            value(value, depth + 1);
        }
    }

    private void line(int depth, String text) {
        this.out.append(INDENT.repeat(depth)).append(text).append('\n');
    }

    private static String span(SourceSpan span) {
        return span.start() + ".." + span.end();
    }

    private static String namespace(String namespace) {
        return namespace == null ? "" : "ns=" + text(namespace) + " ";
    }

    private static String terminated(boolean terminated) {
        return terminated ? "" : " unterminated";
    }

    /**
     * Renders whole values without a trailing {@code .0}, matching the token dumps.
     */
    private static String number(double value) {
        if (value == Math.rint(value) && !Double.isInfinite(value) && Math.abs(value) < 1e15) {
            return Long.toString((long) value);
        }

        return Double.toString(value);
    }

    /**
     * Delimits a value so leading and trailing whitespace stay visible in a diff.
     */
    private static String text(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 2).append('|');

        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);

            switch (c) {
                case '\n' -> escaped.append("\\n");
                case '\t' -> escaped.append("\\t");
                case '\\' -> escaped.append("\\\\");
                case '|' -> escaped.append("\\|");
                default -> {
                    if (c < 0x20 || c == 0x7F) {
                        escaped.append(String.format("\\u%04X", (int) c));
                    }
                    else {
                        escaped.append(c);
                    }
                }
            }
        }

        return escaped.append('|').toString();
    }

    private AstDump() {
        // static only
    }
}
