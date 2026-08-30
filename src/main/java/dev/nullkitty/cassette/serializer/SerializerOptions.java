package dev.nullkitty.cassette.serializer;

import java.util.Objects;

/**
 * Everything the serializer needs to know, as one immutable value.
 *
 * <pre>{@code
 * SerializerOptions options = SerializerOptions.builder()
 *         .legacyCompatible()               // flips every legacy-safe flag at once
 *         .identifierEncoding(IdentifierEncoding.LITERAL)  // ...but this one still wins
 *         .formatting(Formatting.MINIFIED)
 *         .build();
 * }</pre>
 *
 * <p>{@link Builder#legacyCompatible()} is sugar over the individual setters rather than a second
 * code path. It supplies the legacy-safe default for every flag nobody set explicitly, and an
 * explicit setter always wins whichever order the two were called in. There is therefore no
 * registry of legacy-relevant options to keep in sync: a new one joins the group by having a legacy
 * default.
 *
 * @param nesting            whether nested rules stay nested
 * @param formatting         how much whitespace the output carries
 * @param nestingExpansion   how {@code &} is expanded when flattening
 * @param identifierEncoding whether non-ASCII characters are escaped
 */
public record SerializerOptions(NestingMode nesting, //
                                Formatting formatting,
                                NestingExpansion nestingExpansion,
                                IdentifierEncoding identifierEncoding) {

    /**
     * Nesting preserved, pretty-printed, {@code :is()}-wrapping, literal Unicode.
     */
    public static final SerializerOptions DEFAULTS = builder().build();

    /**
     * Rejects a null field, so a serializer never has to.
     *
     * @throws NullPointerException if any argument is {@code null}
     */
    public SerializerOptions {
        Objects.requireNonNull(nesting, "nesting");
        Objects.requireNonNull(formatting, "formatting");
        Objects.requireNonNull(nestingExpansion, "nestingExpansion");
        Objects.requireNonNull(identifierEncoding, "identifierEncoding");
    }

    /**
     * A builder holding no choices yet.
     *
     * @return a fresh builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Whether output should be stripped of whitespace and comments.
     *
     * @return whether {@link #formatting()} is {@link Formatting#MINIFIED}
     */
    public boolean isMinified() {
        return this.formatting == Formatting.MINIFIED;
    }

    /**
     * A builder starting from these options, for changing one of them.
     *
     * @return a builder with every field of this instance set explicitly
     */
    public Builder toBuilder() {
        return new Builder().nesting(this.nesting) //
                            .formatting(this.formatting) //
                            .nestingExpansion(this.nestingExpansion) //
                            .identifierEncoding(this.identifierEncoding);
    }

    /**
     * Collects serializer choices, then freezes them.
     *
     * <p>Every field is unset until someone sets it. That is what lets
     * {@link #legacyCompatible()} be order-independent: it flips a flag that only changes
     * the defaults, so an explicit choice is never overwritten.
     */
    public static final class Builder {

        private NestingMode        nesting;
        private Formatting         formatting;
        private NestingExpansion   nestingExpansion;
        private IdentifierEncoding identifierEncoding;
        private boolean            legacy;

        private Builder() {
            // through SerializerOptions.builder()
        }

        /**
         * Whether nested rules stay nested.
         *
         * @param mode the nesting mode; legacy default {@link NestingMode#FLATTEN}
         * @return this builder
         */
        public Builder nesting(NestingMode mode) {
            this.nesting = Objects.requireNonNull(mode, "mode");
            return this;
        }

        /**
         * How much whitespace the output carries.
         *
         * @param mode the formatting mode; not a legacy-relevant choice
         * @return this builder
         */
        public Builder formatting(Formatting mode) {
            this.formatting = Objects.requireNonNull(mode, "mode");
            return this;
        }

        /**
         * How {@code &} is expanded when flattening.
         *
         * @param expansion the expansion; legacy default {@link NestingExpansion#DUPLICATE}
         * @return this builder
         */
        public Builder nestingExpansion(NestingExpansion expansion) {
            this.nestingExpansion = Objects.requireNonNull(expansion, "expansion");
            return this;
        }

        /**
         * Whether non-ASCII characters are escaped.
         *
         * @param encoding the encoding; legacy default {@link IdentifierEncoding#ASCII}
         * @return this builder
         */
        public Builder identifierEncoding(IdentifierEncoding encoding) {
            this.identifierEncoding = Objects.requireNonNull(encoding, "encoding");
            return this;
        }

        /**
         * Takes the legacy-safe default for every flag not set explicitly: flattened
         * nesting, duplicated selectors instead of {@code :is()}, ASCII-escaped identifiers.
         *
         * @return this builder
         */
        public Builder legacyCompatible() {
            this.legacy = true;
            return this;
        }

        /**
         * Freezes the choices made so far, filling the rest in from the defaults.
         *
         * @return the options
         */
        public SerializerOptions build() {
            return new SerializerOptions(orDefault(this.nesting, NestingMode.FLATTEN, NestingMode.PRESERVE),
                                         orDefault(this.formatting, Formatting.PRETTY, Formatting.PRETTY),
                                         orDefault(this.nestingExpansion,
                                                   NestingExpansion.DUPLICATE,
                                                   NestingExpansion.IS_WRAP),
                                         orDefault(this.identifierEncoding,
                                                   IdentifierEncoding.ASCII,
                                                   IdentifierEncoding.LITERAL));
        }

        private <T> T orDefault(T explicit, T legacyDefault, T modernDefault) {
            if (explicit != null) {
                return explicit;
            }

            return this.legacy ? legacyDefault : modernDefault;
        }
    }
}
