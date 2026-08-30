package dev.nullkitty.cassette.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import dev.nullkitty.cassette.bundle.BundleOptions;
import dev.nullkitty.cassette.serializer.Formatting;
import dev.nullkitty.cassette.serializer.IdentifierEncoding;
import dev.nullkitty.cassette.serializer.NestingExpansion;
import dev.nullkitty.cassette.serializer.NestingMode;
import dev.nullkitty.cassette.serializer.NodeTransform;
import dev.nullkitty.cassette.serializer.Optimizations;

/**
 * Flag-to-options mapping, which is where a CLI actually breaks.
 *
 * <p>Pure, fast and needs no file. What it asserts is that the right options reached the
 * library, never what the library then did with them, which golden fixtures already cover and
 * which would only ever fail here in pairs with one of those.
 */
class ArgumentsTest {

    @Nested
    class Verbs {

        @Test
        void theVerbOwnsTheFormattingAxis() throws UsageException {
            assertThat(options("format", "a.css").serializer().formatting()).isEqualTo(Formatting.PRETTY);
            assertThat(options("minify", "a.css").serializer().formatting()).isEqualTo(Formatting.MINIFIED);
        }

        @Test
        void rejectsAnUnknownOrMissingVerb() throws UsageException {
            assertThatThrownBy(() -> Arguments.parse(new String[] { "lint",
                                                                    "a.css" })).isInstanceOf(UsageException.class)
                                                                               .hasMessageContaining("unknown verb 'lint'")
                                                                               .hasMessageContaining("format, minify, check");

            assertThatThrownBy(() -> Arguments.parse(new String[0])).isInstanceOf(UsageException.class)
                                                                    .hasMessageContaining("no verb");

            assertThatThrownBy(() -> Arguments.parse(new String[] { "--strict",
                                                                    "a.css" })).isInstanceOf(UsageException.class)
                                                                               .hasMessageContaining("no verb");
        }

        @Test
        void helpAndVersionOutrankEverything() throws UsageException {
            assertThat(Arguments.parse(new String[] { "--version" })).isInstanceOf(Invocation.ShowVersion.class);

            assertThat(Arguments.parse(new String[] { "check",
                                                      "--version",
                                                      "a.css" })).isInstanceOf(Invocation.ShowVersion.class);

            assertThat(Arguments.parse(new String[] { "--help" })).isEqualTo(new Invocation.ShowHelp(null));

            // A verb already names itself, so `check --help` needs no value.
            assertThat(Arguments.parse(new String[] { "check",
                                                      "--help" })).isEqualTo(new Invocation.ShowHelp(Verb.CHECK));

            assertThat(Arguments.parse(new String[] { "--help=minify" })).isEqualTo(new Invocation.ShowHelp(Verb.MINIFY));
        }
    }

    @Nested
    class SerializerAxes {

        @Test
        void mapsEveryEnumValueInBothSpellings() throws UsageException {
            assertThat(options("format", "--nesting", "flatten", "a.css").serializer()
                                                                         .nesting()).isEqualTo(NestingMode.FLATTEN);

            assertThat(options("format", "--nesting=preserve", "a.css").serializer()
                                                                       .nesting()).isEqualTo(NestingMode.PRESERVE);

            assertThat(options("format",
                               "--expand=duplicate",
                               "a.css").serializer().nestingExpansion()).isEqualTo(NestingExpansion.DUPLICATE);

            assertThat(options("format",
                               "--expand=is-wrap",
                               "a.css").serializer().nestingExpansion()).isEqualTo(NestingExpansion.IS_WRAP);

            assertThat(options("format",
                               "--identifiers=ascii",
                               "a.css").serializer().identifierEncoding()).isEqualTo(IdentifierEncoding.ASCII);

            assertThat(options("format",
                               "--identifiers=literal",
                               "a.css").serializer().identifierEncoding()).isEqualTo(IdentifierEncoding.LITERAL);
        }

        @Test
        void namesTheAcceptedValuesWhenOneIsWrong() throws UsageException {
            assertThatThrownBy(() -> options("format",
                                             "--expand=iswrap",
                                             "a.css")).isInstanceOf(UsageException.class)
                                                      .hasMessageContaining("is-wrap|duplicate")
                                                      .hasMessageContaining("'iswrap'");
        }

        /**
         * The ordering case, and the reason this class asserts nothing about precedence itself:
         * {@code legacyCompatible()} fills in only what was not set explicitly, so an override
         * wins from either side without the parser knowing which came first.
         */
        @Test
        void legacyFillsInOnlyWhatWasNotSetExplicitly() throws UsageException {
            assertThat(options("format", "--legacy", "a.css").serializer()).satisfies(o -> {
                assertThat(o.nesting()).isEqualTo(NestingMode.FLATTEN);
                assertThat(o.nestingExpansion()).isEqualTo(NestingExpansion.DUPLICATE);
                assertThat(o.identifierEncoding()).isEqualTo(IdentifierEncoding.ASCII);
            });

            assertThat(options("format",
                               "--legacy",
                               "--nesting",
                               "preserve",
                               "a.css").serializer()).isEqualTo(options("format",
                                                                        "--nesting",
                                                                        "preserve",
                                                                        "--legacy",
                                                                        "a.css").serializer());

            assertThat(options("format",
                               "--legacy",
                               "--nesting",
                               "preserve",
                               "a.css").serializer().nesting()).isEqualTo(NestingMode.PRESERVE);
        }
    }

    @Nested
    class OptionalValues {

        @Test
        void bareOptimizeMeansAll() throws UsageException {
            // "All" is Optimizations.all(), not every name -O accepts; see Transform.
            assertThat(options("format", "-O", "a.css").optimizations()).containsExactlyElementsOf(Transform.inAll());

            assertThat(options("format",
                               "--optimize",
                               "a.css").optimizations()).containsExactlyElementsOf(Transform.inAll());

            assertThat(options("format", "a.css").optimizations()).isEmpty();
        }

        @Test
        void namesASubsetOnlyWhenAttached() throws UsageException {
            assertThat(options("format",
                               "-O=shorten-colors,compact-numbers",
                               "a.css").optimizations()).containsExactly(Transform.SHORTEN_COLORS,
                                                                         Transform.COMPACT_NUMBERS);

            assertThat(options("format", "--optimize=none", "a.css").optimizations()).isEmpty();
        }

        /**
         * The rule that makes the optional value unambiguous: the token after a bare {@code -O}
         * is an input, never a value. Resolving it any other way means guessing, does the file
         * exist, does the name look like a transform, and a rule nobody can predict from the
         * help text is worse than one extra character.
         */
        @Test
        void treatsTheTokenAfterABareOptimizeAsAnInput() throws UsageException {
            Options options = options("format", "-O", "style.css");

            assertThat(options.optimizations()).containsExactlyElementsOf(Transform.inAll());
            assertThat(options.inputs()).containsExactly("style.css");
        }
    }

    /**
     * {@code -O}, which is the one flag whose value is a set rather than a choice.
     *
     * <p>What is worth asserting is the two things a set has that a choice does not: that the
     * order it reaches the library is {@code Transform}'s and not the command line's, and that
     * the names it accepts are exactly the ones the library ships.
     */
    @Nested
    class Optimize {

        /**
         * Command-line order controls nothing, so it is not honoured. {@code Optimizer} fuses
         * every enabled transform into one walk and re-dispatches when one changes a node's
         * type, and accepting an order it never reads would invite a bug report about it.
         */
        @Test
        void ordersASubsetCanonicallyRatherThanAsWritten() throws UsageException {
            assertThat(options("format",
                               "-O=compact-numbers,lowercase-names",
                               "a.css").optimizations()).containsExactly(Transform.LOWERCASE_NAMES,
                                                                         Transform.COMPACT_NUMBERS);
        }

        /**
         * Every occurrence adds to one set, so repetition composes rather than conflicting.
         */
        @Test
        void accumulatesAcrossOccurrencesAndDropsDuplicates() throws UsageException {
            assertThat(options("format",
                               "-O=shorten-colors",
                               "-O=shorten-colors",
                               "--optimize=lowercase-names",
                               "a.css").optimizations()).containsExactly(Transform.LOWERCASE_NAMES,
                                                                         Transform.SHORTEN_COLORS);
            // `none` names no transform; it does not take away what another -O asked for,
            // which would need a rule about which side of it a name was written on.
            assertThat(options("format",
                               "-O=all",
                               "-O=none",
                               "a.css").optimizations()).containsExactlyElementsOf(Transform.inAll());
        }

        @Test
        void namesTheAcceptedValuesWhenOneIsWrong() throws UsageException {
            assertThatThrownBy(() -> options("format",
                                             "-O=shorten-colours",
                                             "a.css")).isInstanceOf(UsageException.class)
                                                      .hasMessageContaining("'shorten-colours'")
                                                      .hasMessageContaining("all, none")
                                                      .hasMessageContaining("shorten-colors");
        }

        /**
         * The agreement bare {@code -O} rests on, in both halves: same membership, same order.
         *
         * <p>This compares against the {@code inAll} subset rather than every constant, because the
         * two dropping transforms sit outside {@code Optimizations.all()}. Each removes an assertion
         * the input made about itself, and whether that assertion has gone false depends on what is
         * done with the output. Transforms are compared by the node types they declare, which is
         * what distinguishes them from the outside.
         */
        @Test
        void meansTheLibrarysAllForABareDashO() {
            List<?> canonical = Optimizations.all().stream().map(NodeTransform::types).toList();
            List<?> declared = Transform.inAll().stream().map(transform -> transform.transform().types()).toList();

            assertThat(declared).isEqualTo(canonical);
        }

        /**
         * And the other half, which the split above would otherwise have lost: every transform
         * the library offers is reachable by name here.
         *
         * <p>Found reflectively rather than listed, because a list is the thing that drifts. A
         * transform added to {@code Optimizations} and not named in the enum is unavailable from
         * the command line and absent from the help text, with nothing else to notice, which is
         * exactly the failure {@code all()} used to catch for free before it stopped being the
         * complete set.
         */
        @Test
        void namesEveryTransformTheLibraryOffers() {
            List<String> offered = Stream.of(Optimizations.class.getDeclaredMethods())
                                         .filter(method -> Modifier.isPublic(method.getModifiers()))
                                         .filter(method -> Modifier.isStatic(method.getModifiers()))
                                         .filter(method -> NodeTransform.class.isAssignableFrom(method.getReturnType()))
                                         .map(java.lang.reflect.Method::getName).sorted().toList();

            List<String> named =
                Stream.of(Transform.values()).map(transform -> camelCase(transform.name())).sorted().toList();

            assertThat(named).isEqualTo(offered);
        }

        /**
         * {@code DROP_SOURCE_MAP_URL} is {@code dropSourceMappingUrl}, so spell it out.
         */
        private static String camelCase(String constant) {
            return switch (constant) {
                case "DROP_SOURCE_MAP_URL" -> "dropSourceMappingUrl";
                default -> {
                    StringBuilder out = new StringBuilder();
                    for (String word : constant.toLowerCase(java.util.Locale.ROOT).split("_")) {
                        out.append(out.isEmpty() ? word : Character.toUpperCase(word.charAt(0)) + word.substring(1));
                    }
                    yield out.toString();
                }
            };
        }

        @Test
        void keepsTheDroppingTransformsOutOfABareDashO() throws UsageException {
            assertThat(options("format", "-O", "a.css").optimizations()).doesNotContain(Transform.DROP_CHARSET,
                                                                                        Transform.DROP_SOURCE_MAP_URL);

            assertThat(options("format",
                               "-O=all",
                               "a.css").optimizations()).doesNotContain(Transform.DROP_CHARSET,
                                                                        Transform.DROP_SOURCE_MAP_URL);
        }

        @Test
        void acceptsTheDroppingTransformsByName() throws UsageException {
            assertThat(options("format",
                               "-O=drop-charset,drop-source-map-url",
                               "a.css").optimizations()).containsExactly(Transform.DROP_CHARSET,
                                                                         Transform.DROP_SOURCE_MAP_URL);
        }

        /**
         * The help text lists the names by hand, so it can drift; this is what notices.
         */
        @Test
        void isSpelledOutInTheHelpText() {
            String help = Help.forVerb(Verb.MINIFY);

            for (Transform transform : Transform.values()) {
                assertThat(help).contains(Arguments.spell(transform));
            }
        }
    }

    @Nested
    class InputsAndDestinations {

        @Test
        void endsFlagParsingAtDoubleDashAndReadsStdinAtSingle() throws UsageException {
            assertThat(options("check", "--", "--weird-name.css").inputs()).containsExactly("--weird-name.css");
            assertThat(options("check", "-").inputs()).containsExactly("-");
        }

        @Test
        void requiresAnInput() throws UsageException {
            assertThatThrownBy(() -> options("check")).isInstanceOf(UsageException.class)
                                                      .hasMessageContaining("no input");
        }

        @Test
        void refusesADestinationForTheVerbThatWritesNothing() throws UsageException {
            assertThatThrownBy(() -> options("check", "-o", "out.css", "a.css")).isInstanceOf(UsageException.class)
                                                                                .hasMessageContaining("writes nothing");
        }

        @Test
        void refusesAnImplicitConcatenationOfSeveralInputs() throws UsageException {
            assertThatThrownBy(() -> options("minify",
                                             "a.css",
                                             "b.css")).isInstanceOf(UsageException.class)
                                                      .hasMessageContaining("several inputs need a destination");

            assertThatThrownBy(() -> options("minify",
                                             "-o",
                                             "out.css",
                                             "a.css",
                                             "b.css")).isInstanceOf(UsageException.class)
                                                      .hasMessageContaining("'-o' writes one file");

            assertThat(options("check", "a.css", "b.css").inputs()).containsExactly("a.css", "b.css");
        }

        @Test
        void refusesAnOutDirThatWouldWriteOneFileOverAnother() throws UsageException {
            // --out-dir mirrors file names, not the paths leading to them, so same-named
            // inputs in different directories collide. Silently writing one and discarding
            // the other is the worst available outcome.
            assertThatThrownBy(() -> options("minify",
                                             "--out-dir",
                                             "out",
                                             "a/x.css",
                                             "b/x.css")).isInstanceOf(UsageException.class)
                                                        .hasMessageContaining("two inputs are named 'x.css'");

            assertThat(options("minify", "--out-dir", "out", "a/x.css", "b/y.css").inputs()).hasSize(2);
        }

        @Test
        void refusesAnOutDirForStandardInput() throws UsageException {
            assertThatThrownBy(() -> options("minify",
                                             "--out-dir",
                                             "out",
                                             "-")).isInstanceOf(UsageException.class)
                                                  .hasMessageContaining("cannot take standard input");
        }

        @Test
        void refusesToRewriteStandardInputAndToPickTwoDestinations() throws UsageException {
            assertThatThrownBy(() -> options("format",
                                             "--in-place",
                                             "-")).isInstanceOf(UsageException.class)
                                                  .hasMessageContaining("cannot rewrite standard input");

            assertThatThrownBy(() -> options("format",
                                             "-i",
                                             "-o",
                                             "out.css",
                                             "a.css")).isInstanceOf(UsageException.class)
                                                      .hasMessageContaining("at most one");
        }
    }

    @Nested
    class Bundling {

        @Test
        void mapsTheBundlingFlags() throws UsageException {
            Options options = options("format",
                                      "--bundle",
                                      "--import-root",
                                      "src",
                                      "--import-root=vendor",
                                      "--no-imports",
                                      "--banners",
                                      "--max-import-depth",
                                      "8",
                                      "a.css");

            assertThat(options.bundle()).isTrue();
            assertThat(options.importRoots()).containsExactly(Path.of("src"), Path.of("vendor"));
            assertThat(options.noImports()).isTrue();
            assertThat(options.banners()).isTrue();
            assertThat(options.maxImportDepth()).isEqualTo(8);
        }

        @Test
        void defaultsToNoBundlingAndTheLibrarysDepth() throws UsageException {
            Options options = options("format", "a.css");

            assertThat(options.bundle()).isFalse();
            assertThat(options.importRoots()).isEmpty();
            assertThat(options.noImports()).isFalse();
            assertThat(options.banners()).isFalse();
            assertThat(options.maxImportDepth()).isEqualTo(BundleOptions.DEFAULT_MAX_IMPORT_DEPTH);
        }

        /**
         * What the flag is for: several inputs stop needing a per-input destination.
         */
        @Test
        void makesSeveralInputsLegalWithOneDestinationAndWithNone() throws UsageException {
            assertThat(options("minify",
                               "--bundle",
                               "-o",
                               "out.css",
                               "a.css",
                               "b.css").inputs()).containsExactly("a.css", "b.css");

            assertThat(options("minify", "--bundle", "a.css", "b.css").writesToStdout()).isTrue();
        }

        @Test
        void refusesThePerInputDestinations() throws UsageException {
            assertThatThrownBy(() -> options("format",
                                             "--bundle",
                                             "--out-dir",
                                             "out",
                                             "a.css")).isInstanceOf(UsageException.class)
                                                      .hasMessageContaining("no per-input name for '--out-dir' to mirror");

            assertThatThrownBy(() -> options("format",
                                             "--bundle",
                                             "-i",
                                             "a.css")).isInstanceOf(UsageException.class)
                                                      .hasMessageContaining("no input for '--in-place' to rewrite");
        }

        /**
         * {@code BundleOptions} throws {@code IllegalArgumentException} for a depth below 1, and
         * a constructor's exception reaching a command-line user as a stack trace is the wrong
         * way to say that.
         */
        @Test
        void rejectsADepthTheLibraryWouldThrowOn() throws UsageException {
            assertThatThrownBy(() -> options("check",
                                             "--bundle",
                                             "--max-import-depth=0",
                                             "a.css")).isInstanceOf(UsageException.class)
                                                      .hasMessageContaining("expected at least 1");

            assertThatThrownBy(() -> options("check",
                                             "--bundle",
                                             "--max-import-depth=-3",
                                             "a.css")).isInstanceOf(UsageException.class)
                                                      .hasMessageContaining("expected a count");
        }

        /**
         * The same answer {@code --expand} gets under {@code --nesting preserve}: a flag group
         * that does nothing in this company is accepted rather than refused, because refusing
         * would make it the first one carrying a rule about which flags it may sit beside.
         */
        @Test
        void acceptsTheGroupWithoutBundleAndDoesNothingWithIt() throws UsageException {
            Options options = options("format", "--import-root=src", "--banners", "a.css");

            assertThat(options.bundle()).isFalse();
            assertThat(options.banners()).isTrue();
        }

        /**
         * {@code check --bundle} validates an import graph, so the flag is not verb-specific.
         */
        @Test
        void isAcceptedByCheckWhichStillTakesNoDestination() throws UsageException {
            assertThat(options("check", "--bundle", "a.css", "b.css").bundle()).isTrue();

            assertThatThrownBy(() -> options("check",
                                             "--bundle",
                                             "-o",
                                             "out.css",
                                             "a.css")).isInstanceOf(UsageException.class)
                                                      .hasMessageContaining("writes nothing");
        }
    }

    @Nested
    class Diagnostics {

        @Test
        void mapsTheDiagnosticFlags() throws UsageException {
            Options options = options("check", "-q", "--strict", "--color=never", "--max-diagnostics", "5", "a.css");

            assertThat(options.quiet()).isTrue();
            assertThat(options.strict()).isTrue();
            assertThat(options.color()).isEqualTo(Color.NEVER);
            assertThat(options.maxDiagnostics()).isEqualTo(5);
        }

        @Test
        void defaultsMaxDiagnosticsAndColour() throws UsageException {
            Options options = options("check", "a.css");

            assertThat(options.maxDiagnostics()).isEqualTo(Options.DEFAULT_MAX_DIAGNOSTICS);
            assertThat(options.color()).isEqualTo(Color.AUTO);
            assertThat(options.format()).isEqualTo(DiagnosticFormat.AUTO);
            assertThat(options.quiet()).isFalse();
            assertThat(options.strict()).isFalse();
        }

        /**
         * The same shape as {@code --color}, and for the same reason: a redirected stream is
         * read by a machine, and {@code file:line:col} is what gets scraped.
         */
        @Test
        void mapsTheDiagnosticFormat() throws UsageException {
            assertThat(options("check", "--diagnostic-format=rich", "a.css").format()).isEqualTo(DiagnosticFormat.RICH);

            assertThat(options("check",
                               "--diagnostic-format",
                               "short",
                               "a.css").format()).isEqualTo(DiagnosticFormat.SHORT);

            assertThatThrownBy(() -> options("check",
                                             "--diagnostic-format=fancy",
                                             "a.css")).isInstanceOf(UsageException.class)
                                                      .hasMessageContaining("auto|rich|short");
        }

        /**
         * Off the terminal, which is where the tests run, {@code AUTO} is the short form.
         */
        @Test
        void resolvesAutoToShortWhenNotAtATerminal() {
            assertThat(DiagnosticFormat.AUTO.rich()).isEqualTo(System.console() != null);
            assertThat(DiagnosticFormat.RICH.rich()).isTrue();
            assertThat(DiagnosticFormat.SHORT.rich()).isFalse();
        }

        @Test
        void rejectsBadValues() throws UsageException {
            assertThatThrownBy(() -> options("check",
                                             "--max-diagnostics=lots",
                                             "a.css")).isInstanceOf(UsageException.class)
                                                      .hasMessageContaining("expected a count");

            assertThatThrownBy(() -> options("check",
                                             "--max-diagnostics=-1",
                                             "a.css")).isInstanceOf(UsageException.class)
                                                      .hasMessageContaining("expected a count");

            assertThatThrownBy(() -> options("check",
                                             "--color=beige",
                                             "a.css")).isInstanceOf(UsageException.class)
                                                      .hasMessageContaining("auto|always|never");
        }
    }

    @Nested
    class Shape {

        @Test
        void rejectsAValueOnAFlagThatTakesNone() throws UsageException {
            // Rejected rather than ignored: `--strict=yes` reads as though `--strict=no` would
            // also work, and it would not.
            assertThatThrownBy(() -> options("check", "--strict=yes", "a.css")).isInstanceOf(UsageException.class)
                                                                               .hasMessageContaining("takes no value");
        }

        @Test
        void rejectsAMissingValueRatherThanConsumingTheInput() throws UsageException {
            assertThatThrownBy(() -> options("check", "a.css", "--charset")).isInstanceOf(UsageException.class)
                                                                            .hasMessageContaining("needs a value");
        }

        @Test
        void rejectsAnUnknownFlagWithoutSwallowingWhatFollows() throws UsageException {
            assertThatThrownBy(() -> options("check", "--nope", "a.css")).isInstanceOf(UsageException.class)
                                                                         .hasMessageContaining("unknown flag '--nope'");
        }

        @Test
        void resolvesTheCharsetToALibraryType() throws UsageException {
            assertThat(options("check", "--charset=utf-16", "a.css").charset()).isEqualTo(StandardCharsets.UTF_16);
            assertThat(options("check", "a.css").charset()).isNull();

            assertThatThrownBy(() -> options("check",
                                             "--charset=nonsense",
                                             "a.css")).isInstanceOf(UsageException.class)
                                                      .hasMessageContaining("no encoding");
        }
    }

    // -----------------------------------------------------------------------

    private static Options options(String... args) throws UsageException {
        Invocation invocation = Arguments.parse(args);
        assertThat(invocation).isInstanceOf(Invocation.Run.class);
        return ((Invocation.Run) invocation).options();
    }
}
