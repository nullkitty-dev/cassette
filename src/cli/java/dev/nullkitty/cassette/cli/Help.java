package dev.nullkitty.cassette.cli;

/**
 * Usage text.
 */
final class Help {

    private Help() {
        // static-only
    }

    static String OVERVIEW = """
        cassette: a CSS parser, formatter and minifier

        usage: cassette <verb> [flags] <input…>

        Verbs
          format                     re-print, preserving what the CSS means
          minify                     strip whitespace and comments, nothing else
          check                      report diagnostics, write nothing

        `cassette <verb> --help` describes one verb's flags.
        """;

    static String forVerb(Verb verb) {
        return switch (verb) {
            case CHECK -> """
                usage: cassette check [flags] <input…>

                Parses every input and reports what it found. Writes no CSS anywhere, so it
                takes no destination. Exits 1 if anything was an error.

                Source maps
                      --source-map[=file|inline|none]  default none; bare --source-map
                                                       means file. 'file' writes <output>.map
                                                       beside the output and needs one, so it
                                                       cannot go to a pipe; 'inline' can
                      --source-map-url <url>           what the trailer names; default is the
                                                       map file's own name
                      --no-source-map-content          omit sourcesContent, which is most of
                                                       a map's size and all of what makes one
                                                       readable without the sources

                Input
                      --charset <name>       transport-supplied encoding; a BOM and an
                                             @charset rule both outrank it
                """ + bundling() + diagnostics() + common();

            case FORMAT, MINIFY -> "usage: cassette "
                                   + verb.verbName()
                                   + " [flags] <input…>\n"
                                   + (verb == Verb.MINIFY ? """

                                       Strips whitespace and comments and nothing else. Anything that changes what
                                       a stylesheet means is opt-in under -O, and is available under `format` too.
                                       """ : "\n")
                                   + """

                                       Output
                                         -o, --output <file>        write to one file (single input, or --bundle)
                                             --out-dir <dir>        write one output per input, names mirrored
                                         -i, --in-place             rewrite each input where it sits
                                                                    (default: standard output, single input only)

                                       Serialization
                                             --nesting preserve|flatten            default: preserve
                                             --expand is-wrap|duplicate            default: is-wrap; read only
                                                                                   when flattening
                                             --identifiers literal|ascii           default: literal
                                             --legacy                              legacy-safe defaults for the
                                                                                   three above
                                         -O, --optimize[=<list>]                   default: none
                                                                                   all | none | lowercase-names,
                                                                                   shorten-colors, drop-zero-units,
                                                                                   compact-numbers
                                                                                   'all' and a bare -O mean those
                                                                                   four; the two below rewrite what
                                                                                   the input claims about itself and
                                                                                   must be named explicitly:
                                                                                   drop-charset,
                                                                                   drop-source-map-url
                                                                                   -O repeats to compose, as
                                                                                   -O -O=drop-charset

                                       Source maps
                                             --source-map[=file|inline|none]  default none; bare --source-map
                                                                              means file. 'file' writes <output>.map
                                                                              beside the output and needs one, so it
                                                                              cannot go to a pipe; 'inline' can
                                             --source-map-url <url>           what the trailer names; default is the
                                                                              map file's own name
                                             --no-source-map-content          omit sourcesContent, which is most of
                                                                              a map's size and all of what makes one
                                                                              readable without the sources

                                       Input
                                             --charset <name>       transport-supplied encoding; a BOM and an
                                                                    @charset rule both outrank it
                                       """
                                   + bundling()
                                   + diagnostics()
                                   + common();
        };
    }

    /**
     * Shown under {@code check} too, where {@code --bundle} means "validate this import graph"
     * and is the one flag in the group that changes what {@code check} does.
     */
    private static String bundling() {
        return """

            Bundling
                  --bundle               inputs become one stylesheet in cascade order,
                                         so several of them take -o, or standard output
                  --import-root <dir>    resolve @import under this directory; repeatable
                                         (default: each input's own directory)
                  --no-imports           leave every @import in the output
                  --banners              a comment naming each source at its boundary
                  --max-import-depth <n> default 64

            An @import is resolved only inside a root, and never over a network. One that
            resolves to nothing is left in the output with a warning, which is what makes
            a web font survive bundling untouched.
            """;
    }

    private static String diagnostics() {
        return """

            Diagnostics
              -q, --quiet                errors only
                  --strict               warnings affect the exit code
                  --color auto|always|never
                  --diagnostic-format auto|rich|short
                                         rich draws the line and a caret under the span;
                                         auto is rich at a terminal and short in a pipe,
                                         where file:line:col is what gets scraped
                  --max-diagnostics <n>  default 100, then a count of the rest
            """;
    }

    private static String common() {
        return """

                  -h, --help[=<verb>]
                      --version

                A flag whose value is optional (-O and --help) takes it only attached with
                '=', because `-O style.css` would otherwise be ambiguous between a transform
                name and an input file. Everything else accepts both `--flag value` and
                `--flag=value`. '--' ends flag parsing; '-' means standard input.

                Exit codes
                  0  no errors
                  1  at least one error, or a warning under --strict
                  2  usage error
              3  I/O error
            """;
    }
}
