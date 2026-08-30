package dev.nullkitty.cassette.fixtures;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one place golden-file comparison happens, so update mode is honored uniformly
 * rather than remembered at each call site.
 */
public final class Golden {

    private Golden() {
        // static-only
    }

    /**
     * Asserts {@code actual} matches the fixture's expected output for {@code variant},
     * or, under {@link FixtureLoader#updateMode()}, overwrites the expected file with it.
     */
    public static void assertMatches(Fixture fixture, String variant, String actual) {
        assertMatches(fixture, variant, actual, FixtureLoader.updateMode());
    }

    /**
     * As {@link #assertMatches(Fixture, String, String)}, with update mode passed in rather
     * than read from the system property.
     *
     * <p>Only this class's own tests use it. They cover the comparison update mode exists to
     * skip, against a synthetic fixture that has nowhere to write back to, so inheriting the
     * ambient setting would make them fail under the very flag they are unaffected by.
     */
    static void assertMatches(Fixture fixture, String variant, String actual, boolean update) {
        String normalized = actual.stripTrailing();
        if (update) {
            fixture.writeExpected(variant, normalized);
            return;
        }

        assertThat(normalized).as("fixture '%s', variant '%s'", fixture.name(), variant)
                              .isEqualTo(fixture.expected(variant));
    }
}
