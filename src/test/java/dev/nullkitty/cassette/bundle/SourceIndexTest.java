package dev.nullkitty.cassette.bundle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import dev.nullkitty.cassette.ast.SourceSpan;
import dev.nullkitty.cassette.diagnostics.SourceResolver;

/**
 * The map out of the coordinate space.
 *
 * <p>Everything here is about offsets rather than about CSS, because the index is the one piece of
 * bundling that has no opinion about what a source contains.
 */
class SourceIndexTest {

    private static final String A = "a{color:red}";
    private static final String B = ".b{top:0}";
    private static final String C = "@media print{}";

    /**
     * The three-source layout every test below resolves against.
     */
    private static SourceIndex three() {
        return SourceIndex.builder() //
                          .add("a.css", A) //
                          .add("b.css", B) //
                          .add("c.css", C) //
                          .build();
    }

    @Test
    void laysSourcesOutEndToEnd() {
        SourceIndex index = three();

        assertThat(index.segments()).containsExactly(new SourceIndex.Segment("a.css", 0, A.length(), null),
                                                     new SourceIndex.Segment("b.css", A.length(), B.length(), null),
                                                     new SourceIndex.Segment("c.css",
                                                                             A.length() + B.length(),
                                                                             C.length(),
                                                                             null));

        assertThat(index.length()).isEqualTo(A.length() + B.length() + C.length());
    }

    /**
     * The base is handed out before the source is decoded, because it has to be threaded
     * through the decode and the parse that follow it.
     */
    @Test
    void handsOutTheNextBaseBeforeTheSourceIsAdded() {
        SourceIndex.Builder builder = SourceIndex.builder();

        assertThat(builder.nextBase()).isZero();
        builder.add("a.css", A);
        assertThat(builder.nextBase()).isEqualTo(A.length());
        builder.add("b.css", B);
        assertThat(builder.nextBase()).isEqualTo(A.length() + B.length());
    }

    @Test
    void resolvesAnOffsetToItsSourceAndItsLocalPosition() {
        SourceIndex index = three();

        assertThat(index.resolve(0)).isEqualTo(new Origin("a.css", 0));
        assertThat(index.resolve(2)).isEqualTo(new Origin("a.css", 2));
        assertThat(index.resolve(A.length())).isEqualTo(new Origin("b.css", 0));
        assertThat(index.resolve(A.length() + 3)).isEqualTo(new Origin("b.css", 3));
        assertThat(index.resolve(A.length() + B.length())).isEqualTo(new Origin("c.css", 0));
    }

    /**
     * A zero-width span at the very end of the space is where an EOF token sits, so the last
     * offset has to resolve rather than being one past everything.
     */
    @Test
    void resolvesTheOffsetOnePastTheLastCharacter() {
        SourceIndex index = three();

        assertThat(index.resolve(index.length())).isEqualTo(new Origin("c.css", C.length()));
        assertThat(index.textOf(new SourceSpan(index.length(), 0))).isEmpty();
    }

    @Test
    void slicesTextFromTheSourceASpanActuallyCameFrom() {
        SourceIndex index = three();
        SourceSpan top = new SourceSpan(A.length() + 3, 3);

        assertThat(index.textOf(top)).isEqualTo("top");

        // The trap this class exists for: the same span against any one source's text is in
        // range, returns something, and is wrong.
        assertThat(top.text(A + B + C)).isEqualTo("top");
        assertThat(new SourceSpan(0, 1).text(B)).isEqualTo(".");
    }

    @Test
    void locatesASpanForWhateverRendersIt() {
        SourceResolver resolver = three();

        SourceResolver.Location located = resolver.locate(new SourceSpan(A.length() + 3, 3));

        assertThat(located.sourceId()).isEqualTo("b.css");
        assertThat(located.sourceText()).isEqualTo(B);
        assertThat(located.offset()).isEqualTo(3);
        assertThat(located.text()).isEqualTo("top");
    }

    @Test
    void recordsWhichImportPulledASourceIn() {
        Origin cause = new Origin("a.css", 5);

        SourceIndex index = SourceIndex.builder().add("a.css", A).add("b.css", B, cause).build();

        assertThat(index.segments().get(0).importedFrom()).isNull();
        assertThat(index.segments().get(1).importedFrom()).isEqualTo(cause);
    }

    /**
     * The same questions {@link Rejections} asks, asked by something walking a whole tree.
     *
     * <p>A source-map generator meets an unresolvable span on the first nested import and has to
     * carry on, so the refusals below are answers rather than errors, while still being
     * refusals, which is the part worth testing.
     */
    @Nested
    class TryLocate {

        @Test
        void answersWhereLocateWould() {
            SourceIndex index = three();
            SourceSpan span = new SourceSpan(A.length() + 1, 2);

            assertThat(index.tryLocate(span)).contains(index.locate(span));
        }

        @Test
        void declinesASpanStraddlingTwoSources() {
            SourceIndex index = three();

            assertThat(index.tryLocate(new SourceSpan(A.length() - 1, 2))).isEmpty();
        }

        @Test
        void declinesASpanOutsideTheSpace() {
            SourceIndex index = three();

            assertThat(index.tryLocate(new SourceSpan(index.length() + 1, 1))).isEmpty();
        }

        @Test
        void declinesEverythingWhenItHoldsNoSources() {
            assertThat(SourceIndex.builder().build().tryLocate(new SourceSpan(0, 1))).isEmpty();
        }

        @Test
        void alsoWorksForASingleSource() {
            // SourceResolver.of has no range table to consult, so it range-checks the text
            // directly, and it has to reach the same answer for the case a single-source tree
            // can actually produce.
            SourceResolver single = SourceResolver.of("a.css", A);

            assertThat(single.tryLocate(new SourceSpan(0, 1))).isPresent();
            assertThat(single.tryLocate(new SourceSpan(A.length() + 5, 1))).isEmpty();
        }

        @Test
        void singleSourceRangeCheckAgreesWithLocateAtEveryBoundary() {
            // `of` answers this from arithmetic where it used to catch what Location's own
            // constructor throws, so the two have to agree exactly and the boundary is the only
            // place they could not. A span ending on the last character is inside; one character
            // more is not, whether it starts inside or outside.
            SourceResolver single = SourceResolver.of("a.css", A);

            assertThat(single.tryLocate(new SourceSpan(0, A.length()))).isPresent();
            assertThat(single.tryLocate(new SourceSpan(A.length(), 0))).isPresent();
            assertThat(single.tryLocate(new SourceSpan(0, A.length() + 1))).isEmpty();
            assertThat(single.tryLocate(new SourceSpan(A.length() - 1, 2))).isEmpty();
            assertThat(single.tryLocate(new SourceSpan(A.length() + 1, 0))).isEmpty();
        }
    }

    @Nested
    class Rejections {

        /**
         * The silent one, and the reason it is checked rather than documented. §3.3 collapses
         * CRLF, which is the only preprocessing rule that changes a length, so raw text here
         * would make every base after it too large and every later span resolve into the wrong
         * file, with nothing out of range to notice.
         */
        @Test
        void refusesTextThatHasNotBeenPreprocessed() {
            assertThatThrownBy(() -> SourceIndex.builder()
                                                .add("a.css", "a{}\r\nb{}"))
                                                                            .isInstanceOf(IllegalArgumentException.class)
                                                                            .hasMessageContaining("a.css")
                                                                            .hasMessageContaining("carriage return")
                                                                            .hasMessageContaining("CssParser.decode");
        }

        @Test
        void refusesAnOffsetOutsideTheSpace() {
            SourceIndex index = three();

            assertThatThrownBy(() -> index.resolve(-1)).isInstanceOf(IndexOutOfBoundsException.class);
            assertThatThrownBy(() -> index.resolve(index.length() + 1)).isInstanceOf(IndexOutOfBoundsException.class);
        }

        /**
         * A span covering the seam between two sources is not a span any parse produces, every
         * tree is built from one source at a time, so it is a bug in whatever synthesized it,
         * and answering with either neighbour would hide it.
         */
        @Test
        void refusesASpanStraddlingTwoSources() {
            SourceIndex index = three();

            assertThatThrownBy(() -> index.textOf(new SourceSpan(A.length() - 1,
                                                                 2))).isInstanceOf(IndexOutOfBoundsException.class)
                                                                     .hasMessageContaining("straddles")
                                                                     .hasMessageContaining("a.css");
        }

        @Test
        void refusesToResolveAnythingWhenItHoldsNoSources() {
            assertThatThrownBy(() -> SourceIndex.builder().build()
                                                .resolve(0)).isInstanceOf(IndexOutOfBoundsException.class)
                                                            .hasMessageContaining("no sources");
        }
    }

    /**
     * An empty source is a zero-length segment sharing its base with whatever follows, and no
     * offset belongs to a segment covering no characters. Resolving to the empty source instead
     * would put every offset of its non-empty neighbour in the wrong file.
     */
    @Test
    void givesAnEmptySourceNoOffsetOfItsOwn() {
        SourceIndex index = SourceIndex.builder().add("empty.css", "").add("b.css", B).build();

        assertThat(index.segments().get(0).length()).isZero();
        assertThat(index.resolve(0)).isEqualTo(new Origin("b.css", 0));
    }
}
