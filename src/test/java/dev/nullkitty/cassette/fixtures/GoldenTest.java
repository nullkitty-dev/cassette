package dev.nullkitty.cassette.fixtures;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Covers golden comparison and the update path against a throwaway directory, the real
 * fixture tree must not be mutated by its own tests.
 */
class GoldenTest {

    @Test
    void matchesIgnoringTrailingNewline() {
        Fixture fixture = fixture(null, Map.of("nested.unminified.modern.css", "a{color:red}"));

        Golden.assertMatches(fixture, "nested.unminified.modern.css", "a{color:red}\n\n", false);
    }

    @Test
    void reportsFixtureAndVariantOnMismatch() {
        Fixture fixture = fixture(null, Map.of("nested.unminified.modern.css", "a{color:red}"));

        assertThatThrownBy(() -> Golden.assertMatches(fixture,
                                                      "nested.unminified.modern.css",
                                                      "a{color:blue}",
                                                      false)).isInstanceOf(AssertionError.class)
                                                             .hasMessageContaining("selftest")
                                                             .hasMessageContaining("nested.unminified.modern.css");
    }

    @Test
    void writesExpectedFileWithTrailingNewline(@TempDir Path sourceDirectory) throws IOException {
        Fixture fixture = fixture(sourceDirectory, Map.of());

        fixture.writeExpected("flattened.minified.legacy.css", "a{color:red}");

        Path written = sourceDirectory.resolve("expected/flattened.minified.legacy.css");
        assertThat(Files.readString(written, StandardCharsets.UTF_8)).isEqualTo("a{color:red}\n");
    }

    @Test
    void refusesToUpdateWithoutASourceDirectory() {
        Fixture fixture = fixture(null, Map.of());

        assertThatThrownBy(() -> fixture.writeExpected("nested.minified.modern.css",
                                                       "a{color:red}")).isInstanceOf(IllegalStateException.class)
                                                                       .hasMessageContaining("cassette.fixtures.sourceDir");
    }

    private static Fixture fixture(Path sourceDirectory, Map<String, String> expected) {
        return new Fixture("selftest",
                           Path.of("selftest"),
                           sourceDirectory,
                           "a{color:red}".getBytes(StandardCharsets.UTF_8),
                           Map.of(),
                           List.of(),
                           expected);
    }
}
