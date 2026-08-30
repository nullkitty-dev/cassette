package dev.nullkitty.cassette.fuzz;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import dev.nullkitty.cassette.ast.AtRule;
import dev.nullkitty.cassette.ast.ComponentValue;
import dev.nullkitty.cassette.ast.ConditionalGroupRule;
import dev.nullkitty.cassette.ast.Declaration;
import dev.nullkitty.cassette.ast.FunctionValue;
import dev.nullkitty.cassette.ast.Node;
import dev.nullkitty.cassette.ast.SimpleBlock;
import dev.nullkitty.cassette.ast.SourceSpan;
import dev.nullkitty.cassette.ast.StyleRule;
import dev.nullkitty.cassette.ast.Stylesheet;
import dev.nullkitty.cassette.bundle.Origin;
import dev.nullkitty.cassette.bundle.SourceIndex;
import dev.nullkitty.cassette.diagnostics.Diagnostic;
import dev.nullkitty.cassette.parser.CssParser;
import dev.nullkitty.cassette.parser.ParseResult;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;

/**
 * What has to hold when several sources are laid out in one space of offsets.
 *
 * <p>Three sources rather than a generated list, because three is enough for every case that
 * exists, one before, one between, one after, and it keeps the shrunk counterexample readable.
 *
 * <p>The generators draw the same wreckage the parser properties do, which matters here:
 * recovered input is where spans come from odd places, and a span that a normal parse puts at a
 * plausible offset is exactly what an off-by-one in the layout would hide.
 */
class CoordinateSpacePropertiesTest {

    /**
     * The property the whole coordinate space reduces to: parsing at a base is parsing at zero
     * with every offset shifted, and nothing else about the tree differs.
     *
     * <p>If this holds, laying sources out end to end cannot make one source's spans collide
     * with another's, because each stays inside the window its own length defines.
     */
    @Property
    void parsingAtABaseShiftsEverySpanAndNothingElse(@ForAll(supplier = CssLikeArbitraries.Bytes.class) byte[] input,
                                                     @ForAll int rawBase) {
        int base = Math.floorMod(rawBase, 1 << 20);
        String text = CssParser.decode(input);

        ParseResult atZero = CssParser.parse(text);
        ParseResult based = CssParser.parse(text, base);

        List<SourceSpan> expected = spans(atZero.ast()).stream() //
                                                       .map(span -> new SourceSpan(span.start() + base, span.length())) //
                                                       .toList();

        assertThat(spans(based.ast())).isEqualTo(expected);

        assertThat(based.diagnostics().stream().map(Diagnostic::message)
                        .toList()).isEqualTo(atZero.diagnostics().stream().map(Diagnostic::message).toList());
    }

    /**
     * Every span from every source resolves to the segment it was parsed into, at the offset it
     * would have had on its own, which is the same statement as "exactly one segment", said in
     * a form that also catches resolving to the right file at the wrong offset.
     */
    @Property
    void everySpanResolvesToTheSourceItCameFrom(@ForAll(supplier = CssLikeArbitraries.Bytes.class) byte[] first,
                                                @ForAll(supplier = CssLikeArbitraries.Bytes.class) byte[] second,
                                                @ForAll(supplier = CssLikeArbitraries.Bytes.class) byte[] third) {
        byte[][] inputs = { first, second, third };
        SourceIndex.Builder layout = SourceIndex.builder();
        List<Stylesheet> trees = new ArrayList<>();
        List<String> texts = new ArrayList<>();

        for (int index = 0; index < inputs.length; index++) {
            int base = layout.nextBase();

            List<Diagnostic> charset = new ArrayList<>();
            String text = CssParser.decode(inputs[index], null, base, charset::add);

            trees.add(CssParser.parse(text, base).ast());
            texts.add(text);
            layout.add(name(index), text);

            // The other span no token builds. It has to name this source, not source zero.
            for (Diagnostic diagnostic : charset) {
                assertThat(diagnostic.span().start()).isGreaterThanOrEqualTo(base);
            }
        }

        SourceIndex index = layout.build();

        for (int source = 0; source < trees.size(); source++) {
            int base = index.segments().get(source).base();

            for (Node node : flatten(trees.get(source))) {
                SourceSpan span = node.span();
                assertThat(index.resolve(span)).as("%s from %s", span, name(source))
                                               .isEqualTo(new Origin(name(source), span.start() - base));

                assertThat(index.textOf(span).toString()).as("text of %s from %s", span, name(source))
                                                         .isEqualTo(texts.get(source)
                                                                         .substring(span.start()
                                                                                    - base,
                                                                                    span.start()
                                                                                            - base
                                                                                            + span.length()));
            }
        }
    }

    /**
     * The layout's own arithmetic, against the unit the trap is about: a source's width in the
     * space is its <em>decoded</em> length, and for anything non-ASCII or CRLF-bearing that is
     * not its byte count. Getting it from bytes would misplace every source after the first.
     */
    @Property
    void aSegmentIsAsWideAsItsDecodedText(@ForAll(supplier = CssLikeArbitraries.Bytes.class) byte[] first,
                                          @ForAll(supplier = CssLikeArbitraries.Bytes.class) byte[] second) {
        String one = CssParser.decode(first);
        String two = CssParser.decode(second);

        SourceIndex index = SourceIndex.builder().add("1", one).add("2", two).build();

        assertThat(index.segments().get(0).length()).isEqualTo(one.length());
        assertThat(index.segments().get(1).base()).isEqualTo(one.length());
        assertThat(index.length()).isEqualTo(one.length() + two.length());

        // The whole of source two sits after the whole of source one, whatever its bytes said.
        assertThat(index.resolve(one.length() + two.length())).isEqualTo(new Origin("2", two.length()));
    }

    /**
     * Text through the byte path and text through the text path agree on every offset.
     */
    @Property
    void decodingAtABaseAgreesWithParsingAtIt(@ForAll(supplier = CssLikeArbitraries.Text.class) String input) {
        byte[] bytes = input.getBytes(StandardCharsets.UTF_8);
        String text = CssParser.decode(bytes, null, 77, Diagnostic.DISCARD);

        assertThat(CssParser.parse(text, 77).ast().span()).isEqualTo(new SourceSpan(77, text.length()));
    }

    private static String name(int index) {
        return index + ".css";
    }

    private static List<SourceSpan> spans(Node root) {
        return flatten(root).stream().map(Node::span).toList();
    }

    private static List<Node> flatten(Node root) {
        List<Node> all = new ArrayList<>();
        collect(root, all);
        return all;
    }

    private static void collect(Node node, List<Node> into) {
        into.add(node);
        for (Node child : childrenOf(node)) {
            collect(child, into);
        }
    }

    private static List<Node> childrenOf(Node node) {
        return switch (node) {
            case Stylesheet stylesheet -> stylesheet.children();
            case StyleRule rule -> rule.body();
            case ConditionalGroupRule rule -> concat(rule.prelude(), rule.body());
            case AtRule rule -> rule.isStatement() ? List.copyOf(rule.prelude()) : concat(rule.prelude(), rule.block());
            case Declaration declaration -> List.copyOf(declaration.value());
            case FunctionValue function -> List.copyOf(function.arguments());
            case SimpleBlock block -> List.copyOf(block.contents());
            default -> List.of();
        };
    }

    private static List<Node> concat(List<? extends ComponentValue> first, List<? extends Node> second) {
        List<Node> all = new ArrayList<>(first);
        all.addAll(second);
        return all;
    }
}
