package dev.nullkitty.cassette.bundle;

import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import java.util.List;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import dev.nullkitty.cassette.fixtures.Fixture;
import dev.nullkitty.cassette.fixtures.FixtureLoader;
import dev.nullkitty.cassette.fixtures.Golden;

/**
 * Golden dumps for every bundle fixture that declares one.
 *
 * <p>The bundle counterpart of {@code ParserFixtureTest}, and the same bargain: the golden holds
 * the tree <em>and</em> the diagnostics, because a fixture asserting only the tree would pass
 * whether a stranded {@code @import} was hoisted with a warning or moved in silence.
 */
class BundleFixtureTest {

    /**
     * Bundle fixtures without this file simply are not asserted at the tree level.
     */
    private static final String VARIANT = "bundle.txt";

    @TestFactory
    List<DynamicTest> matchesGoldenBundleDumps() {
        return FixtureLoader.loadAll() //
                            .stream() //
                            .filter(Fixture::isBundle) //
                            .filter(fixture -> fixture.hasVariant(VARIANT) || FixtureLoader.updateMode()) //
                            .map(fixture -> dynamicTest(fixture.name(), () -> assertBundle(fixture))) //
                            .toList();
    }

    private static void assertBundle(Fixture fixture) {
        Golden.assertMatches(fixture,
                             VARIANT,
                             BundleFixtures.dump(BundleFixtures.bundle(fixture, BundleOptions.DEFAULTS)));
    }
}
