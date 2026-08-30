/**
 * c(a)ssette: a fast, dependency-free CSS parser, transformer and serializer.
 *
 * <p>The {@code lexer} and {@code text} packages are never exported. The tokenizer/parser boundary
 * is internal, and everything a consumer needs is reachable through {@code parser}, {@code ast}
 * and {@code serializer}. {@code public} inside either therefore means "another package in this
 * module needs it", not "a consumer can call it".
 */
module dev.nullkitty.cassette {

    exports dev.nullkitty.cassette.ast;
    exports dev.nullkitty.cassette.diagnostics;
    exports dev.nullkitty.cassette.parser;
    exports dev.nullkitty.cassette.serializer;
    exports dev.nullkitty.cassette.bundle;
    exports dev.nullkitty.cassette.sourcemap;
}
