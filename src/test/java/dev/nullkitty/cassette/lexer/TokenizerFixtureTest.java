package dev.nullkitty.cassette.lexer;

import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import java.util.List;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import dev.nullkitty.cassette.fixtures.Fixture;
import dev.nullkitty.cassette.fixtures.FixtureLoader;
import dev.nullkitty.cassette.fixtures.Golden;

/**
 * Golden token dumps for every fixture that declares one.
 *
 * <p>Driven from the fixture's raw bytes rather than its text, so charset detection and
 * preprocessing are part of what these assert.
 */
class TokenizerFixtureTest {

    /**
     * Fixtures without this file simply aren't asserted at the token level.
     */
    private static final String VARIANT = "tokens.txt";

    @TestFactory
    List<DynamicTest> matchesGoldenTokenDumps() {
        return FixtureLoader.loadAll() //
                            .stream() //
                            // A bundle fixture has no single input to tokenize; its sources are tokenized
                            // individually by the parses the bundler drives.
                            .filter(fixture -> !fixture.isBundle()) //
                            .filter(fixture -> fixture.hasVariant(VARIANT) || FixtureLoader.updateMode()) //
                            .map(fixture -> dynamicTest(fixture.name(), () -> assertTokens(fixture))) //
                            .toList();
    }

    private static void assertTokens(Fixture fixture) {
        Golden.assertMatches(fixture, VARIANT, TokenDump.of(fixture.input()));
    }
}
