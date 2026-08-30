package dev.nullkitty.cassette.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.nullkitty.cassette.diagnostics.Diagnostic;
import dev.nullkitty.cassette.parser.CssParser;
import dev.nullkitty.cassette.serializer.CssSerializer;
import dev.nullkitty.cassette.serializer.Optimizer;

/**
 * Exit codes and the {@code check} verb, driven through {@link Cli#run} as a library.
 *
 * <p>{@code run} takes its streams and returns a code, so nothing here forks a JVM or installs
 * anything to intercept {@code System.exit}, which is the whole reason {@code main} is a
 * three-line wrapper over it.
 */
class CliTest {

    @TempDir
    Path dir;

    @Test
    void exitsZeroOnCleanCss() {
        Result result = check("a { color: red }\n");

        assertThat(result.code()).isEqualTo(Cli.OK);
        assertThat(result.err()).isEmpty();
        assertThat(result.out()).isEmpty();
    }

    @Test
    void exitsOneOnAnError() {
        Result result = check(".b { background: rgb(0 0 0; }\n");

        assertThat(result.code()).isEqualTo(Cli.DIAGNOSTICS);
        assertThat(result.err()).contains("error: unclosed function rgb()");
    }

    /**
     * Warnings must not fail a build by default: a forgiving {@code :is()} list dropping an
     * alternative is a warning by design, and real CSS produces them routinely.
     */
    @Test
    void warningsAffectTheExitCodeOnlyUnderStrict() {
        String css = "@charset \"nonsense\";\na { color: red }\n";

        assertThat(check(css).code()).isEqualTo(Cli.OK);
        assertThat(check(css).err()).contains("warning:");
        assertThat(run(css, "check", "--strict", file(css).toString()).code()).isEqualTo(Cli.DIAGNOSTICS);
    }

    /**
     * Quiet is a display decision and strict is not, so hiding a warning must not also excuse
     * it. The two flags are independent and this pins that they stay so.
     */
    @Test
    void quietHidesWarningsWithoutExcusingThem() {
        String css = "@charset \"nonsense\";\na { color: red }\n";

        Result quiet = run(css, "check", "--quiet", file(css).toString());
        assertThat(quiet.err()).isEmpty();
        assertThat(quiet.code()).isEqualTo(Cli.OK);

        assertThat(run(css, "check", "--quiet", "--strict", file(css).toString()).code()).isEqualTo(Cli.DIAGNOSTICS);
    }

    @Test
    void summarizesWhatMaxDiagnosticsHeldBack() {
        Result result = run("",
                            "check",
                            "--max-diagnostics=1",
                            "--color=never",
                            file(".b { background: rgb(0 0 0; }\n").toString());

        assertThat(result.err()).containsOnlyOnce("error:").contains("... and 2 more (--max-diagnostics 1)");
    }

    @Test
    void reportsInSourceOrderRatherThanTheOrderRecoveryFoundThem() {
        Result result = check(".b { background: rgb(0 0 0; }\n");

        // The parser reports the unmatched '}' first, having met it while consuming the
        // unclosed function; a reader going down their file wants the opposite.
        assertThat(result.err().indexOf(":1:4:")).isLessThan(result.err().indexOf(":1:18:"));
        assertThat(result.err().indexOf(":1:18:")).isLessThan(result.err().indexOf(":1:29:"));
    }

    /**
     * The rich form end to end, including the blank line between two snippets. That is not the
     * gutter line the format drops, but the thing that keeps two diagnostics from reading as one
     * block.
     */
    @Test
    void drawsSnippetsUnderDiagnosticsWhenAskedTo() {
        Result result = run("",
                            "check",
                            "--color=never",
                            "--diagnostic-format=rich",
                            file(".b { background: rgb(0 0 0; }\n").toString());

        assertThat(result.err()).contains("  |                             ^").contains("\n\nerror:");

        // The short form's shape must be gone, not merely supplemented.
        assertThat(result.err()).doesNotContain("note:   ");
    }

    /**
     * And the machine-readable form is what a redirected stream gets by default.
     */
    @Test
    void keepsTheScrapableShapeByDefaultAndUnderShort() {
        String css = ".b { background: rgb(0 0 0; }\n";

        for (String[] args : List.of(new String[0], new String[] { "--diagnostic-format=short" })) {
            java.util.List<String> command = new java.util.ArrayList<>(List.of("check", "--color=never"));
            command.addAll(List.of(args));
            command.add(file(css).toString());

            Result result = run("", command.toArray(String[]::new));

            assertThat(result.err()).contains("note:   ").doesNotContain(" --> ");
        }
    }

    @Test
    void exitsThreeWhenAnInputCannotBeRead() {
        Result result = run("", "check", this.dir.resolve("absent.css").toString());

        assertThat(result.code()).isEqualTo(Cli.IO);
        assertThat(result.err()).contains("no such file");
    }

    @Test
    void exitsTwoOnAUsageError() {
        Result result = run("", "check", "--nope", "a.css");

        assertThat(result.code()).isEqualTo(Cli.USAGE);
        assertThat(result.err()).contains("unknown flag '--nope'").contains("try 'cassette --help'");
    }

    @Test
    void readsStandardInputForADashAndNamesItInDiagnostics() {
        Result result = run(".b { background: rgb(0 0 0; }\n", "check", "-");

        assertThat(result.code()).isEqualTo(Cli.DIAGNOSTICS);
        assertThat(result.err()).contains("<stdin>:1:");
    }

    @Test
    void printsHelpAndVersionToStandardOutput() {
        assertThat(run("", "--version")).satisfies(result -> {
            assertThat(result.code()).isEqualTo(Cli.OK);
            assertThat(result.out()).startsWith("cassette ");
        });

        assertThat(run("", "--help").out()).contains("usage: cassette <verb>");
        assertThat(run("", "check", "--help").out()).contains("usage: cassette check").contains("takes no destination");
        assertThat(run("", "minify", "--help").out()).contains("-O, --optimize");
    }

    @Test
    void formatAndMinifyWriteCssToStandardOutput() {
        String css = "a { color: red; background: blue }\n.b > .c { top: 0 }\n";

        Result pretty = run("", "format", file(css).toString());
        assertThat(pretty.code()).isEqualTo(Cli.OK);
        assertThat(pretty.err()).isEmpty();

        assertThat(pretty.out()).isEqualTo("""
            a {
              color: red;
              background: blue;
            }

            .b > .c {
              top: 0;
            }
            """);

        assertThat(run("", "minify", file(css).toString()).out()).isEqualTo("a{color:red;background:blue}.b>.c{top:0}");
    }

    /**
     * The CLI adds nothing of its own to the library's output: not even a trailing newline,
     * which is tempting for a minified file and would be the CLI inventing a byte. Every
     * behaviour it has is meant to be the library's with a flag in front of it, and this is the
     * assertion that keeps that literally true.
     */
    @Test
    void writesExactlyWhatTheSerializerReturns() {
        String css = ".card { color: red; & .title { top: 0 } }\n";
        Path path = file(css);

        for (String[] args : new String[][] { { "minify", path.toString() },
                                              { "format", path.toString() },
                                              { "minify", "--nesting=flatten", path.toString() },
                                              { "format", "--legacy", path.toString() } }) {
            Options options = parse(args);
            String expected =
                CssSerializer.serialize(CssParser.parse(css).ast(), options.serializer(), Diagnostic.DISCARD);

            assertThat(run("", args).out()).as("%s", String.join(" ", args)).isEqualTo(expected);
        }
    }

    /**
     * The serializer reports through a sink of its own, and a {@code url()} it cannot spell is
     * its own limitation rather than something the parse already named. Wiring the two-argument
     * overload instead would throw away the one diagnostic nothing else in the system produces,
     * and would do it silently.
     */
    @Test
    void reportsWhatTheSerializerHadToDrop() {
        Result result = run("", "minify", "--color=never", file("a { background: url(\"\n) }\n").toString());

        assertThat(result.err()).contains("warning: url() contents cannot be written");
        assertThat(result.out()).isEqualTo("a{background:}");
    }

    /**
     * Recovery is defined behaviour, so a stylesheet with errors still produces output and the
     * errors describe what recovery did to it. A script that reads exit 1 as "nothing was
     * written" is reading it wrong, which is worth pinning rather than leaving to be discovered.
     */
    @Test
    void stillWritesOutputWhenTheExitCodeIsNonzero() {
        Result result =
            run("", "minify", "--color=never", file("a { color: red }\n.b { background: rgb(0 0 0; }\n").toString());

        assertThat(result.code()).isEqualTo(Cli.DIAGNOSTICS);
        assertThat(result.err()).contains("error:");
        assertThat(result.out()).startsWith("a{color:red}");
    }

    @Test
    void checkWritesNoCssAtAll() {
        assertThat(run("", "check", file("a { color: red }\n").toString()).out()).isEmpty();
    }

    /**
     * {@code cassette format} is a fixed point on its own output, by the same mechanism and for
     * the same reason as the serializer property the fuzz suite asserts. Stated here as a
     * standing check rather than as coverage: if it ever fails the defect is in the library and
     * the CLI is only the thing that made it visible.
     *
     * <p>The input is recovered wreckage, because that is the case where the first pass is allowed
     * to normalize and every pass after it is not.
     */
    @Test
    void formatIsAFixedPointOnItsOwnOutput() {
        String css = "@media print { .a { color: red; & .b { top: 0 } } }\n" + ".c { background: rgb(0 0 0; }\n";

        for (String[] flags : new String[][] { {},
                                               { "--nesting=flatten" },
                                               { "--legacy" },
                                               { "--identifiers=ascii" } }) {
            String once = format(css, flags);
            String twice = format(once, flags);

            assertThat(twice).as("re-formatting %s output", String.join(" ", flags)).isEqualTo(once);
        }
    }

    private String format(String css, String[] flags) {
        String[] args = new String[flags.length + 2];
        args[0] = "format";
        System.arraycopy(flags, 0, args, 1, flags.length);
        args[args.length - 1] = file(css).toString();
        return run("", args).out();
    }

    /**
     * {@code minify} means what {@code Formatting.MINIFIED} means and nothing else, so a
     * transform runs only where it was asked for and under whichever verb asked.
     *
     * <p>The input carries one case for each of the four: an uppercase at-keyword, a hex colour
     * that halves, a zero length and a number with a trailing zero.
     */
    @Test
    void runsATransformOnlyWhereItWasAskedFor() {
        String css = "@MEDIA print { a { color: #AABBCC; margin: 0px; opacity: 0.50 } }\n";

        assertThat(run("",
                       "minify",
                       file(css).toString()).out()).isEqualTo("@MEDIA print{a{color:#AABBCC;margin:0px;opacity:0.50}}");

        assertThat(run("",
                       "minify",
                       "-O",
                       file(css).toString()).out()).isEqualTo("@media print{a{color:#abc;margin:0;opacity:.5}}");

        assertThat(run("",
                       "minify",
                       "-O=shorten-colors",
                       file(css).toString()).out()).isEqualTo("@MEDIA print{a{color:#abc;margin:0px;opacity:0.50}}");
        // Available under `format` too: -O is orthogonal to the verb, and the verb owns
        // whitespace alone.
        assertThat(run("", "format", "-O=lowercase-names", file(css).toString()).out()).startsWith("@media print {");
    }

    /**
     * The two warnings that had nothing to act on, and now do.
     *
     * <p>Both are input metadata that rewriting invalidated, and each is fixed by naming its
     * transform. A bare {@code -O} must not fix either, which is the whole of the decision that
     * kept them out of {@code Optimizations.all()}.
     */
    @Test
    void silencesTheStaleMetadataWarningsOnlyWhenAsked() {
        String css = "@charset \"shift_jis\";\n/*# sourceMappingURL=a.css.map */\na { top: 0 }\n";

        Result bare = run("", "format", "-O", file(css).toString());
        assertThat(bare.err()).contains("@charset").contains("sourceMappingURL");
        assertThat(bare.out()).contains("@charset").contains("sourceMappingURL");

        Result named = run("", "format", "-O=drop-charset,drop-source-map-url", file(css).toString());
        assertThat(named.err()).isEmpty();
        assertThat(named.out()).doesNotContain("@charset").doesNotContain("sourceMappingURL").contains("top: 0");
    }

    @Test
    void tellsYouWhichTransformSilencesEachWarning() {
        // A warning with an actionable fix should name it; these two are the only ones that
        // have one.
        String css = "@charset \"shift_jis\";\n/*# sourceMappingURL=a.css.map */\na { top: 0 }\n";

        assertThat(run("", "format", file(css).toString()).err()).contains("-O=drop-charset")
                                                                 .contains("-O=drop-source-map-url");
    }

    /**
     * The CLI holds no CSS knowledge of its own, here as everywhere else: {@code -O} is the
     * library's transforms behind a flag, and this compares the two directly rather than
     * restating what the optimizer does, which the library's own tests already cover and which
     * a second assertion here would only ever fail in a pair with.
     */
    @Test
    void optimizesExactlyAsTheLibraryWouldHaveBeenCalled() {
        String css = "@MEDIA print { a { color: #AABBCC; margin: 0px; opacity: 0.50 } }\n";
        String[] args = { "minify", "-O=shorten-colors,drop-zero-units", file(css).toString() };
        Options options = parse(args);

        String expected = CssSerializer.serialize(
                                                  Optimizer.optimize(CssParser.parse(css).ast(),
                                                                     Transform.resolve(options.optimizations())),
                                                  options.serializer(),
                                                  Diagnostic.DISCARD);

        assertThat(run("", args).out()).isEqualTo(expected);
    }

    @Nested
    class Destinations {

        @Test
        void writesOneFileForDashO() throws Exception {
            Path target = CliTest.this.dir.resolve("nested/out.css");

            Result result = run("", "minify", "-o", target.toString(), file("a { color: red }").toString());

            assertThat(result.code()).isEqualTo(Cli.OK);
            assertThat(result.out()).isEmpty();
            assertThat(Files.readString(target)).isEqualTo("a{color:red}");
        }

        @Test
        void writesOnePerInputForOutDir() throws Exception {
            Path a = file("a { color: red }");
            Path b = write("b.css", ".b { top: 0 }");
            Path out = CliTest.this.dir.resolve("out");

            Result result = run("", "minify", "--out-dir", out.toString(), a.toString(), b.toString());

            assertThat(result.code()).isEqualTo(Cli.OK);
            assertThat(Files.readString(out.resolve(a.getFileName()))).isEqualTo("a{color:red}");
            assertThat(Files.readString(out.resolve("b.css"))).isEqualTo(".b{top:0}");
        }

        @Test
        void rewritesTheInputForInPlace() throws Exception {
            Path path = write("in-place.css", "a { color: red }\n");

            Result result = run("", "minify", "--in-place", path.toString());

            assertThat(result.code()).isEqualTo(Cli.OK);
            assertThat(result.out()).isEmpty();
            assertThat(Files.readString(path)).isEqualTo("a{color:red}");
        }

        /**
         * A write that fails part-way must leave the original rather than half of a new one,
         * and the temporary it went through must not survive either.
         */
        @Test
        void leavesNoTemporaryFileBehind() throws Exception {
            Path path = write("tidy.css", "a { color: red }\n");

            run("", "minify", "--in-place", path.toString());

            try (var entries = Files.list(CliTest.this.dir)) {
                assertThat(entries.map(p -> p.getFileName()
                                             .toString())).noneMatch(name -> name.startsWith(".cassette-"));
            }
        }

        @Test
        void writesUtf8() throws Exception {
            Path target = CliTest.this.dir.resolve("unicode.css");

            run("", "minify", "-o", target.toString(), file("a { content: \"café\" }").toString());

            assertThat(Files.readAllBytes(target)).isEqualTo("a{content:\"café\"}".getBytes(StandardCharsets.UTF_8));
        }

        @Test
        void exitsThreeWhenTheDestinationCannotBeCreated() throws Exception {
            // The parent is a file, so creating the directory for it must fail.
            Path blocker = write("blocker.css", "a{color:red}");
            Path target = blocker.resolve("under-a-file.css");

            Result result = run("", "minify", "-o", target.toString(), blocker.toString());

            assertThat(result.code()).isEqualTo(Cli.IO);
            assertThat(Files.readString(blocker)).isEqualTo("a{color:red}");
        }

        /**
         * The parser keeps {@code @charset} as an ordinary at-rule and this writes UTF-8, so a
         * legacy-encoded stylesheet comes back out carrying a rule that now lies about its own
         * bytes, and under {@code --in-place} the corrupted copy is the only one. Warned about
         * rather than rewritten, since changing what a stylesheet says is not a decision the
         * command that happened to write it should be making.
         */
        @Test
        void warnsWhenAnAtCharsetNoLongerDescribesTheOutput() throws Exception {
            Path path = CliTest.this.dir.resolve("sjis.css");
            Files.write(path,
                        "@charset \"shift_jis\";\na { content: \"表\" }\n".getBytes(java.nio.charset.Charset.forName("Shift_JIS")));

            Result result = run("", "format", "--color=never", "--in-place", path.toString());

            assertThat(result.err()).contains("output is written as UTF-8").contains("@charset \"shift_jis\"");

            // Still written, and still readable as what it now is.
            assertThat(Files.readString(path)).contains("表");
        }

        @Test
        void doesNotWarnWhenTheCharsetAgreesWithTheOutput() {
            Result result = run("", "format", file("@charset \"utf-8\";\na { color: red }\n").toString());

            assertThat(result.err()).isEmpty();
        }

        /**
         * The same shape as the {@code @charset} warning above: metadata the input carried
         * about itself, which rewriting invalidates. The map named here was generated against
         * the input, and reformatting moved every offset in it.
         */
        @Test
        void warnsWhenASourceMappingUrlSurvivesIntoReformattedOutput() throws Exception {
            Path path = write("mapped.css", ".a { color: red }\n/*# sourceMappingURL=mapped.css.map */\n");

            Result result = run("", "format", "--color=never", path.toString());

            assertThat(result.err()).contains("this sourceMappingURL survives into the output");
            assertThat(result.out()).contains("sourceMappingURL=mapped.css.map");
        }

        /**
         * And says nothing when it did not survive. {@code Formatting.MINIFIED} strips
         * comments, so a minified run carries no annotation and has nothing to be wrong about
         * which is why the check asks the finished output rather than predicting from the
         * flags.
         */
        @Test
        void staysSilentWhenMinifyingStripsTheSourceMappingUrl() throws Exception {
            Path path = write("mapped-min.css", ".a { color: red }\n/*# sourceMappingURL=mapped-min.css.map */\n");

            Result result = run("", "minify", "--color=never", path.toString());

            assertThat(result.out()).isEqualTo(".a{color:red}");
            assertThat(result.err()).isEmpty();
        }

        /**
         * A comment that is not the annotation is nobody's business.
         */
        @Test
        void doesNotWarnAboutAnOrdinaryComment() throws Exception {
            Path path = write("commented.css", "/* just a note */\n.a { color: red }\n");

            Result result = run("", "format", "--color=never", path.toString());

            assertThat(result.err()).isEmpty();
        }

        private Path write(String name, String css) throws Exception {
            Path path = CliTest.this.dir.resolve(name);
            Files.writeString(path, css);
            return path;
        }
    }

    /**
     * {@code --bundle}, and the two policies the filesystem importer carries and the library does
     * not: a fence around where an {@code @import} may resolve, and never over a network.
     */
    @Nested
    class Bundling {

        @Test
        void makesTheInputsOneStylesheetInCascadeOrder() throws Exception {
            Path a = write("a.css", ".a { color: red }\n");
            Path b = write("b.css", ".b { color: blue }\n");

            Result result = run("", "minify", "--bundle", a.toString(), b.toString());

            assertThat(result.code()).isEqualTo(Cli.OK);
            assertThat(result.out()).isEqualTo(".a{color:red}.b{color:blue}");
        }

        @Test
        void resolvesAnImportBesideTheImportingFile() throws Exception {
            write("partial.css", ".p { color: red }\n");
            Path entry = write("entry.css", "@import \"partial.css\";\n.e { color: blue }\n");

            Result result = run("", "minify", "--bundle", entry.toString());

            assertThat(result.code()).isEqualTo(Cli.OK);
            assertThat(result.out()).isEqualTo(".p{color:red}.e{color:blue}");
            assertThat(result.err()).isEmpty();
        }

        /**
         * The fence. With no {@code --import-root} the root is the input's own directory, so a
         * specifier climbing out of it resolves to nothing, which is not an error but a
         * decline, so the rule stays in the output for a browser to resolve at runtime.
         */
        @Test
        void declinesAnImportThatEscapesEveryRoot() throws Exception {
            Path outside = CliTest.this.dir.resolve("outside.css");
            Files.writeString(outside, ".secret { color: red }\n");
            Path inner = Files.createDirectory(CliTest.this.dir.resolve("inner"));
            Path entry = inner.resolve("entry.css");
            Files.writeString(entry, "@import \"../outside.css\";\n.e { color: blue }\n");

            Result result = run("", "minify", "--color=never", "--bundle", entry.toString());

            assertThat(result.out()).doesNotContain(".secret").contains(".e{color:blue}");
            assertThat(result.err()).contains("was not resolved, so it is left in the output");

            // A decline is not a failure: the output is valid and the browser resolves it.
            assertThat(result.code()).isEqualTo(Cli.OK);
        }

        @Test
        void resolvesOutsideThatRootOnlyWhenOneIsDeclared() throws Exception {
            Path outside = CliTest.this.dir.resolve("outside.css");
            Files.writeString(outside, ".secret { color: red }\n");
            Path inner = Files.createDirectory(CliTest.this.dir.resolve("inner"));
            Path entry = inner.resolve("entry.css");
            Files.writeString(entry, "@import \"../outside.css\";\n.e { color: blue }\n");

            Result result =
                run("", "minify", "--bundle", "--import-root", CliTest.this.dir.toString(), entry.toString());

            assertThat(result.out()).isEqualTo(".secret{color:red}.e{color:blue}");
            assertThat(result.err()).isEmpty();
        }

        /**
         * A web font survives bundling untouched rather than being fetched during the build,
         * even with a root declared over the whole directory.
         *
         * <p>Asserts the behaviour and not a mechanism, because two independent things produce it.
         * {@code FileImporter.hasScheme} declines it up front, and the root fence declines it again
         * because a URL names no file inside a root. Disabling the former leaves this test passing,
         * which is recorded there rather than papered over here.
         */
        @Test
        void neverFetchesOverANetwork() throws Exception {
            Path entry = write("fonts.css", "@import \"https://fonts.example/css?family=X\";\n.e { color: blue }\n");

            Result result = run("",
                                "minify",
                                "--color=never",
                                "--bundle",
                                "--import-root",
                                CliTest.this.dir.toString(),
                                entry.toString());

            assertThat(result.out()).isEqualTo("@import \"https://fonts.example/css?family=X\";.e{color:blue}");
            assertThat(result.err()).contains("was not resolved");
        }

        /**
         * The reason every id goes through {@code SourceIds}, asserted as the exact chain
         * rather than as "a cycle was found", which is what the first version of this test
         * checked, and which passes with canonicalization disabled.
         *
         * <p>The hazard is subtler than it first looks. An uncanonicalized command-line id and
         * a canonicalized imported one are two sources, so the first lap round the cycle does
         * not close; but the second lap re-enters on the canonical id and closes there. So a
         * cycle is still reported either way. What is lost is that the sheet has by then been
         * inlined an extra time, and that the chain names one file by two different paths,
         * which is precisely the part a reader is supposed to act on.
         */
        @Test
        void detectsACycleAsAChainNamingEachFileOnce() throws Exception {
            Path a = write("cyc-a.css", "@import \"cyc-b.css\";\n.a { color: red }\n");
            Path b = write("cyc-b.css", "@import \"./cyc-a.css\";\n.b { color: blue }\n");
            String idA = a.toRealPath().toString();
            String idB = b.toRealPath().toString();

            Result result = run("", "check", "--color=never", "--bundle", a.toString());

            assertThat(result.code()).isEqualTo(Cli.DIAGNOSTICS);

            assertThat(result.err()).contains("@import cycle: " + idA + " -> " + idB + " -> " + idA)
                                    .doesNotContain("nested deeper than");
        }

        @Test
        void leavesEveryImportAloneUnderNoImports() throws Exception {
            write("partial.css", ".p { color: red }\n");
            Path entry = write("entry.css", "@import \"partial.css\";\n.e { color: blue }\n");

            Result result = run("", "minify", "--bundle", "--no-imports", entry.toString());

            assertThat(result.out()).isEqualTo("@import \"partial.css\";.e{color:blue}");
        }

        /**
         * An ordinary AST comment, so {@code minify} strips it like any other.
         */
        @Test
        void namesEachSourceUnderBanners() throws Exception {
            Path a = write("a.css", ".a { color: red }\n");
            Path b = write("b.css", ".b { color: blue }\n");

            Result formatted = run("", "format", "--bundle", "--banners", a.toString(), b.toString());
            assertThat(formatted.out()).contains("a.css */").contains("b.css */");

            Result minified = run("", "minify", "--bundle", "--banners", a.toString(), b.toString());
            assertThat(minified.out()).isEqualTo(".a{color:red}.b{color:blue}");
        }

        @Test
        void cutsAChainAtTheDepthBound() throws Exception {
            write("d1.css", "@import \"d2.css\";\n.one { color: red }\n");
            write("d2.css", "@import \"d3.css\";\n.two { color: red }\n");
            write("d3.css", ".three { color: red }\n");

            Result result = run("",
                                "check",
                                "--color=never",
                                "--bundle",
                                "--max-import-depth=1",
                                CliTest.this.dir.resolve("d1.css").toString());

            assertThat(result.code()).isEqualTo(Cli.DIAGNOSTICS);
            assertThat(result.err()).contains("nested deeper than 1 levels");
        }

        @Test
        void writesOneFileForSeveralInputsUnderDashO() throws Exception {
            Path a = write("a.css", ".a { color: red }\n");
            Path b = write("b.css", ".b { color: blue }\n");
            Path target = CliTest.this.dir.resolve("bundle.css");

            Result result = run("", "minify", "--bundle", "-o", target.toString(), a.toString(), b.toString());

            assertThat(result.code()).isEqualTo(Cli.OK);
            assertThat(Files.readString(target)).isEqualTo(".a{color:red}.b{color:blue}");
        }

        /**
         * {@code check --bundle} validates the graph and writes nothing anywhere.
         */
        @Test
        void checkValidatesTheGraphAndWritesNothing() throws Exception {
            Path entry = write("broken.css", "@import \"absent.css\";\n.e { color: blue }\n");

            Result result = run("", "check", "--color=never", "--bundle", entry.toString());

            assertThat(result.out()).isEmpty();
            assertThat(result.err()).contains("@import \"absent.css\" was not resolved");
        }

        /**
         * A bundled tree's spans are offsets into one coordinate space and into no single file,
         * so this only reads correctly because the renderer resolves through
         * {@code BundleResult.sourceIndex()}. Reaching for the text of whichever file was being
         * read would name the wrong one, without failing.
         */
        @Test
        void namesTheRightFileForADiagnosticInAnImportedSheet() throws Exception {
            write("bad.css", ".p { background: rgb(0 0 0; }\n");
            Path entry = write("entry.css", "@import \"bad.css\";\n.e { color: blue }\n");

            Result result = run("", "check", "--color=never", "--bundle", entry.toString());

            assertThat(result.code()).isEqualTo(Cli.DIAGNOSTICS);
            assertThat(result.err()).contains("bad.css:1:").doesNotContain("entry.css:1:");
        }

        /**
         * Concatenation keeps one annotation per input, and tools honour the last one in a
         * file, so a bundle silently claims whichever input came last, for all of it. Each is
         * named against its own file, which is where the reader has to go to remove it.
         */
        @Test
        void warnsAboutEverySourceMappingUrlItConcatenated() throws Exception {
            Path a = write("m-a.css", ".a { color: red }\n/*# sourceMappingURL=m-a.css.map */\n");
            Path b = write("m-b.css", ".b { color: blue }\n/*# sourceMappingURL=m-b.css.map */\n");

            Result result = run("", "format", "--color=never", "--bundle", a.toString(), b.toString());

            assertThat(result.err()).contains("m-a.css").contains("m-b.css")
                                    .containsPattern("(?s)sourceMappingURL survives.*sourceMappingURL survives");
        }

        private Path write(String name, String css) throws Exception {
            Path path = CliTest.this.dir.resolve(name);
            Files.writeString(path, css);
            return path;
        }
    }

    private static Options parse(String[] args) {
        try {
            return ((Invocation.Run) Arguments.parse(args)).options();
        }
        catch (UsageException e) {
            throw new AssertionError(e);
        }
    }

    // -----------------------------------------------------------------------

    /**
     * {@code --source-map}, which is the one flag group that writes a second file.
     *
     * <p>What is worth asserting here is what only this component decides: where the sidecar
     * goes, what the trailer names, and what {@code sources} is relative to. The map's contents
     * are the library's and are checked there.
     */
    @Nested
    class SourceMaps {

        @Test
        void writesTheSidecarBesideTheOutputAndNamesItInATrailer() throws IOException {
            Path input = file(".a { color: #FFFFFF }\n");
            Path output = CliTest.this.dir.resolve("out/app.min.css");

            Result result = run("", "minify", "--source-map", "-o", output.toString(), input.toString());

            assertThat(result.code()).isZero();
            assertThat(Files.readString(output)).endsWith("/*# sourceMappingURL=app.min.css.map */\n");

            assertThat(Files.readString(output.resolveSibling("app.min.css.map"))).contains("\"file\":\"app.min.css\"")
                                                                                  .contains("\"version\":3");
        }

        @Test
        void relativizesSourcesAgainstTheMapsOwnDirectory() throws IOException {
            // Which is what the format means by `sources`, and the one thing the library
            // cannot do: it has no filesystem and never sees the output's path.
            Path input = file(".a { top: 0 }\n");
            Path output = CliTest.this.dir.resolve("dist/app.css");

            run("", "minify", "--source-map", "-o", output.toString(), input.toString());

            assertThat(Files.readString(output.resolveSibling("app.css.map"))).contains("\"sources\":[\"../"
                                                                                        + input.getFileName()
                                                                                        + "\"]");
        }

        @Test
        void inlinesTheWholeMapAsADataUri() {
            Result result = run("", "minify", "--source-map=inline", file(".a{top:0}").toString());

            assertThat(result.code()).isZero();
            assertThat(result.out()).contains("/*# sourceMappingURL=data:application/json;charset=utf-8;base64,");

            String uri = result.out().substring(result.out().indexOf("base64,") + 7);
            String json =
                new String(Base64.getDecoder().decode(uri.substring(0, uri.indexOf(" */"))), StandardCharsets.UTF_8);
            assertThat(json).contains("\"version\":3").contains("\"mappings\":");
        }

        @Test
        void refusesASidecarWithNowhereToPutIt() {
            Result result = run("", "minify", "--source-map", file(".a{top:0}").toString());

            assertThat(result.code()).isEqualTo(2);
            assertThat(result.err()).contains("standard output is not a place").contains("--source-map=inline");
        }

        @Test
        void acceptsTheFlagUnderCheckAndDoesNothingWithIt() {
            // Exactly as `check -O` is: the verb writes nothing, so there is nothing to map.
            Result result = run("", "check", "--source-map", file(".a{top:0}").toString());

            assertThat(result.code()).isZero();
            assertThat(result.out()).isEmpty();
        }

        @Test
        void writesOneMapForABundle() throws IOException {
            Path base = CliTest.this.dir.resolve("base.css");
            Files.writeString(base, ".base { top: 0 }\n");
            Path entry = CliTest.this.dir.resolve("entry.css");
            Files.writeString(entry, "@import \"base.css\";\n.card { top: 1 }\n");
            Path output = CliTest.this.dir.resolve("b/bundle.css");

            run("", "minify", "--bundle", "--source-map", "-o", output.toString(), entry.toString());

            assertThat(Files.readString(output.resolveSibling("bundle.css.map"))).contains("../base.css")
                                                                                 .contains("../entry.css");
        }

        @Test
        void letsTheTrailerBeNamedIndependentlyOfTheFile() throws IOException {
            Path output = CliTest.this.dir.resolve("out/app.css");

            run("",
                "minify",
                "--source-map",
                "--source-map-url",
                "/static/app.map",
                "-o",
                output.toString(),
                file(".a{top:0}").toString());

            assertThat(Files.readString(output)).endsWith("/*# sourceMappingURL=/static/app.map */\n");

            // Still written where it belongs; the URL says where it will be served from.
            assertThat(Files.exists(output.resolveSibling("app.css.map"))).isTrue();
        }

        @Test
        void omitsContentWhenAsked() throws IOException {
            Path output = CliTest.this.dir.resolve("out/app.css");

            run("",
                "minify",
                "--source-map",
                "--no-source-map-content",
                "-o",
                output.toString(),
                file(".a{top:0}").toString());

            assertThat(Files.readString(output.resolveSibling("app.css.map"))).doesNotContain("sourcesContent");
        }

        @Test
        void warnsWhenStandardInputIsLeftWithNothingToResolveWith() throws IOException {
            Path output = CliTest.this.dir.resolve("out/app.css");

            Result result = run(".a { top: 0 }\n",
                                "minify",
                                "--source-map",
                                "--no-source-map-content",
                                "-o",
                                output.toString(),
                                "-");

            assertThat(result.err()).contains("<stdin>").contains("--no-source-map-content");
            assertThat(result.code()).isZero();
            assertThat(Files.readString(output.resolveSibling("app.css.map"))).contains("\"<stdin>\"");
        }

        @Test
        void doesNotWarnWhenTheContentIsThere() {
            Result result = run(".a { top: 0 }\n", "minify", "--source-map=inline", "-");

            assertThat(result.err()).isEmpty();
        }

        @Test
        void addsNothingAtAllWhenNoMapWasAskedFor() {
            // The CLI writes what the serializer produced. The source-map trailer is the one
            // written exception to that.
            assertThat(run("", "minify", file(".a { top: 0 }\n").toString()).out()).isEqualTo(".a{top:0}");
        }
    }

    private record Result(int code, String out, String err) {
    }

    private Path file(String css) {
        try {
            Path path = this.dir.resolve("style-" + css.hashCode() + ".css");
            Files.writeString(path, css);
            return path;
        }
        catch (java.io.IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Result check(String css) {
        return run("", "check", "--color=never", file(css).toString());
    }

    private static Result run(String stdin, String... args) {
        InputStream in = new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = Cli.run(args,
                           in,
                           new PrintStream(out, true, StandardCharsets.UTF_8),
                           new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Result(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }
}
