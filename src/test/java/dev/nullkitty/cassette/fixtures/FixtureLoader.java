package dev.nullkitty.cassette.fixtures;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Discovers and loads fixture directories from {@code src/test/resources/fixtures}.
 *
 * <p>Layout, per fixture directory:
 *
 * <pre>
 * fixtures/nesting-basic/
 *   input.css
 *   SOURCE.md                       (optional, provenance and license, for vendored WPT cases)
 *   expected/
 *     tokens.txt
 *     nested.unminified.modern.css
 *     flattened.unminified.legacy.css
 *     ...                           (only the combinations actually asserted)
 * </pre>
 *
 * <p>A variant is named by its full filename, extension included, so a fixture can hold
 * expected output in whatever form a suite needs, CSS text, a token dump, an AST dump,
 * without the loader knowing anything about the format.
 *
 * <p>A fixture with no {@code expected/} directory is legal: input-only fixtures are how
 * "must not throw" and diagnostics-only cases are expressed.
 *
 * <h2>Bundle fixtures</h2>
 *
 * <p>A directory holding {@code sources/} instead of {@code input.css} is a bundle fixture:
 *
 * <pre>
 * fixtures/bundle-import-media/
 *   sources/
 *     index.css
 *     base/buttons.css              (nested, and its id is the path under sources/)
 *   entry.txt                       entry sources, one per line, in cascade order
 *   expected/
 *     bundle.txt                    tree + diagnostics + segment table
 *     nested.unminified.modern.css
 * </pre>
 *
 * <p>Which marker file is present is the whole difference. Everything under {@code expected/}
 * means what it always meant, because a bundled tree is an ordinary {@code Stylesheet}, the
 * four serializer axes select the same four things, and only the tree they run on is assembled
 * differently.
 */
public final class FixtureLoader {

    /**
     * Classpath directory the fixture tree is copied to by the {@code processTestResources} task.
     */
    private static final String RESOURCE_ROOT = "fixtures";

    /**
     * Absolute path of the fixture tree in the source tree; set by the {@code test} task.
     */
    static final String SOURCE_DIR_PROPERTY = "cassette.fixtures.sourceDir";

    private static final String UPDATE_PROPERTY = "cassette.fixtures.update";
    private static final String INPUT_FILE      = "input.css";
    private static final String SOURCES_DIR     = "sources";
    private static final String ENTRY_FILE      = "entry.txt";

    private FixtureLoader() {
        // static-only
    }

    /**
     * Whether golden files should be rewritten rather than asserted against, enabled with
     * {@code ./gradlew test -Dcassette.fixtures.update=true}.
     *
     * <p>The workflow this exists for: make a serializer change, regenerate, read the
     * resulting diff as the review artifact. Never leave it on in CI, an always-passing
     * golden-file suite asserts nothing.
     */
    public static boolean updateMode() {
        return Boolean.parseBoolean(System.getProperty(UPDATE_PROPERTY, "false"));
    }

    /**
     * Every fixture, ordered by name.
     */
    public static List<Fixture> loadAll() {
        Path root = resourceRoot();
        try (Stream<Path> entries = Files.list(root)) {
            List<Fixture> fixtures = new ArrayList<>();
            for (Path directory : entries.filter(Files::isDirectory).sorted().toList()) {
                fixtures.add(read(directory));
            }

            if (fixtures.isEmpty()) {
                throw new IllegalStateException("no fixtures found under " + root);
            }

            return List.copyOf(fixtures);
        }
        catch (IOException e) {
            throw new UncheckedIOException("failed listing fixtures under " + root, e);
        }
    }

    /**
     * A single fixture by directory name.
     */
    public static Fixture load(String name) {
        Path directory = resourceRoot().resolve(name);
        if (!Files.isDirectory(directory)) {
            throw new IllegalArgumentException("no fixture named '" + name + "' under " + resourceRoot());
        }

        return read(directory);
    }

    private static Fixture read(Path directory) {
        String name = directory.getFileName().toString();
        Path sources = directory.resolve(SOURCES_DIR);
        Path inputFile = directory.resolve(INPUT_FILE);

        boolean bundle = Files.isDirectory(sources);
        if (!bundle && !Files.isRegularFile(inputFile)) {
            throw new IllegalStateException("fixture '"
                                            + name
                                            + "' has neither "
                                            + INPUT_FILE
                                            + " nor "
                                            + SOURCES_DIR
                                            + "/");
        }

        try {
            Map<String, String> expected = readExpected(directory, name);

            if (!bundle) {
                return new Fixture(name,
                                   directory,
                                   sourceDirectory(name),
                                   Files.readAllBytes(inputFile),
                                   Map.of(),
                                   List.of(),
                                   expected);
            }

            return new Fixture(name,
                               directory,
                               sourceDirectory(name),
                               null,
                               readSources(sources),
                               readEntry(directory, name),
                               expected);
        }
        catch (IOException e) {
            throw new UncheckedIOException("failed reading fixture '" + name + "'", e);
        }
    }

    /**
     * Every file under {@code sources/}, keyed by its path relative to it.
     *
     * <p>Relative rather than by file name, so that a fixture can hold {@code base/buttons.css}
     * and have an {@code @import "base/buttons.css"} resolve to exactly what it says. Read as
     * bytes, because a bundle's encoding cases are the point of several of these and a fixture
     * that decoded up front could not express one.
     */
    private static Map<String, byte[]> readSources(Path root) throws IOException {
        Map<String, byte[]> sources = new LinkedHashMap<>();

        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                String id = root.relativize(file).toString().replace('\\', '/');
                sources.put(id, Files.readAllBytes(file));
            }
        }

        return sources;
    }

    /**
     * The entry sources, in cascade order; blank lines and {@code #} comments are ignored.
     */
    private static List<String> readEntry(Path directory, String name) throws IOException {
        Path entry = directory.resolve(ENTRY_FILE);
        if (!Files.isRegularFile(entry)) {
            throw new IllegalStateException("bundle fixture '"
                                            + name
                                            + "' is missing "
                                            + ENTRY_FILE
                                            + ", which says which sources to bundle and in what order");
        }

        return Files.readAllLines(entry, StandardCharsets.UTF_8) //
                    .stream() //
                    .map(String::strip) //
                    .filter(line -> !line.isEmpty() && !line.startsWith("#")) //
                    .toList();
    }

    private static Map<String, String> readExpected(Path directory, String name) throws IOException {
        Path expectedDirectory = directory.resolve("expected");
        if (!Files.isDirectory(expectedDirectory)) {
            return Map.of();
        }

        Map<String, String> expected = new LinkedHashMap<>();

        try (Stream<Path> files = Files.list(expectedDirectory)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                String variant = file.getFileName().toString();
                if (variant.startsWith(".")) {
                    continue;
                }

                expected.put(variant, Files.readString(file, StandardCharsets.UTF_8).stripTrailing());
            }
        }

        return expected;
    }

    private static Path sourceDirectory(String name) {
        String configured = System.getProperty(SOURCE_DIR_PROPERTY);
        return configured == null ? null : Path.of(configured).resolve(name);
    }

    private static Path resourceRoot() {
        URL url = FixtureLoader.class.getClassLoader().getResource(RESOURCE_ROOT);
        if (url == null) {
            throw new IllegalStateException("classpath resource '" + RESOURCE_ROOT + "' not found");
        }

        try {
            return Path.of(url.toURI());
        }
        catch (URISyntaxException e) {
            throw new IllegalStateException("fixture root is not a filesystem path: " + url, e);
        }
    }
}
