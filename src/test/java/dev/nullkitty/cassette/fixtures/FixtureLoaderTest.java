package dev.nullkitty.cassette.fixtures;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * Exercises the fixture harness itself: there is no parser to point it at yet.
 */
class FixtureLoaderTest {

    @Test
    void discoversEveryFixtureDirectory() {
        List<Fixture> fixtures = FixtureLoader.loadAll();

        assertThat(fixtures).extracting(Fixture::name).contains("nesting-basic");
    }

    @Test
    void readsInputAsRawBytes() {
        Fixture fixture = FixtureLoader.load("nesting-basic");

        assertThat(fixture.input()).isNotEmpty();
        assertThat(fixture.inputText()).contains("&");
    }

    @Test
    void namesVariantsAfterTheirExpectedFile() {
        Fixture fixture = FixtureLoader.load("nesting-basic");

        assertThat(fixture.variants()).contains("nested.unminified.modern.css", "flattened.unminified.legacy.css");
        assertThat(fixture.hasVariant("nested.unminified.modern.css")).isTrue();
        assertThat(fixture.hasVariant("no.such.variant.css")).isFalse();
    }

    @Test
    @DisplayName("a missing variant names the ones that do exist")
    void reportsAvailableVariantsOnMiss() {
        Fixture fixture = FixtureLoader.load("nesting-basic");

        assertThatThrownBy(() -> fixture.expected("flattened.minified.ancient.css")).isInstanceOf(IllegalArgumentException.class)
                                                                                    .hasMessageContaining("nesting-basic")
                                                                                    .hasMessageContaining("nested.unminified.modern.css");
    }

    @Test
    void rejectsUnknownFixtures() {
        assertThatThrownBy(() -> FixtureLoader.load("does-not-exist")).isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * The shape every fixture suite takes: one dynamic test per (fixture, variant) pair.
     * The claim here is only that each expected file is readable and non-empty. Comparing
     * them against real output is the job of the suites next to the code that produces it.
     */
    @TestFactory
    List<DynamicTest> everyVariantIsReadable() {
        return FixtureLoader.loadAll() //
                            .stream() //
                            .flatMap(fixture -> fixture.variants() //
                                                       .stream() //
                                                       .map(variant -> dynamicTest(fixture.name() + " / " + variant,
                                                                                   () -> assertThat(fixture.expected(variant)).isNotBlank()))) //
                            .toList();
    }
}
