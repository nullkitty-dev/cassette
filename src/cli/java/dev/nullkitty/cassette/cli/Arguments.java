package dev.nullkitty.cassette.cli;

import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.UnsupportedCharsetException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import dev.nullkitty.cassette.bundle.BundleOptions;
import dev.nullkitty.cassette.serializer.IdentifierEncoding;
import dev.nullkitty.cassette.serializer.NestingExpansion;
import dev.nullkitty.cassette.serializer.NestingMode;
import dev.nullkitty.cassette.serializer.SerializerOptions;

/**
 * The command line, hand-rolled.
 *
 * <p>Zero runtime dependencies is policy, so the grammar is a small one: {@code --flag value} and
 * {@code --flag=value}, a handful of single-dash short forms, {@code --} ending flag parsing,
 * {@code -} meaning standard input. No clustering of short flags, no abbreviation matching, no
 * negatable {@code --no-*} pairs.
 *
 * <p>Flag precedence needs no rules here, because the builder has them.
 * {@code SerializerOptions.Builder.legacyCompatible()} fills in only what was not set explicitly,
 * so mapping flags onto the builder in the order they arrive makes
 * {@code --legacy --nesting preserve} and {@code --nesting preserve --legacy} agree.
 *
 * <p>A flag whose value is optional takes it only in the attached form: {@code -O} and
 * {@code --optimize} alone mean {@code Optimizations.all()}, and a subset is
 * {@code -O=shorten-colors}. The space-separated form would leave
 * {@code cassette minify -O style.css} ambiguous between a transform name and an input file, and
 * the rule resolving it, whether the file exists or the name looks like a transform, is one no
 * help text can state.
 *
 * <p>{@code -O} accumulates into one {@link Transform} set, handed to {@link Options} in
 * {@code Transform}'s order rather than the command line's. {@code none} names no transform rather
 * than clearing what another {@code -O} asked for, since a flag that took things away would need a
 * rule about which side of it a name was written on. See {@link Transform}.
 *
 * <p>The bundling flags are accepted without {@code --bundle} and do nothing, as {@code --expand}
 * does under {@code --nesting preserve} and {@code -O} under {@code check}. Warning instead would
 * fire on every invocation that sets both from a shell alias, which is how a project that bundles
 * some targets and not others is configured.
 */
final class Arguments {

    /**
     * @param args the command line, verb first
     * @return what it asks for
     * @throws UsageException if it asks for nothing coherent
     */
    static Invocation parse(String[] args) throws UsageException {
        if (args.length == 0) {
            throw new UsageException("no verb; expected one of " + Verb.names());
        }

        // --help and --version outrank the verb, so `cassette --version` works without one.
        for (String arg : args) {
            if (arg.equals("--")) {
                break;
            }

            if (arg.equals("--version")) {
                return new Invocation.ShowVersion();
            }
        }

        Verb verb = Verb.parse(args[0]);
        int first = 1;
        if (verb == null) {
            if (isHelp(args[0])) {
                return new Invocation.ShowHelp(helpVerb(args[0]));
            }

            if (args[0].startsWith("-")) {
                throw new UsageException("no verb; expected one of " + Verb.names() + " before " + args[0]);
            }

            throw new UsageException("unknown verb '" + args[0] + "'; expected one of " + Verb.names());
        }

        for (int i = first; i < args.length; i++) {
            if (args[i].equals("--")) {
                break;
            }

            if (isHelp(args[i])) {
                Verb named = helpVerb(args[i]);
                return new Invocation.ShowHelp(named != null ? named : verb);
            }
        }

        return new Invocation.Run(run(verb, args, first));
    }

    private static boolean isHelp(String arg) {
        return arg.equals("-h") || arg.equals("--help") || arg.startsWith("--help=");
    }

    private static Verb helpVerb(String arg) throws UsageException {
        int equals = arg.indexOf('=');
        if (equals < 0) {
            return null;
        }

        String name = arg.substring(equals + 1);
        Verb verb = Verb.parse(name);
        if (verb == null) {
            throw new UsageException("unknown verb '" + name + "'; expected one of " + Verb.names());
        }

        return verb;
    }

    private static Options run(Verb verb, String[] args, int first) throws UsageException {
        SerializerOptions.Builder serializer = SerializerOptions.builder().formatting(verb.formatting());
        List<String> inputs = new ArrayList<>();
        EnumSet<Transform> optimizations = EnumSet.noneOf(Transform.class);

        Path output = null;
        Path outDir = null;
        boolean inPlace = false;

        boolean bundle = false;
        List<Path> importRoots = new ArrayList<>();
        boolean noImports = false;
        boolean banners = false;
        int maxImportDepth = BundleOptions.DEFAULT_MAX_IMPORT_DEPTH;

        SourceMapMode sourceMap = SourceMapMode.NONE;
        String sourceMapUrl = null;
        boolean sourceMapContent = true;

        Charset charset = null;

        boolean quiet = false;
        boolean strict = false;
        Color color = Color.AUTO;
        DiagnosticFormat format = DiagnosticFormat.AUTO;
        int maxDiagnostics = Options.DEFAULT_MAX_DIAGNOSTICS;

        boolean flagsEnded = false;

        for (int i = first; i < args.length; i++) {
            String arg = args[i];
            if (flagsEnded || arg.equals("-") || !arg.startsWith("-")) {
                inputs.add(arg);
                continue;
            }

            if (arg.equals("--")) {
                flagsEnded = true;
                continue;
            }

            int equals = arg.indexOf('=');
            String name = equals < 0 ? arg : arg.substring(0, equals);
            String attached = equals < 0 ? null : arg.substring(equals + 1);

            // Resolved before the switch so that consuming the next token happens in exactly
            // one place. An unknown flag takes no value, so its argument stays an input and
            // the error below names the flag rather than swallowing what followed it.
            String value = null;
            if (takesValue(name)) {
                if (attached != null) {
                    value = attached;
                }
                else if (i + 1 < args.length) {
                    value = args[++i];
                }
                else {
                    throw new UsageException("'" + name + "' needs a value");
                }
            }

            switch (name) {
                case "-o", "--output" -> output = Path.of(value);
                case "--out-dir" -> outDir = Path.of(value);
                case "-i", "--in-place" -> inPlace = noValue(name, attached);

                case "--nesting" -> serializer.nesting(pick(name, value, NestingMode.values()));
                case "--expand" -> serializer.nestingExpansion(pick(name, value, NestingExpansion.values()));
                case "--identifiers" -> serializer.identifierEncoding(pick(name, value, IdentifierEncoding.values()));

                case "--legacy" -> {
                    noValue(name, attached);
                    serializer.legacyCompatible();
                }

                case "-O", "--optimize" -> optimizations.addAll(transforms(attached));

                case "--bundle" -> bundle = noValue(name, attached);
                case "--import-root" -> importRoots.add(Path.of(value));
                case "--no-imports" -> noImports = noValue(name, attached);
                case "--banners" -> banners = noValue(name, attached);
                case "--max-import-depth" -> maxImportDepth = depth(name, value);

                // Value-optional, so the value comes only attached and a bare one means the
                // mode a person asking for "a source map" means: a file beside the output.
                case "--source-map" -> sourceMap = attached == null ? SourceMapMode.FILE : sourceMapMode(attached);
                case "--source-map-url" -> sourceMapUrl = value;
                case "--no-source-map-content" -> sourceMapContent = !noValue(name, attached);

                case "--charset" -> charset = charset(value);

                case "-q", "--quiet" -> quiet = noValue(name, attached);
                case "--strict" -> strict = noValue(name, attached);
                case "--color" -> color = color(value);
                case "--diagnostic-format" -> format = format(value);
                case "--max-diagnostics" -> maxDiagnostics = count(name, value);

                default -> throw new UsageException("unknown flag '" + name + "'");
            }
        }

        validate(verb, inputs, output, outDir, inPlace, bundle);
        validateSourceMap(verb, sourceMap, output, outDir, inPlace);

        // List.copyOf over an EnumSet, so what Options holds is in Transform's order and
        // carries no duplicate, whatever order or repetition the command line used.
        return new Options(verb,
                           inputs,
                           output,
                           outDir,
                           inPlace,
                           serializer.build(),
                           List.copyOf(optimizations),
                           charset,
                           quiet,
                           strict,
                           color,
                           format,
                           maxDiagnostics,
                           bundle,
                           List.copyOf(importRoots),
                           noImports,
                           banners,
                           maxImportDepth,
                           sourceMap,
                           sourceMapUrl,
                           sourceMapContent);
    }

    private static boolean takesValue(String name) {
        return switch (name) {
            case "-o", "--output", "--out-dir", "--nesting", "--expand", "--identifiers", "--charset", "--color", "--max-diagnostics", "--import-root", "--max-import-depth", "--diagnostic-format", "--source-map-url" -> true;
            default -> false;
        };
    }

    /**
     * Rejects {@code --strict=yes}, which reads as though it might also accept {@code no}.
     */
    private static boolean noValue(String name, String attached) throws UsageException {
        if (attached != null) {
            throw new UsageException("'" + name + "' takes no value");
        }

        return true;
    }

    /**
     * {@code -O} with nothing attached means every transform.
     *
     * <p>{@code all} is spelled out rather than left to mean "whatever the library ships",
     * because {@link Transform} is the one place that has to agree with
     * {@code Optimizations.all()} and a second place that agreed with it independently would
     * be a second place to get it wrong.
     */
    private static List<Transform> transforms(String attached) throws UsageException {
        if (attached == null || attached.equals("all")) {
            // Bare -O and -O=all both mean Optimizations.all(), not every constant. The two
            // dropping transforms are outside all() in the library because each removes an
            // assertion whose falsity depends on what is done with the output, and they must
            // not arrive here by implication either. -O=drop-charset asks for one by name.
            return Transform.inAll();
        }

        if (attached.equals("none")) {
            return List.of();
        }

        List<Transform> named = new ArrayList<>();

        for (String name : attached.split(",", -1)) {
            Transform transform = find(name, Transform.values());
            if (transform == null) {
                throw new UsageException("'-O' expected all, none, or a comma-separated list of "
                                         + accepted(Transform.values())
                                         + " but found '"
                                         + name
                                         + "'");
            }

            named.add(transform);
        }

        return named;
    }

    private static SourceMapMode sourceMapMode(String value) throws UsageException {
        SourceMapMode mode = SourceMapMode.parse(value);
        if (mode == null) {
            throw new UsageException("'--source-map' expected file|inline|none but found '" + value + "'");
        }

        return mode;
    }

    /**
     * The one destination a map cannot have.
     *
     * <p>A sidecar has to be written somewhere, and standard output is a stream rather than a
     * place, so {@code --source-map=file} with the CSS going to a pipe is a request with no
     * answer, and saying so beats writing a trailer naming a file that was never created.
     * {@code --source-map=inline} is fine there, which is what makes this a real choice rather
     * than a blanket refusal, and the message says so.
     *
     * <p>Under {@code check} neither applies: the verb writes nothing, so the flag is accepted
     * and ignored exactly as {@code -O} is.
     */
    private static void validateSourceMap(Verb verb,
                                          SourceMapMode mode,
                                          Path output,
                                          Path outDir,
                                          boolean inPlace) throws UsageException {
        if (!verb.writesOutput() || mode != SourceMapMode.FILE) {
            return;
        }

        if (output == null && outDir == null && !inPlace) {
            throw new UsageException("'--source-map=file' needs somewhere to put the map, and "
                                     + "standard output is not a place; give '-o', '--out-dir' or '--in-place', "
                                     + "or use '--source-map=inline'");
        }
    }

    private static <E extends Enum<E>> E pick(String flag, String value, E[] candidates) throws UsageException {
        E picked = find(value, candidates);
        if (picked == null) {
            throw new UsageException("'" + flag + "' expected " + accepted(candidates) + " but found '" + value + "'");
        }

        return picked;
    }

    private static <E extends Enum<E>> E find(String value, E[] candidates) {
        for (E candidate : candidates) {
            if (spell(candidate).equals(value)) {
                return candidate;
            }
        }

        return null;
    }

    private static <E extends Enum<E>> String accepted(E[] candidates) {
        StringBuilder accepted = new StringBuilder();
        for (E candidate : candidates) {
            accepted.append(accepted.isEmpty() ? "" : "|").append(spell(candidate));
        }

        return accepted.toString();
    }

    /**
     * {@code IS_WRAP} on the command line is {@code is-wrap}.
     *
     * <p>Package-private so that the help text can be checked against it rather than against a
     * second copy of the rule.
     */
    static String spell(Enum<?> value) {
        return value.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
    }

    private static Charset charset(String value) throws UsageException {
        try {
            return Charset.forName(value);
        }
        catch (IllegalCharsetNameException | UnsupportedCharsetException e) {
            throw new UsageException("'--charset' names no encoding this build can decode: " + value);
        }
    }

    private static Color color(String value) throws UsageException {
        Color color = Color.parse(value);
        if (color == null) {
            throw new UsageException("'--color' expected auto|always|never but found '" + value + "'");
        }

        return color;
    }

    /**
     * Like {@link #count}, but zero is not a bound this one can take.
     *
     * <p>{@code BundleOptions} throws for a non-positive depth, and a constructor's
     * {@code IllegalArgumentException} reaching a command-line user as a stack trace is the
     * wrong way to say "0 is not a number of levels". Caught here, where the flag is named.
     */
    private static int depth(String flag, String value) throws UsageException {
        int depth = count(flag, value);
        if (depth < 1) {
            throw new UsageException("'" + flag + "' expected at least 1 but found " + depth);
        }

        return depth;
    }

    private static DiagnosticFormat format(String value) throws UsageException {
        DiagnosticFormat format = DiagnosticFormat.parse(value);
        if (format == null) {
            throw new UsageException("'--diagnostic-format' expected auto|rich|short but found '" + value + "'");
        }

        return format;
    }

    private static int count(String flag, String value) throws UsageException {
        try {
            int count = Integer.parseInt(value);
            if (count < 0) {
                throw new UsageException("'" + flag + "' expected a count but found " + value);
            }

            return count;
        }
        catch (NumberFormatException e) {
            throw new UsageException("'" + flag + "' expected a count but found '" + value + "'");
        }
    }

    private static void validate(Verb verb,
                                 List<String> inputs,
                                 Path output,
                                 Path outDir,
                                 boolean inPlace,
                                 boolean bundle) throws UsageException {
        if (inputs.isEmpty()) {
            throw new UsageException("no input; name a file, or '-' for standard input");
        }

        int destinations = (output != null ? 1 : 0) + (outDir != null ? 1 : 0) + (inPlace ? 1 : 0);
        if (!verb.writesOutput()) {
            if (destinations > 0) {
                throw new UsageException("'" + verb.verbName() + "' writes nothing, so it takes no destination");
            }

            return;
        }

        if (destinations > 1) {
            throw new UsageException("give at most one of '-o', '--out-dir' and '--in-place'");
        }

        if (bundle) {
            validateBundle(outDir, inPlace);
            return;
        }

        if (inPlace && inputs.contains("-")) {
            throw new UsageException("'--in-place' cannot rewrite standard input");
        }

        // Not an implicit concatenation to stdout: CSS concatenation is cascade-order
        // sensitive, and bundling exists to do it properly, so the accidental version should
        // not be reachable by forgetting a flag. '--bundle' is that flag, which is why the
        // rule sits below the branch above rather than growing an exception.
        if (inputs.size() > 1 && destinations == 0) {
            throw new UsageException("several inputs need a destination; give '--out-dir', "
                                     + "'--in-place', or '--bundle' to make them one stylesheet");
        }

        if (inputs.size() > 1 && output != null) {
            throw new UsageException("'-o' writes one file, so it takes one input, or "
                                     + "'--bundle' to make several into one");
        }

        if (outDir != null) {
            validateOutDir(inputs);
        }
    }

    /**
     * {@code --bundle} produces one stylesheet, so the two per-input destinations stop making
     * sense and are usage errors rather than being quietly reinterpreted.
     *
     * <p>{@code --out-dir} would have one output and a directory to put it in, but the name it
     * mirrors is a name it no longer has: there is no one input the bundle came from. And
     * {@code --in-place} would have to pick an input to overwrite with the whole bundle, which
     * would destroy the others' contents by including them and destroy the chosen one's by
     * replacing it. Both are spelled {@code -o}.
     *
     * <p>What {@code --bundle} makes legal is the pair of rules below it: several inputs with
     * {@code -o}, and several inputs with no destination at all, since concatenating to
     * standard output is now what was asked for rather than what was forgotten.
     */
    private static void validateBundle(Path outDir, boolean inPlace) throws UsageException {
        if (outDir != null) {
            throw new UsageException("'--bundle' writes one stylesheet, so it has no per-input "
                                     + "name for '--out-dir' to mirror; use '-o'");
        }

        if (inPlace) {
            throw new UsageException("'--bundle' writes one stylesheet, so there is no input for "
                                     + "'--in-place' to rewrite; use '-o'");
        }
    }

    /**
     * {@code --out-dir} mirrors file names, not the paths leading to them, so two inputs called
     * the same thing in different directories would land on one output. Rejected rather than
     * resolved: silently writing one and discarding the other is the worst available outcome,
     * and there is no base directory here to mirror a relative path against.
     */
    private static void validateOutDir(List<String> inputs) throws UsageException {
        if (inputs.contains("-")) {
            throw new UsageException("'--out-dir' names its outputs after its inputs, so it "
                                     + "cannot take standard input; use '-o'");
        }

        List<String> seen = new ArrayList<>();

        for (String input : inputs) {
            String name = String.valueOf(Path.of(input).getFileName());
            if (seen.contains(name)) {
                throw new UsageException("two inputs are named '"
                                         + name
                                         + "', so '--out-dir' would write one over the other");
            }

            seen.add(name);
        }
    }

    private Arguments() {
        // utility class
    }
}
