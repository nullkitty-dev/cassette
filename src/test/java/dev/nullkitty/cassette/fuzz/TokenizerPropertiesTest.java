package dev.nullkitty.cassette.fuzz;

import static org.assertj.core.api.Assertions.assertThat;

import dev.nullkitty.cassette.lexer.SourceText;
import dev.nullkitty.cassette.lexer.TokenType;
import dev.nullkitty.cassette.lexer.Tokenizer;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;

/**
 * Invariants the tokenizer holds for <em>any</em> input, valid or not.
 *
 * <p>Only the token stream is covered here. The parser-level properties, diagnostics land on
 * real spans and recovered output re-parses to itself, are in {@link ParserPropertiesTest}
 * and {@link SerializerPropertiesTest}.
 */
class TokenizerPropertiesTest {

    @Property
    void neverThrows(@ForAll(supplier = CssLikeArbitraries.Bytes.class) byte[] input) {
        Tokenizer tokenizer = new Tokenizer(SourceText.decode(input));

        while (tokenizer.next() != TokenType.EOF) {
            // Touching every accessor: a span that is fine to compute but not to slice would
            // otherwise go unnoticed until the parser tried to read it.
            tokenizer.raw();
            tokenizer.value();
            tokenizer.span();
            tokenizer.valueSpan();
        }
    }

    @Property
    void alwaysTerminates(@ForAll(supplier = CssLikeArbitraries.Text.class) String input) {
        SourceText source = SourceText.of(input);
        Tokenizer tokenizer = new Tokenizer(source);

        // Every token consumes at least one character, so the stream cannot be longer than
        // the input. A zero-width token would spin forever in the parser instead.
        int budget = source.length() + 1;
        while (tokenizer.next() != TokenType.EOF) {
            budget--;

            assertThat(budget).as("tokenizer made no progress at offset %d", tokenizer.start()).isPositive();
        }
    }

    @Property
    void tokensTileTheInputExactly(@ForAll(supplier = CssLikeArbitraries.Text.class) String input) {
        SourceText source = SourceText.of(input);
        Tokenizer tokenizer = new Tokenizer(source);

        int previousEnd = 0;
        while (tokenizer.next() != TokenType.EOF) {
            assertThat(tokenizer.start()).as("no gap or overlap between tokens").isEqualTo(previousEnd);
            assertThat(tokenizer.end()).isGreaterThan(tokenizer.start());
            previousEnd = tokenizer.end();
        }

        assertThat(previousEnd).as("tokens cover the whole input").isEqualTo(source.length());
    }

    @Property
    void valueSpansStayInsideTheirToken(@ForAll(supplier = CssLikeArbitraries.Text.class) String input) {
        SourceText source = SourceText.of(input);
        Tokenizer tokenizer = new Tokenizer(source);

        while (tokenizer.next() != TokenType.EOF) {
            assertThat(tokenizer.valueStart()).isGreaterThanOrEqualTo(tokenizer.start());
            assertThat(tokenizer.valueEnd()).isLessThanOrEqualTo(tokenizer.end());
            assertThat(tokenizer.valueStart()).isLessThanOrEqualTo(tokenizer.valueEnd());
        }
    }

    @Property
    void concatenatedRawTextReconstructsTheSource(@ForAll(supplier = CssLikeArbitraries.Text.class) String input) {
        SourceText source = SourceText.of(input);
        Tokenizer tokenizer = new Tokenizer(source);

        StringBuilder rebuilt = new StringBuilder();
        while (tokenizer.next() != TokenType.EOF) {
            rebuilt.append(tokenizer.raw());
        }

        // Not the same as the original input: preprocessing has already normalized newlines
        // and NULLs. It is the same as what the tokenizer was actually given.
        assertThat(rebuilt.toString()).isEqualTo(source.toString());
    }

    @Property
    void escapeFreeTokensHaveAValueEqualToTheirSourceText(@ForAll(
        supplier = CssLikeArbitraries.Text.class) String input) {
        SourceText source = SourceText.of(input);
        Tokenizer tokenizer = new Tokenizer(source);

        while (tokenizer.next() != TokenType.EOF) {
            if (tokenizer.hasEscape()) {
                continue;
            }

            assertThat(tokenizer.value()).isEqualTo(source.subSequence(tokenizer.valueStart(), tokenizer.valueEnd()));
        }
    }
}
