package dev.nullkitty.cassette.serializer;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import dev.nullkitty.cassette.ast.SourceSpan;

/**
 * The mapping list itself, apart from anything that fills it.
 */
class MappingsTest {

    @Nested
    class Recording {

        @Test
        void keepsWhatItIsGiven() {
            Mappings mappings = new Mappings(0);
            mappings.add(4, SourceSpan.pack(10, 3));
            mappings.add(9, SourceSpan.pack(20, 5));

            assertThat(mappings.size()).isEqualTo(2);
            assertThat(mappings.outputOffset(0)).isEqualTo(4);
            assertThat(mappings.packedSpan(1)).isEqualTo(SourceSpan.pack(20, 5));
        }

        @Test
        void dropsASynthesizedSpan() {
            Mappings mappings = new Mappings(0);
            mappings.add(0, SourceSpan.NONE_PACKED);

            assertThat(mappings.size()).isZero();
        }

        @Test
        void dropsAZeroWidthSpanBecauseItCannotBeToldFromASynthesizedOne() {
            // pack(0, 0) is bit-identical to NONE_PACKED, so the length is the only test there
            // is, and a construct covering no characters has nothing to point at anyway.
            assertThat(SourceSpan.pack(0, 0)).isEqualTo(SourceSpan.NONE_PACKED);

            Mappings mappings = new Mappings(0);
            mappings.add(7, SourceSpan.pack(12, 0));

            assertThat(mappings.size()).isZero();
        }

        @Test
        void growsPastAnEstimateThatCameInShort() {
            Mappings mappings = new Mappings(0);
            for (int index = 0; index < 5000; index++) {
                mappings.add(index, SourceSpan.pack(index, 1));
            }

            assertThat(mappings.size()).isEqualTo(5000);
            assertThat(mappings.outputOffset(4999)).isEqualTo(4999);
            assertThat(mappings.packedSpan(4999)).isEqualTo(SourceSpan.pack(4999, 1));
        }
    }

    @Nested
    class Truncation {

        @Test
        void dropsEverythingAtOrAfterTheMark() {
            Mappings mappings = new Mappings(0);
            mappings.add(0, SourceSpan.pack(1, 1));
            mappings.add(5, SourceSpan.pack(2, 1));
            mappings.add(9, SourceSpan.pack(3, 1));

            mappings.truncateFrom(5);

            assertThat(mappings.size()).isEqualTo(1);
            assertThat(mappings.outputOffset(0)).isZero();
        }

        @Test
        void keepsEverythingWhenTheMarkIsPastTheEnd() {
            Mappings mappings = new Mappings(0);
            mappings.add(0, SourceSpan.pack(1, 1));
            mappings.add(5, SourceSpan.pack(2, 1));

            mappings.truncateFrom(6);

            assertThat(mappings.size()).isEqualTo(2);
        }

        @Test
        void survivesAnEmptyList() {
            Mappings mappings = new Mappings(0);

            mappings.truncateFrom(0);

            assertThat(mappings.size()).isZero();
        }

        @Test
        void leavesRoomForWhatIsWrittenNext() {
            // Truncating and re-recording is the shape the writer's rollback paths produce.
            Mappings mappings = new Mappings(0);
            mappings.add(0, SourceSpan.pack(1, 1));
            mappings.add(5, SourceSpan.pack(2, 1));
            mappings.truncateFrom(5);
            mappings.add(5, SourceSpan.pack(9, 4));

            assertThat(mappings.size()).isEqualTo(2);
            assertThat(mappings.packedSpan(1)).isEqualTo(SourceSpan.pack(9, 4));
        }
    }
}
