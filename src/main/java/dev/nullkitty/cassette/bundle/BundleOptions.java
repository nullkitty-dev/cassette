package dev.nullkitty.cassette.bundle;

import java.util.Objects;
import java.util.Optional;

/**
 * What to do while bundling, beyond the sources themselves.
 *
 * <pre>{@code
 * BundleOptions options = BundleOptions.builder()
 *         .importer(files)
 *         .banners(true)
 *         .build();
 * }</pre>
 *
 * <p>A builder over a record, mirroring {@code SerializerOptions}, so that adding an option
 * later costs callers nothing.
 *
 * @param importer       how to resolve an {@code @import}, or {@code null} to resolve none,
 *                       in which case every import is left in the output and bundling is pure
 *                       concatenation, which needs no importer to be useful
 * @param banners        whether to mark each source's contents with a comment naming it
 * @param maxImportDepth how deep {@code @import} may nest before the chain is cut
 */
public record BundleOptions(Importer importer, //
                            boolean banners,
                            int maxImportDepth) {

    /**
     * Far past any real import graph, far short of anything that threatens the stack.
     *
     * <p>The parser's own recursion bound is 512 and exists for the same reason: a bound that
     * no actual input reaches, so that reaching it is evidence of something else.
     */
    public static final int DEFAULT_MAX_IMPORT_DEPTH = 64;

    /**
     * No importer, no banners, depth 64.
     */
    public static final BundleOptions DEFAULTS = new BundleOptions(null, //
                                                                   false,
                                                                   DEFAULT_MAX_IMPORT_DEPTH);

    /**
     * @throws IllegalArgumentException if {@code maxImportDepth} is not positive
     */
    public BundleOptions {
        if (maxImportDepth < 1) {
            throw new IllegalArgumentException("maxImportDepth must be at least 1: " + maxImportDepth);
        }
    }

    /**
     * @return a builder holding the defaults
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Assembles a {@link BundleOptions}.
     */
    public static final class Builder {

        private Importer importer;
        private boolean  banners;
        private int      maxImportDepth = DEFAULT_MAX_IMPORT_DEPTH;

        private Builder() {
            // BundleOptions.builder()
        }

        /**
         * Resolves {@code @import}s through this importer.
         *
         * @param importer the importer, or {@code null} to resolve none
         * @return this builder
         */
        public Builder importer(Importer importer) {
            this.importer = importer;
            return this;
        }

        /**
         * Marks each source's contents with a comment naming it.
         *
         * <p>An ordinary AST comment, which is what makes this free everywhere else:
         * {@code Formatting.MINIFIED} strips it like any other comment, passthrough
         * serialization keeps it, and no serializer option or fixture axis had to grow for it.
         *
         * @param banners whether to insert them
         * @return this builder
         */
        public Builder banners(boolean banners) {
            this.banners = banners;
            return this;
        }

        /**
         * How deep {@code @import} may nest.
         *
         * @param maxImportDepth the bound, at least 1
         * @return this builder
         */
        public Builder maxImportDepth(int maxImportDepth) {
            this.maxImportDepth = maxImportDepth;
            return this;
        }

        /**
         * @return the options
         */
        public BundleOptions build() {
            return new BundleOptions(this.importer, this.banners, this.maxImportDepth);
        }
    }

    /**
     * Whether any {@code @import} will be resolved at all.
     *
     * @return whether an importer is configured
     */
    public boolean resolvesImports() {
        return this.importer != null;
    }

    /**
     * @param specifier what to resolve
     * @param from      where the import sits
     * @return the imported source, or empty
     */
    Optional<Source> resolve(String specifier, //
                             Origin from) {
        return Objects.requireNonNull(this.importer.resolve(specifier, from),
                                      "an importer returned null; return Optional.empty() to decline");
    }
}
