package dev.nullkitty.cassette.fuzz;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.statistics.Statistics;

/**
 * Proves the jqwik engine is wired up and the generators produce usable input.
 *
 * <p>These claim nothing about the parser. The properties that do are in
 * {@link TokenizerPropertiesTest}, {@link ParserPropertiesTest} and their siblings.
 */
class CssLikeArbitrariesTest {

    @Property
    void generatesNonEmptyByteInput(@ForAll(supplier = CssLikeArbitraries.Bytes.class) byte[] input) {
        assertThat(input).isNotEmpty();
    }

    @Property
    void byteInputAlwaysDecodesAsUtf8(@ForAll(supplier = CssLikeArbitraries.Bytes.class) byte[] input) {
        assertThat(new String(input, StandardCharsets.UTF_8)).isNotEmpty();
    }

    @Property
    void generatesNonEmptyTextInput(@ForAll(supplier = CssLikeArbitraries.Text.class) String input) {
        assertThat(input).isNotEmpty();
    }

    /**
     * The seam fragments are the pool's whole reason for not being a list of atoms, and every
     * property hanging off this generator is worth only what the generator draws. Without this,
     * dropping {@code SEAMS} from the frequency table would turn the idempotence property green
     * and silent rather than green and meaningful.
     */
    @Property
    void mostInputCarriesASeam(@ForAll(supplier = CssLikeArbitraries.Text.class) String input) {
        Statistics.label("carries a seam") //
                  .collect(CssLikeArbitraries.containsSeam(input)) //
                  .coverage(coverage -> coverage.check(true) //
                                                .percentage(p -> p >= 50.0));
    }
}
