package dev.nullkitty.cassette.cli;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import dev.nullkitty.cassette.ast.AtRule;
import dev.nullkitty.cassette.ast.Comment;
import dev.nullkitty.cassette.ast.Node;
import dev.nullkitty.cassette.ast.Rule;
import dev.nullkitty.cassette.ast.StringToken;
import dev.nullkitty.cassette.ast.Stylesheet;
import dev.nullkitty.cassette.bundle.BundleOptions;
import dev.nullkitty.cassette.bundle.BundleResult;
import dev.nullkitty.cassette.bundle.Bundler;
import dev.nullkitty.cassette.bundle.Source;
import dev.nullkitty.cassette.bundle.SourceIndex;
import dev.nullkitty.cassette.diagnostics.Diagnostic;
import dev.nullkitty.cassette.diagnostics.SourceResolver;
import dev.nullkitty.cassette.parser.CssParser;
import dev.nullkitty.cassette.parser.ParseResult;
import dev.nullkitty.cassette.serializer.CssSerializer;
import dev.nullkitty.cassette.serializer.Optimizer;
import dev.nullkitty.cassette.serializer.SerializeResult;
import dev.nullkitty.cassette.sourcemap.SourceMap;

/**
 * The command-line driver.
 *
 * <p>{@link #run} takes its streams as parameters and returns an exit code, and {@link #main} is
 * the three-line wrapper that turns that into a process. Tests drive {@code run} directly, which
 * is why none of them forks a JVM or installs anything to intercept {@code System.exit}.
 */
public final class Cli {

    /**
     * No errors; warnings may have printed.
     */
    static final int OK = 0;

    /**
     * At least one {@code ERROR}, or a {@code WARNING} under {@code --strict}.
     */
    static final int DIAGNOSTICS = 1;

    /**
     * Unknown flag, bad enum value, no verb.
     */
    static final int USAGE = 2;

    /**
     * Input unreadable, output unwritable.
     */
    static final int IO = 3;

    /**
     * @param args command line
     * @param in   standard input, for an input named {@code -}
     * @param out  where CSS goes
     * @param err  where diagnostics and usage errors go
     * @return the exit code
     */
    public static int run(String[] args, //
                          InputStream in,
                          PrintStream out,
                          PrintStream err) {
        Invocation invocation;
        try {
            invocation = Arguments.parse(args);
        }
        catch (UsageException e) {
            err.println("cassette: " + e.getMessage());
            err.println("try 'cassette --help'");
            return USAGE;
        }

        return switch (invocation) {
            case Invocation.ShowVersion ignored -> {
                out.println("cassette " + version());
                yield OK;
            }

            case Invocation.ShowHelp help -> {
                out.print(help.verb() == null ? Help.OVERVIEW : Help.forVerb(help.verb()));
                yield OK;
            }

            case Invocation.Run run -> execute(run.options(), in, out, err);
        };
    }

    private static int execute(Options options, //
                               InputStream in,
                               PrintStream out,
                               PrintStream err) {
        DiagnosticReporter reporter = new DiagnosticReporter(err, options);

        warnAboutAnUnresolvableMap(options, err);

        if (options.bundle()) {
            int failure = bundle(options, in, out, err, reporter);
            if (failure != OK) {
                return failure;
            }
        }
        else {
            for (String input : options.inputs()) {
                try {
                    Path destination = options.writesToStdout() ? null : destination(input, options);

                    SourceMaps.Attached rendered = process(input, options, in, destination, reporter);
                    if (rendered == null) {
                        continue;
                    }

                    if (destination == null) {
                        out.print(rendered.css());
                    }
                    else {
                        Output.write(rendered.css(), destination);
                    }

                    if (rendered.map() != null) {
                        Output.write(rendered.map()::writeJson, SourceMaps.fileFor(destination));
                    }
                }
                catch (IOException | UncheckedIOException e) {
                    err.println("cassette: " + describe(input, e));
                    return IO;
                }
            }
        }

        reporter.finish();

        // Note that a nonzero exit does not mean nothing was written. Recovery is defined
        // behaviour, so a stylesheet with errors still produces output, and the errors describe
        // what recovery did to it.
        return reporter.failed() ? DIAGNOSTICS : OK;
    }

    /**
     * Every input as one stylesheet, in cascade order, with {@code @import} resolved.
     *
     * <p>One read of the whole pipeline rather than a loop, because that is what bundling is:
     * the inputs are one document and the diagnostics from all of them resolve through one
     * {@link SourceIndex}, which is what {@code BundleResult.sourceIndex()} implementing
     * {@code SourceResolver} buys; the renderer takes a bundled tree with no change at all.
     *
     * <p>The output is written after the diagnostics are reported, which is the opposite of the
     * per-input path above. There, a file that fails leaves the earlier files already written. Here
     * there is one output, so a failure part-way should leave nothing rather than a partial bundle,
     * and the reporting has to happen either way.
     *
     * <p>Nothing warns about a stale {@code @charset} here, and nothing needs to. {@code Bundler}
     * drops every one of them, since by the time a bundle exists the text is decoded and a
     * surviving {@code @charset} describes nothing.
     *
     * @return {@link #OK}, or {@link #IO} when a source could not be read or the output written
     */
    private static int bundle(Options options,
                              InputStream in,
                              PrintStream out,
                              PrintStream err,
                              DiagnosticReporter reporter) {
        List<Source> sources = new ArrayList<>();

        for (String input : options.inputs()) {
            try {
                sources.add(new Source(SourceIds.of(input), read(input, in), options.charset()));
            }
            catch (IOException | UncheckedIOException e) {
                err.println("cassette: " + describe(input, e));
                return IO;
            }
        }

        BundleResult bundled = Bundler.bundle(sources, bundleOptions(options));
        List<Diagnostic> found = new ArrayList<>(bundled.diagnostics());
        SourceMaps.Attached rendered = null;
        if (options.verb().writesOutput()) {
            Stylesheet ast = Optimizer.optimize(bundled.ast(), Transform.resolve(options.optimizations()));
            String css;

            if (options.generatesSourceMap()) {
                // One map for the whole bundle, and nothing about the call changes: the
                // bundle's own SourceIndex is a SourceResolver, so the serializer takes a tree
                // spanning several files exactly as it takes one from a single parse.
                SerializeResult serialized =
                    CssSerializer.serializeWithMap(ast, options.serializer(), bundled.sourceIndex(), found::add);
                rendered = SourceMaps.attach(serialized, options, options.output());
                css = serialized.css();
            }
            else {
                css = CssSerializer.serialize(ast, options.serializer(), found::add);
                rendered = new SourceMaps.Attached(css, null);
            }

            // No stale-@charset warning here: Bundler drops every one of them. The source-map
            // annotations are the opposite case; they are ordinary comments, so concatenation
            // keeps all of them, and one bundle ends up carrying several.
            warnAboutStaleSourceMap(ast, css, found);
        }

        reporter.report(found, bundled.sourceIndex());

        if (rendered == null) {
            return OK;
        }

        if (options.writesToStdout()) {
            out.print(rendered.css());
            return OK;
        }

        try {
            Output.write(rendered.css(), options.output());

            if (rendered.map() != null) {
                Output.write(rendered.map()::writeJson, SourceMaps.fileFor(options.output()));
            }
        }
        catch (IOException | UncheckedIOException e) {
            err.println("cassette: " + describe(options.output().toString(), e));
            return IO;
        }

        return OK;
    }

    /**
     * The library options, and the one policy decision the CLI makes for the importer.
     *
     * <p>With no {@code --import-root}, the roots are the inputs' own directories: a bundle of
     * {@code src/a.css} and {@code src/b.css} resolves partials beside them and nothing above, so
     * the common case needs no flag and the uncommon one is explicit. Standard input has no
     * directory and contributes no root, so a bundle read from a pipe resolves nothing until a
     * root is declared.
     *
     * <p>{@code --no-imports} passes no importer at all rather than one that always declines. The
     * library reads a null importer as "resolve none", so this is the same answer arrived at one
     * layer earlier, and {@code BundleOptions.resolvesImports()} then says so honestly.
     */
    private static BundleOptions bundleOptions(Options options) {
        return BundleOptions.builder() //
                            .importer(options.noImports() ? null : new FileImporter(importRoots(options))) //
                            .banners(options.banners()) //
                            .maxImportDepth(options.maxImportDepth()) //
                            .build();
    }

    private static List<Path> importRoots(Options options) {
        if (!options.importRoots().isEmpty()) {
            return options.importRoots();
        }

        List<Path> roots = new ArrayList<>();

        for (String input : options.inputs()) {
            if (input.equals("-")) {
                continue;
            }

            Path directory = Path.of(input).toAbsolutePath().getParent();
            if (directory != null && !roots.contains(directory)) {
                roots.add(directory);
            }
        }

        return roots;
    }

    /**
     * Where one input's output goes.
     *
     * <p>{@code --out-dir} mirrors the file name and not the path leading to it: mirroring a
     * relative path needs a base directory to be relative <em>to</em>, which nothing here
     * supplies and which an absolute input would escape anyway. Two inputs whose names collide
     * under one {@code --out-dir} are rejected by {@link Arguments}, so the flattening cannot
     * silently drop a file.
     */
    private static Path destination(String input, Options options) {
        if (options.output() != null) {
            return options.output();
        }

        Path path = Path.of(input);
        return options.outDir() != null ? options.outDir().resolve(path.getFileName()) : path;
    }

    /**
     * Runs one input through the pipeline.
     *
     * @param destination where the CSS will go, or {@code null} for standard output, which the
     *                    map needs, both to name itself and to relativize its sources against
     * @return the CSS to write and the map to write beside it, or {@code null} for a verb that
     *         writes neither
     */
    private static SourceMaps.Attached process(String input,
                                               Options options,
                                               InputStream in,
                                               Path destination,
                                               DiagnosticReporter reporter) throws IOException {
        byte[] bytes = read(input, in);

        // Not SourceIds.of: that canonicalizes, which is what cycle detection needs and this
        // path has no cycles to detect. One file, named the way it was named on the command
        // line, is what a reader expects a diagnostic to say.
        String name = input.equals("-") ? SourceIds.STDIN : input;

        // Decoding and parsing are two steps so that the text every span indexes into is one
        // the renderer holds. That split is also why decode takes the sink: the unresolvable
        // @charset warning happens here and parse(CharSequence) could never report it.
        List<Diagnostic> found = new ArrayList<>();
        String text = CssParser.decode(bytes, options.charset(), found::add);
        ParseResult result = CssParser.parse(text);
        found.addAll(result.diagnostics());

        SourceMaps.Attached rendered = null;
        if (options.verb().writesOutput()) {
            // -O runs before serialization and never during it: Formatting.MINIFIED removes
            // whitespace and comments, everything here changes what a value says, and keeping
            // those two apart is what the minify/-O split protects. An empty
            // list returns the tree untouched, so `optimize` needs no guard around it.
            Stylesheet ast = Optimizer.optimize(result.ast(), Transform.resolve(options.optimizations()));

            // The overloads with a sink, deliberately. The ones without discard, and a `url()`
            // the writer cannot spell is the serializer's own limitation rather than something
            // the parse already reported, so the quiet form would throw away the one
            // diagnostic nothing else in the system would ever name.
            SourceResolver sources = SourceResolver.of(name, text);

            String css;
            if (options.generatesSourceMap()) {
                SerializeResult serialized =
                    CssSerializer.serializeWithMap(ast, options.serializer(), sources, found::add);
                rendered = SourceMaps.attach(serialized, options, destination);
                css = serialized.css();
            }
            else {
                css = CssSerializer.serialize(ast, options.serializer(), found::add);
                rendered = new SourceMaps.Attached(css, null);
            }

            warnAboutStaleCharset(ast, found);

            // Asked of the serializer's own output rather than of the trailer this run may
            // have just appended, which would otherwise warn about the map it is creating.
            warnAboutStaleSourceMap(ast, css, found);
        }

        reporter.report(found, SourceResolver.of(name, text));

        return rendered;
    }

    /**
     * The one flag combination that produces a map nothing can follow.
     *
     * <p>Standard input has no path, so its entry in {@code sources} is a placeholder and the
     * only thing that can make it readable is the text in {@code sourcesContent}. Dropping the
     * content leaves every mapping pointing at a file no consumer can open, worth saying,
     * because the flag that caused it is one about size and reads as though it were free.
     *
     * <p>Not a {@link dev.nullkitty.cassette.diagnostics.Diagnostic}. Every diagnostic carries a
     * span, and this has no location, because it is about the flags rather than about anything in
     * the stylesheet. Given {@code SourceSpan.NONE} it would render as {@code &lt;stdin&gt;:1:1}
     * with the first line quoted underneath, which points at something innocent and makes every
     * honest location beside it less believable. It goes out as a plain message instead, once per
     * invocation rather than once per map, read off the flags because that is all it depends on.
     *
     * <p>{@code --quiet} still silences it, since it is a warning and that is what the flag is for.
     * It does not affect the exit code under {@code --strict}, because nothing about the input is
     * wrong and the map that was asked for was produced.
     */
    private static void warnAboutAnUnresolvableMap(Options options, PrintStream err) {
        if (options.generatesSourceMap()
            && !options.sourceMapContent()
            && options.inputs().contains("-")
            && !options.quiet()) {
            err.println("cassette: warning: this map names "
                        + SourceIds.STDIN
                        + ", which nothing can open, and '--no-source-map-content' left it no text "
                        + "to fall back on");
        }
    }

    /**
     * Reports an {@code @charset} that the output is about to contradict.
     *
     * <p>The parser keeps {@code @charset} as an ordinary statement at-rule, unlike the CSSOM, since
     * dropping it is semantic validation, and {@link Output} writes UTF-8. Formatting a Shift_JIS
     * stylesheet therefore produces UTF-8 bytes still carrying {@code @charset "shift_jis"}, and
     * reading that file back decodes it as Shift_JIS and corrupts it. Under {@code --in-place} the
     * only copy is the one destroyed.
     *
     * <p>A warning rather than a rewrite. Changing the rule or dropping it is a decision about what
     * the stylesheet says, which belongs to an opt-in transform and not to whichever command
     * happened to write the file. The message names that transform, {@code -O=drop-charset}, because
     * a warning with an actionable fix should say what it is.
     */
    private static void warnAboutStaleCharset(Stylesheet sheet, List<Diagnostic> found) {
        for (Rule rule : sheet.rules()) {
            if (!(rule instanceof AtRule atRule) || !"charset".equalsIgnoreCase(atRule.name())) {
                continue;
            }

            String declared = atRule.prelude() //
                                    .stream() //
                                    .filter(StringToken.class::isInstance) //
                                    .map(value -> ((StringToken) value).value()).findFirst() //
                                    .orElse(null);

            if (declared != null && !declared.equalsIgnoreCase("utf-8") && !declared.equalsIgnoreCase("utf8")) {
                found.add(Diagnostic.warning("output is written as UTF-8, so this @charset \""
                                             + declared
                                             + "\" no longer describes it; -O=drop-charset removes it",
                                             atRule.span()));
            }
        }
    }

    /**
     * Reports a {@code sourceMappingURL} that survived into output it no longer describes.
     *
     * <p>The same shape as the {@code @charset} warning above, and worth seeing as that rather
     * than as a source-map feature: both are metadata the input carried about itself which
     * rewriting invalidates. {@code @charset} then lies about the bytes; this lies about the
     * positions, since the map it names was generated against the input and every offset in it
     * moved the moment anything reformatted.
     *
     * <p>Under {@code --bundle} it is worse than stale. The annotation is an ordinary comment, so
     * concatenation keeps one per input and tools honour the last one in a file, so a bundle
     * silently claims whichever input happened to come last for all of it.
     *
     * <p>Whether it survived is asked of the output rather than predicted from the options.
     * {@code Formatting.MINIFIED} strips comments, so a minified run carries no annotation and a
     * formatted one may. Reading that off the finished string cannot drift from what the
     * serializer did. This runs after the last character, so it is not the forward scan over
     * unwritten text that {@code CssWriter.writesNothing} answers.
     *
     * <p>A warning rather than a rewrite, for the reason the {@code @charset} one gives. Removing
     * content is an opt-in transform's decision, not that of whichever command happened to write the
     * file. The message names that transform, {@code -O=drop-source-map-url}.
     *
     * <p>Top-level comments only, which is where the convention puts it and where every real
     * one is. One inside a conditionally imported sheet is wrapped in a group rule by the
     * bundler and is not seen here, the transform <em>does</em> reach those, so it removes
     * strictly more than this warns about, which is the safe direction for the two to differ.
     */
    private static void warnAboutStaleSourceMap(Stylesheet sheet, String css, List<Diagnostic> found) {
        if (css == null || !css.contains("sourceMappingURL")) {
            return;
        }

        for (Node child : sheet.children()) {
            if (child instanceof Comment comment && SourceMap.isTrailer(comment.text())) {
                found.add(Diagnostic.warning("this sourceMappingURL survives into the output, whose positions it no "
                                             + "longer describes; the map it names was made for the input, and "
                                             + "-O=drop-source-map-url removes it",
                                             comment.span()));
            }
        }
    }

    private static byte[] read(String input, InputStream in) throws IOException {
        return input.equals("-") ? in.readAllBytes() : Files.readAllBytes(Path.of(input));
    }

    private static String describe(String input, //
                                   Exception e) {
        String where = input.equals("-") ? "standard input" : input;
        Throwable cause = e instanceof UncheckedIOException unchecked ? unchecked.getCause() : e;
        if (cause instanceof NoSuchFileException) {
            return where + ": no such file";
        }

        return where + ": " + cause.getMessage();
    }

    private static String version() {
        String declared = Cli.class.getPackage().getImplementationVersion();
        return declared != null ? declared : "0.2.0-SNAPSHOT";
    }

    private Cli() {
        // static-only
    }

    /**
     * @param args command line
     */
    public static void main(String[] args) {
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(System.err, true, StandardCharsets.UTF_8);
        System.exit(run(args, System.in, out, err));
    }
}
