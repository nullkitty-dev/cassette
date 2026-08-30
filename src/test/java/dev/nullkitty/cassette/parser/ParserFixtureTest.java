package dev.nullkitty.cassette.parser;

import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import java.util.List;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import dev.nullkitty.cassette.fixtures.Fixture;
import dev.nullkitty.cassette.fixtures.FixtureLoader;
import dev.nullkitty.cassette.fixtures.Golden;

/**
 * Golden AST dumps for every fixture that declares one.
 *
 * <p>Driven from the fixture's raw bytes, like the token dumps, so charset detection and
 * preprocessing stay inside what these assert, spans in the dump are offsets into the
 * decoded buffer and would shift if either changed.
 */
class ParserFixtureTest {

    /**
     * Fixtures without this file simply aren't asserted at the AST level.
     */
    private static final String VARIANT = "ast.txt";

    @TestFactory
    List<DynamicTest> matchesGoldenAstDumps() {
        return FixtureLoader.loadAll() //
                            .stream()
                            // A bundle fixture asserts its tree through bundle.txt, which also holds the
                            // segment table that makes a bundled tree's spans readable.
                            .filter(fixture -> !fixture.isBundle()) //
                            .filter(fixture -> fixture.hasVariant(VARIANT) || FixtureLoader.updateMode()) //
                            .map(fixture -> dynamicTest(fixture.name(), () -> assertAst(fixture))) //
                            .toList();
    }

    private static void assertAst(Fixture fixture) {
        Golden.assertMatches(fixture, VARIANT, AstDump.withDiagnostics(fixture.input()));
    }
}
