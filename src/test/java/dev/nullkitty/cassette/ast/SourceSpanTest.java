package dev.nullkitty.cassette.ast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SourceSpanTest {

    @Test
    void endIsExclusive() {
        SourceSpan span = new SourceSpan(4, 3);

        assertThat(span.end()).isEqualTo(7);
        assertThat(span.text("0123456789")).isEqualTo("456");
    }

    @Test
    void zeroLengthSpansAreEmpty() {
        assertThat(new SourceSpan(9, 0).isEmpty()).isTrue();
        assertThat(SourceSpan.NONE.isEmpty()).isTrue();
        assertThat(new SourceSpan(9, 1).isEmpty()).isFalse();
    }

    @Test
    void unionSpansTheGapBetweenDisjointSpans() {
        SourceSpan union = new SourceSpan(2, 3).union(new SourceSpan(10, 1));

        assertThat(union).isEqualTo(new SourceSpan(2, 9));
    }

    @Test
    void unionIsOrderIndependentAndAbsorbsContainedSpans() {
        SourceSpan outer = new SourceSpan(0, 20);
        SourceSpan inner = new SourceSpan(5, 2);

        assertThat(outer.union(inner)).isEqualTo(outer);
        assertThat(inner.union(outer)).isEqualTo(outer);
    }

    @Test
    void rejectsNegativeOffsets() {
        assertThatThrownBy(() -> new SourceSpan(-1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SourceSpan(0, -1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void toStringReadsAsAHalfOpenInterval() {
        assertThat(new SourceSpan(3, 4)).hasToString("SourceSpan[3, 7)");
    }
}
