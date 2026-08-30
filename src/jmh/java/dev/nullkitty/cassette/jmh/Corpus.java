package dev.nullkitty.cassette.jmh;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/**
 * The benchmark corpus, loaded from {@code src/jmh/resources/corpus}.
 *
 * <p>Real-world CSS, not synthetic: generators reliably under-represent the deep nesting,
 * vendor prefixes and unusual selectors that authored and bundled stylesheets actually
 * contain, which is the shape a performance benchmark should be stressing.
 */
public enum Corpus {

    /**
     * Hand-written, small: the latency-dominated case.
     */
    SMALL("small-handwritten.css"),

    /**
     * Bootstrap, MIT-licensed. Vendored separately; see the corpus README.
     */
    MEDIUM("medium-bootstrap.css"),

    /**
     * Tailwind CLI output, large and highly repetitive. Vendored separately.
     */
    LARGE("large-generated-tailwind.css");

    private final String resource;

    Corpus(String resource) {
        this.resource = resource;
    }

    /**
     * Whether the file has been vendored; the two large entries are not in git.
     */
    public boolean isAvailable() {
        try (InputStream in = open()) {
            return in != null;
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Raw bytes, as the parser's real entry point takes them.
     */
    public byte[] bytes() {
        try (InputStream in = open()) {
            if (in == null) {
                throw new IllegalStateException("corpus file 'corpus/"
                                                + this.resource
                                                + "' is missing; see src/jmh/resources/corpus/README.md");
            }

            return in.readAllBytes();
        }
        catch (IOException e) {
            throw new UncheckedIOException("failed reading corpus/" + this.resource, e);
        }
    }

    private InputStream open() {
        return Corpus.class.getClassLoader().getResourceAsStream("corpus/" + this.resource);
    }
}
