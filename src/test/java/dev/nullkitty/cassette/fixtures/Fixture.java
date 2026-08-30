package dev.nullkitty.cassette.fixtures;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * One fixture directory: a single {@code input.css} plus zero or more named expected
 * outputs under {@code expected/}.
 *
 * <p>A variant name is simply the expected file's name, {@code tokens.txt},
 * {@code flattened.unminified.legacy.css}. Deliberately an opaque string rather than a
 * parsed set of axes, so a new output axis is additive: drop in a new file, assert against
 * it, done.
 *
 * <p>The input is kept as raw bytes. Charset and BOM detection happens on bytes, so a
 * fixture that decodes to a {@code String} up front would be unable to exercise it.
 */
public final class Fixture {

    private final String              name;
    private final Path                directory;
    private final Path                sourceDirectory;
    private final byte[]              input;
    private final Map<String, byte[]> sources;
    private final List<String>        entry;
    private final Map<String, String> expected;

    Fixture(String name,
            Path directory,
            Path sourceDirectory,
            byte[] input,
            Map<String, byte[]> sources,
            List<String> entry,
            Map<String, String> expected) {
        this.name = name;
        this.directory = directory;
        this.sourceDirectory = sourceDirectory;
        this.input = input;
        this.sources = new LinkedHashMap<>(sources);
        this.entry = List.copyOf(entry);
        this.expected = Map.copyOf(expected);
    }

    /**
     * Directory name of the fixture, e.g. {@code nesting-basic}.
     */
    public String name() {
        return this.name;
    }

    /**
     * The fixture as it exists on the test runtime classpath.
     */
    public Path directory() {
        return this.directory;
    }

    /**
     * Raw, undecoded {@code input.css}: BOM and all.
     *
     * @throws IllegalStateException if this is a bundle fixture, which has no single input
     */
    public byte[] input() {
        if (this.input == null) {
            throw new IllegalStateException("fixture '"
                                            + this.name
                                            + "' is a bundle fixture; "
                                            + "it has sources() and entry(), not one input");
        }

        return this.input.clone();
    }

    /**
     * Whether this fixture bundles several sources rather than parsing one input.
     *
     * @return whether it has a {@code sources/} directory
     */
    public boolean isBundle() {
        return this.input == null;
    }

    /**
     * Every file under {@code sources/}, keyed by its path relative to that directory.
     *
     * <p>The key is the id a bundle uses and the specifier an {@code @import} resolves against,
     * so a nested {@code base/buttons.css} is reachable by exactly the name it is written with.
     *
     * @return the sources, in filename order; empty for an ordinary fixture
     */
    public Map<String, byte[]> sources() {
        Map<String, byte[]> copy = new LinkedHashMap<>();
        this.sources.forEach((id, bytes) -> copy.put(id, bytes.clone()));
        return copy;
    }

    /**
     * The sources to bundle, in cascade order, as {@code entry.txt} lists them.
     *
     * @return the entry ids; empty for an ordinary fixture
     */
    public List<String> entry() {
        return this.entry;
    }

    /**
     * {@code input.css} decoded as UTF-8, for the many fixtures where the bytes aren't
     * the point.
     */
    public String inputText() {
        return new String(this.input, StandardCharsets.UTF_8);
    }

    /**
     * Available variant names, sorted.
     */
    public Set<String> variants() {
        return new TreeMap<>(this.expected).keySet();
    }

    public boolean hasVariant(String variant) {
        return this.expected.containsKey(variant);
    }

    /**
     * Expected output for a variant, with the trailing newline every well-behaved editor
     * adds stripped.
     *
     * @throws IllegalArgumentException if the fixture has no such variant, which is
     *         nearly always a typo rather than an intentionally untested combination
     */
    public String expected(String variant) {
        String content = this.expected.get(variant);
        if (content == null) {
            throw new IllegalArgumentException("fixture '"
                                               + this.name
                                               + "' has no expected/"
                                               + variant
                                               + " (have: "
                                               + variants()
                                               + ")");
        }

        return content;
    }

    /**
     * Writes {@code actual} to this fixture's expected file in the <em>source</em> tree.
     *
     * <p>Only called under {@code -Dcassette.fixtures.update=true}; see
     * {@link FixtureLoader#updateMode()} for the intended accept-the-diff workflow.
     */
    public void writeExpected(String variant, //
                              String actual) {
        if (this.sourceDirectory == null) {
            throw new IllegalStateException("cannot update fixture '"
                                            + this.name
                                            + "': system property "
                                            + FixtureLoader.SOURCE_DIR_PROPERTY
                                            + " is not set");
        }

        Path target = this.sourceDirectory.resolve("expected").resolve(variant);
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, actual.stripTrailing() + "\n", StandardCharsets.UTF_8);
        }
        catch (IOException e) {
            throw new UncheckedIOException("failed writing " + target, e);
        }
    }

    @Override
    public String toString() {
        return this.name;
    }
}
