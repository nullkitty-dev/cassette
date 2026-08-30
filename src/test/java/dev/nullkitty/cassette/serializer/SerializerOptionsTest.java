package dev.nullkitty.cassette.serializer;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The builder, and the one thing about it worth a test: that
 * {@link SerializerOptions.Builder#legacyCompatible()} supplies defaults rather than
 * overwriting choices.
 */
class SerializerOptionsTest {

    @Test
    void defaultsToNestedPrettyModernOutput() {
        assertThat(SerializerOptions.DEFAULTS).isEqualTo(new SerializerOptions(NestingMode.PRESERVE,
                                                                               Formatting.PRETTY,
                                                                               NestingExpansion.IS_WRAP,
                                                                               IdentifierEncoding.LITERAL));
    }

    @Test
    void legacyCompatibleFlipsEveryLegacyRelevantFlag() {
        SerializerOptions options = SerializerOptions.builder().legacyCompatible().build();

        assertThat(options.nesting()).isEqualTo(NestingMode.FLATTEN);
        assertThat(options.nestingExpansion()).isEqualTo(NestingExpansion.DUPLICATE);
        assertThat(options.identifierEncoding()).isEqualTo(IdentifierEncoding.ASCII);

        // Whitespace is not a compatibility question.
        assertThat(options.formatting()).isEqualTo(Formatting.PRETTY);
    }

    @Test
    void anExplicitChoiceOverridesThePreset() {
        SerializerOptions options =
            SerializerOptions.builder().legacyCompatible().identifierEncoding(IdentifierEncoding.LITERAL).build();

        assertThat(options.identifierEncoding()).isEqualTo(IdentifierEncoding.LITERAL);
        assertThat(options.nesting()).isEqualTo(NestingMode.FLATTEN);
    }

    @Test
    void anExplicitChoiceWinsWhicheverOrderTheyWereCalledIn() {
        SerializerOptions before =
            SerializerOptions.builder().identifierEncoding(IdentifierEncoding.LITERAL).legacyCompatible().build();

        assertThat(before.identifierEncoding()).isEqualTo(IdentifierEncoding.LITERAL);
    }

    @Test
    void toBuilderChangesOneThingAndKeepsTheRest() {
        SerializerOptions options =
            SerializerOptions.builder().legacyCompatible().build().toBuilder().formatting(Formatting.MINIFIED).build();

        assertThat(options.formatting()).isEqualTo(Formatting.MINIFIED);
        assertThat(options.identifierEncoding()).isEqualTo(IdentifierEncoding.ASCII);
    }
}
