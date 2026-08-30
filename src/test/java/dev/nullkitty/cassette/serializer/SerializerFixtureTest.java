package dev.nullkitty.cassette.serializer;

import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import java.util.List;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import dev.nullkitty.cassette.ast.Stylesheet;
import dev.nullkitty.cassette.bundle.BundleFixtures;
import dev.nullkitty.cassette.bundle.BundleOptions;
import dev.nullkitty.cassette.fixtures.Fixture;
import dev.nullkitty.cassette.fixtures.FixtureLoader;
import dev.nullkitty.cassette.fixtures.Golden;
import dev.nullkitty.cassette.parser.CssParser;

/**
 * Golden CSS output for every {@code <nesting>.<minification>.<compat>.css} a fixture
 * declares.
 *
 * <p>The variant name is the test case: it says which combination of axes to serialize
 * with, so adding a combination is dropping in a file and regenerating. A combination
 * nobody wrote a file for is simply not asserted; the loader has no opinion about which
 * ones ought to exist.
 */
class SerializerFixtureTest {

    private static final String SUFFIX = ".css";

    @TestFactory
    List<DynamicTest> matchesGoldenCss() {
        return FixtureLoader.loadAll().stream()
                            .flatMap(fixture -> fixture.variants().stream().filter(variant -> variant.endsWith(SUFFIX))
                                                       .map(variant -> dynamicTest(fixture.name() + " " + variant,
                                                                                   () -> assertCss(fixture, variant))))
                            .toList();
    }

    private static void assertCss(Fixture fixture, String variant) {
        // From bytes, like the token and AST dumps: charset detection is part of the input.
        // A bundle fixture assembles its sources first and then means exactly the same thing:
        // the tree is an ordinary Stylesheet, so the four axes select the same four things.
        Stylesheet ast = fixture.isBundle() ? BundleFixtures.bundle(fixture, BundleOptions.DEFAULTS).ast()
                                            : CssParser.parse(fixture.input()).ast();
        Golden.assertMatches(fixture, variant, CssSerializer.serialize(ast, optionsFor(variant)));
    }

    /**
     * Turns {@code flattened.minified.legacy.css} into the options that produce it.
     *
     * <p>Legacy is applied as the preset it is, with the nesting axis set explicitly on top,
     * so {@code nested.unminified.legacy.css} means what it says: ASCII-escaped output that
     * still nests.
     */
    static SerializerOptions optionsFor(String variant) {
        return optionsFor(variant, SUFFIX);
    }

    /**
     * As {@link #optionsFor(String)}, for a variant whose extension is not {@code .css}.
     *
     * <p>The source-map dumps are the second family named by the same three axes, and this
     * stays the one place that maps a name to options.
     *
     * @param variant the expected file's name
     * @param suffix  everything after the axes, extension included
     * @return the options that variant asks for
     */
    static SerializerOptions optionsFor(String variant, String suffix) {
        String[] axes = variant.substring(0, variant.length() - suffix.length()).split("\\.");
        if (axes.length != 3) {
            throw new IllegalArgumentException("expected <nesting>.<minification>.<compat>"
                                               + suffix
                                               + " but got '"
                                               + variant
                                               + "'");
        }

        SerializerOptions.Builder options = SerializerOptions.builder();
        if ("legacy".equals(axes[2])) {
            options.legacyCompatible();
        }
        else if (!"modern".equals(axes[2])) {
            throw new IllegalArgumentException("unknown compat axis '" + axes[2] + "'");
        }

        return options.nesting(axis(axes[0], "nested", NestingMode.PRESERVE, "flattened", NestingMode.FLATTEN))
                      .formatting(axis(axes[1], "unminified", Formatting.PRETTY, "minified", Formatting.MINIFIED))
                      .build();
    }

    private static <T> T axis(String written, String first, T firstValue, String second, T secondValue) {
        if (written.equals(first)) {
            return firstValue;
        }

        if (written.equals(second)) {
            return secondValue;
        }

        throw new IllegalArgumentException("expected '" + first + "' or '" + second + "' but got '" + written + "'");
    }
}
