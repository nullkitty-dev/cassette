# c(a)ssette - CLI runner

*Design record for the command-line tool.
Companion to [ARCHITECTURE.md](ARCHITECTURE.md), which owns the library, [BUNDLING.md](BUNDLING.md), which owns the coordinate space, and [SOURCEMAPS.md](SOURCEMAPS.md), which owns the `--source-map` flag group.
Figures come from [PERFORMANCE.md](PERFORMANCE.md).*

The library's public surface is four static calls: `CssParser.parse`, `Flattener.flatten`, `Optimizer.optimize`, `CssSerializer.serialize`.
This is a command-line driver over exactly those, with a flag for every option each of them takes.

Two things make it worth having beyond the tool itself.
It is the first consumer of the public API that is not a test.
And it is the natural home for the filesystem `Importer` the bundler needs, since the library [never touches a filesystem](BUNDLING.md#the-importer-returns-bytes).

---

## Goal & non-goals

**What it is.**
`cassette <verb> [flags] <input…>`.
Reads CSS from files or stdin, runs the pipeline, writes CSS to stdout, a file, or in place, and reports diagnostics with a file, line and column.
Every axis `SerializerOptions` carries is a flag; every transform in `Optimizations` is selectable by name.

**What it is not.**
Not a build tool: no watch mode, no config file, no plugin system, no dependency graph output.
Not a linter with its own rule set, since the diagnostics it prints are the ones the parser and serializer already produce.
Not a second implementation of anything: it holds no CSS knowledge, and every behavior it has is the library's behavior with a flag in front of it.
If we ever have to fix a CSS bug in here, the fix is in the wrong place.

Not part of the published library.
It ships as its own jar and can be deleted without touching a line of `src/main`, the same standard [`tools/differential-fuzz`](../tools/differential-fuzz/README.md) is held to.

---

## Decisions

### It lives in `src/cli`, not in `src/main`

A fourth source set in the one Gradle module, compiled off the module path exactly as `test` and `jmh` are.

This keeps three things the library has committed to.
The published jar stays a library with no `Main-Class`; `module-info.java` needs no new export, so the [exported surface](ARCHITECTURE.md#public-api-surface) is unchanged; and javadoc's strict doclint keeps covering only what a consumer can call.

It also keeps the CLI honest.
Living outside `src/main` means it can only use what a real embedder can, so a CLI feature needing something the library does not expose surfaces as a decision about the public API rather than a quiet reach into a package-private class.
The one such decision is [below](#the-cli-cannot-see-the-text-the-spans-index-into).

Rejected: a second Gradle subproject.
The [single-module decision](ARCHITECTURE.md#project-setup) declined cross-module version coordination for a project this size, and a CLI does not change that.

Build wiring is a `Jar` task with a `Main-Class` manifest plus a `JavaExec` run task, following `memoryCensus` rather than introducing the `application` plugin.
With zero dependencies the "fat" jar is the library's classes plus the CLI's, so there is nothing to shade.
`check` depends on `compileCliJava`, so a broken CLI cannot pass a build that never asked for it.

### Three verbs, and the verb owns the formatting axis

```text
cassette format  [flags] <input…>     # PRETTY
cassette minify  [flags] <input…>     # MINIFIED
cassette check   [flags] <input…>     # diagnostics only, no output
```

Subcommands over one flat command, with the verb owning one axis: **`Formatting` is not a flag.**
`format` and `minify` accept the identical flag set and differ in exactly one option.
There is no `cassette format --minified`, because that is spelled `cassette minify`.

`check` is worth a verb rather than a `--no-output` flag because it is the shape CI wants: parse everything, print what is wrong, exit nonzero if anything is, write nothing anywhere.

**Bundling is not a verb.**
It is an input-side operation, several sources becoming one tree, while formatting is output-side, and making both verbs would force a choice between `bundle --minified` and a `bundle` that cannot minify.
As a flag group it is orthogonal to all three verbs, and `check --bundle` then means "validate this import graph".
See [Bundling](#bundling).

**`minify` means what `Formatting.MINIFIED` means, and nothing else.**
Whitespace and comments, no semantic change.
Optimizations are `-O` and are opt-in under every verb.
This is the [one meaning](ARCHITECTURE.md#minify-means-exactly-one-thing) the library renamed `Minifier` to protect, and a CLI verb quietly reintroducing the second one would undo it in the most visible place available.

### The CLI cannot see the text the spans index into

This is the sharpest edge in the design, and the only place it needs something the library does not otherwise expose.

A diagnostic carries a `SourceSpan`, and a span's offsets are **post-preprocessing character offsets**, into the decoded `char[]`, after section 3.3 has turned CRLF into LF and NULL into U+FFFD.
`SourceText` is in `lexer`, which is never exported, so a CLI handing bytes to `CssParser.parse(byte[])` gets back offsets into a buffer it has no way to obtain.
It cannot count lines in the original file instead: CRLF collapsing shortens the buffer, so every offset after the first CRLF is wrong by the number of them preceding it.

Three ways out, and only one is acceptable.

_Re-implement detection and preprocessing in the CLI_ duplicates the highest-risk code in the library against the one thing a second implementation must never disagree about.

_Put the text on `ParseResult`_ makes every embedder retain the decoded buffer for the tree's whole lifetime, which is precisely the cost [the serializer](ARCHITECTURE.md#serializer--output-modes) declined for byte-exact round-tripping.
A CLI holding it for one file is fine; a record component holding it for everyone is not.

So: one public method that decodes without parsing.

```java
// dev.nullkitty.cassette.parser.CssParser
public static String decode(byte[] source, Charset protocolEncoding, Consumer<Diagnostic> sink)
public static String decode(byte[] source, Charset protocolEncoding)   // discards
public static String decode(byte[] source)                             // discards
```

The CLI decodes once, builds a line index over the result, and calls `CssParser.parse(CharSequence)` on the same text, so the offsets it renders and the offsets the parser produced are offsets into one string it holds.

Two things make this cheap rather than a new surface we would regret.
It exposes no new type: the return is a `String`, and the algorithm behind it was already public behavior, just unreachable without also parsing.
And it is **the single-source degenerate case of `SourceIndex.textOf(SourceSpan)`**, which [BUNDLING.md](BUNDLING.md#surface) had already committed to.

_The invariant it rests on:_ `decode(bytes)` is a fixed point of preprocessing, so re-running it inside `parse(CharSequence)` moves no offset.
We checked it against `SourceText.preprocess`, and it holds, because the output alphabet excludes every input the function reacts to: `\r` and `\f` have become `\n`, `\0` and every unpaired surrogate have become U+FFFD, and a surrogate pair is copied through intact.
A BOM is stripped during decode and never reaches the text.
It holds by argument rather than by construction, and a rule that normalized, say, U+FFFD or a non-breaking space would break it silently.
Hence the test.

**The CRLF test needs thirty lines, not eleven.**
The drift is one character per preceding line, so with eleven the column moves from 18 to 7 and the _line_ stays right, which is a test that would pass against the bug it exists for.
Thirty puts the report on a different line, which is what the assertion can see.

### `decode` needs the diagnostic sink, or the CLI loses the charset warning

`CssParser.parse(byte[])` emits a `WARNING` when a stylesheet declares an `@charset` that cannot be resolved, because [silence there](ARCHITECTURE.md#lexer--input-handling) made a mis-decoded stylesheet indistinguishable from a correctly decoded one.
It is produced by `reportCharsetFallback`, which reads `SourceText.unresolvedCharset()` during the _byte_ entry point.

`parse(CharSequence)` cannot produce it, because its `SourceText` is built from already-decoded text and there was no detection to fall back from.
A CLI decoding to a `String` and then calling `parse(CharSequence)` therefore **silently drops the one diagnostic the charset work exists to raise**, in the one consumer whose whole job is showing diagnostics to a human.

The fix costs no new type and no new pattern, because the library already has one in the serializer's optional sink.
The CLI passes the same reporter it hands `serialize`, so the charset warning, the parse diagnostics and the serializer's dropped-`url()` warning all arrive at one place in source order.
The discarding overloads carry the same risk of being the ones a caller reaches for, which is why the sink form is listed first.

_This is not a CLI problem, which is what decides where the fix goes._
The bundler decodes and parses as two steps for the same reason and loses the same warning, which [BUNDLING.md hits independently](BUNDLING.md#encoding-is-per-source-and-an-import-inherits-its-parents).
Two consumers, one structural cause: the report is produced by `parse` and describes something that happened during `decode`.
So the fix belongs at the decode step and is shared, not duplicated.

### The renderer's interface is not `SourceIndex`, and cannot be

The tempting shape is "a narrow interface that a line index satisfies now and a `SourceIndex` satisfies later".
That is right in intent and wrong in detail: [`SourceIndex`](BUNDLING.md#surface) resolves an offset to an `Origin(sourceId, offset)` and slices text for a span, and **it has no notion of lines or columns at all**.
Neither should it, because counting lines is a rendering concern.

So the split is: the library resolves a span to _which source and where in it_, and the CLI computes line, column and the line's text from that.
The narrow interface is about location, not lines:

```java
package dev.nullkitty.cassette.diagnostics;

@FunctionalInterface
public interface SourceResolver {
    Location locate(SourceSpan span);

    // The single-source case, which is every tree CssParser.parse produces.
    static SourceResolver of(String sourceId, CharSequence text) { ... }

    record Location(String sourceId, CharSequence sourceText, int offset, int length) {
        CharSequence text();
    }
}
```

`of` is a static factory on the interface rather than a class in the CLI.
Four lines, needed by every embedder that renders a diagnostic, and no new public type, against a library that would otherwise export an interface with no implementation and let each caller rewrite it.
`Location`'s compact constructor range-checks the offset against the text, which is the one thing a hand-written implementation is likely to get wrong.

Both implementations are small.
The single-source one returns the file name, the string it decoded, and `span.start()` unchanged.
`SourceIndex` binary-searches its segment table, returns the segment's `sourceId` and `span.start() - segment.base`, and slices the segment's text.

The CLI caches a line index per `sourceId`, which is what makes this shape worth having.
In a bundle, consecutive diagnostics usually fall in the same source, and the alternative, re-counting newlines from offset zero of a megabyte-scale bundle per diagnostic, is quadratic in the thing `--max-diagnostics` exists to bound.

It lives in `diagnostics` alongside `Diagnostic` and `Severity`, and having a home is part of why that package won the argument.

### Diagnostics render as `file:line:col: severity: message`

```
style.css:12:3: error: unclosed ( ) block, which consumed everything after it
style.css:12:3: note:   background: rgb(0 0 0;
```

The conventional shape, because every editor and CI log scraper already parses it.
Column is a 1-based count of characters, not bytes and not display columns, because the tab-and-East-Asian-width question is not one a CSS tool should be inventing an answer to.

The serializer's diagnostics go to the same place, which means the CLI must use the `Consumer<Diagnostic>` overload of `serialize`.
The overloads without a sink discard, and the unspellable-`url()` drop is [the serializer's own limitation](ARCHITECTURE.md#writing), so a CLI wired to the two-argument overload silently throws away the one diagnostic nothing else in the system would ever name.

`--strict` promotes every `WARNING` to the exit code without changing how it prints.
Real CSS routinely produces warnings, since a forgiving `:is()` list dropping an alternative is a warning by design, so warnings must not fail a build by default, and a project that wants them to should not have to grep the output.

### Exit codes

|   |                                                       |
|---|-------------------------------------------------------|
| 0 | no errors; warnings may have printed                  |
| 1 | at least one `ERROR`, or a `WARNING` under `--strict` |
| 2 | usage error: unknown flag, bad enum value, no verb    |
| 3 | I/O error: input unreadable, output unwritable        |

Separating 2 and 3 from 1 lets a script tell "your CSS is broken" from "your invocation is broken", which are acted on by different people.
Exit 1 does **not** mean nothing was written: recovery is defined behavior, so a file with errors still produces output, and the errors describe what recovery did to it.

### A value-optional flag takes its value only attached

`-O`, `--help` and `--source-map` are the flags whose value is optional, and the space-separated form cannot work for any of them: `cassette minify -O style.css` is ambiguous between a transform named `style.css` and an input file.
Resolving it by looking (does the file exist, does the name match a known transform) produces a rule that behaves differently depending on the directory it runs in.

So: **a flag whose value is optional takes it only with `=`.**
`-O` and `--optimize` alone mean `Optimizations.all()`, which is _not_ every name the flag accepts, see [below](#surface).
`-O=shorten-colors` names a subset, and the token after a bare `-O` is always an input.
Every other flag accepts both forms.

`--help` gets a second spelling instead of an awkward one, because a verb can name itself: `cassette check --help` is the natural way to ask, and `--help=check` also works.

### Diagnostics go to standard error, even under `check`

Standard output carries CSS and nothing else, so `cassette minify a.css > a.min.css` cannot produce a file with an error message in the middle of it.
The temptation is to exempt `check`, whose diagnostics _are_ its output.
Not worth it: a `check` being read by a person is being read from a terminal, where the two streams interleave anyway, and a `check` being read by a program is being read for its exit code.
One rule that never has to be explained beats a second rule that saves a redirect.

### Diagnostics print in source order

The library reports in the order recovery found things, which puts the consequence before the cause: an unclosed construct is only reported once the parser has run out of input, so a stray `}` swallowed on the way there is named first.
That order is right for a list a program walks and wrong for a list a person reads down their file, so the CLI sorts by offset.
A rendering decision; the library keeps reporting in the order it finds things.

### No input is a usage error

`-` means standard input, so a bare `cassette check` reading stdin would give two spellings for one thing, and the silent one is the one that hangs at a terminal with no indication why.

### Argument parsing is hand-rolled, and stays boring

Zero runtime dependencies is [policy](ARCHITECTURE.md#project-setup), so picocli is not on the table.
What we had to decide is how little to build: `--flag value` and `--flag=value`, a handful of single-dash short forms, `--` ending flag parsing, `-` meaning stdin.
No clustering of short flags, no abbreviation matching, no negatable `--no-*` pairs except where a flag genuinely has a default-on counterpart.

Flag precedence needs no rules, because the builder already has them.
`--legacy` maps to `SerializerOptions.Builder.legacyCompatible()`, which fills in only what was not set explicitly, so `--legacy --nesting preserve` and `--nesting preserve --legacy` both give preserved nesting.
The CLI gets order-independence by mapping flags onto the builder in the order it sees them and calling `build()` once.

`Cli.run(args, in, out, err)` returns an exit code and `main` is a three-line wrapper, so no test forks a JVM.
Streams are parameters, not `System.out`.

### `--out-dir` mirrors file names, and rejects a collision

"Names mirrored" leaves open whether `--out-dir out` turns `src/a.css` into `out/a.css` or `out/src/a.css`.
Mirroring the path needs a base directory to be relative _to_, which no flag supplies and which an absolute input escapes anyway, so it mirrors the file name alone.

That flattens, and flattening can collide: `a/x.css` and `b/x.css` both want `out/x.css`.
The collision is a **usage error**, checked before anything is read.
Writing one and silently discarding the other is the worst outcome available, and it is what happens by default if nobody looks.

### Every write goes through a temporary sibling and a rename

Not just `--in-place`.
A failure part-way through destroys the only copy, and that is just as true of an existing `-o` target, so there is one rule with no exception to remember.
The temporary is a sibling rather than in the system temp directory, because a rename across filesystems is a copy and buys none of the property.

### Output is UTF-8, and an `@charset` can lie

The library keeps `@charset` as an ordinary statement at-rule, which is a difference from the CSSOM it takes on purpose, because dropping it is semantic validation.
Writing UTF-8 therefore produces this:

```
$ cassette format --in-place legacy.css     # was Shift_JIS
@charset "shift_jis";                        # ...and the bytes are now UTF-8
```

Reading that file back decodes UTF-8 bytes as Shift_JIS and corrupts it, and under `--in-place` the corrupted copy is the only one.
So the CLI **warns** when a stylesheet it is writing carries an `@charset` naming anything but UTF-8.

A warning and not a rewrite.
Dropping the rule or restating it changes what the stylesheet says, which is an opt-in transform's decision and not one for whichever command happened to write the file.
`-O=drop-charset` is that transform, and the warning names it.

Why UTF-8 at all, rather than the encoding the input was read in: getting that back out needs an accessor the library does not offer, and writing a legacy encoding back out by default would be the wrong default even if it did.

### And a `sourceMappingURL` can lie in exactly the same way

An input carrying `/*# sourceMappingURL=app.css.map */` is asserting where its own source positions came from, and that assertion is a comment.
So `format` keeps it and the output claims a map generated against the _input_, whose every offset moved when the file was reformatted.
`minify` strips it, because `Formatting.MINIFIED` strips comments, so **the two verbs disagree and neither said anything**.
Under `--bundle` it is worse: one survives per input, in the middle of the output, and tools honor the last one in a file, so the bundle silently claims whichever input came last.

This is the section above with different metadata, an input's claim about itself that rewriting invalidates, so it gets the same answer: the CLI **warns**, per surviving annotation, against its own file.
[SOURCEMAPS.md](SOURCEMAPS.md#an-inputs-own-trailer-is-a-problem-and-it-is-the-charset-problem-again) carries the argument, including why chaining an input map is the real fix and a separate feature.

One implementation note that is easy to get backwards: **whether the annotation survived is asked of the finished output, not predicted from the flags.**
Whether comments live is the serializer's rule, and a second copy of it here would be a copy that can drift.

### The CLI writes exactly what the serializer returned

Not even a trailing newline on minified output, which is tempting and would be the CLI inventing a byte.
`CliTest.writesExactlyWhatTheSerializerReturns` compares the two directly across four flag sets, which keeps "every behavior it has is the library's behavior with a flag in front of it" literally true.

The one exception is the `sourceMappingURL` trailer, and [SOURCEMAPS.md argues it](SOURCEMAPS.md#the-sourcemappingurl-trailer-is-the-clis-and-it-is-the-one-written-exception): the trailer is the one byte that may legitimately differ between a development and a production build of the same stylesheet, so it has to look like this tool linking two files it is creating.

### Multiple inputs need an explicit destination

One input and no destination flag writes to stdout, which is what a pipe wants.
Two or more inputs and no destination is a usage error, not an implicit concatenation to stdout.
CSS concatenation is cascade-order-sensitive and `--bundle` exists to do it properly, so the accidental version should not be reachable by forgetting a flag.

`--in-place` on stdin is a usage error for the obvious reason, and so is `--out-dir` on stdin, which has no name to mirror.

### Drawing the source costs one field nobody was reading

```
error: malformed url()
 --> u.css:1:9
1 | .a { b: url(bad url) }
  |         ^^^^^^^^^^^^
```

`SourceResolver.Location` carries `sourceId`, `sourceText`, `offset` **and `length`**, and the short form read only the offset.
So the caret's width was already in hand, and the whole feature is one file in `src/cli`, with no library change and no public surface.

**Real spans are tight**, which was measured before the code was written: an unmatched `}` is one character, a `malformed url()` exactly its own text, an unparseable `:is()` alternative exactly the alternative.

**Four lines and not five.**
No surrounding lines are drawn, so the blank gutter line that would separate them from the header has nothing to separate.
Context lines exist in the tool this imitates because _its_ diagnostics carry several labeled spans and a reader has to see the relationship between them; a `Diagnostic` carries one span.
A blank line does go **between** diagnostics, without which two snippets read as one eight-line block.

Nothing says how far a long span reaches.
A first draft appended "...to end of input" whenever the span outran the line, and it was wrong twice: the two diagnostics that most often do this already name the consequence in the message, and the span may equally end three lines down, where the label asserted something false.
A trailing `…` says only what is true.

Long lines are windowed, and that is what makes it usable rather than a nicety.
Bootstrap has a 525-character line, and a minified stylesheet is one line of however many megabytes the file is.

Visual cells are measured here and nowhere else, which is not a contradiction of `LineIndex`.
That class [refuses display widths](#the-renderers-interface-is-not-sourceindex-and-cannot-be) on the grounds that a column is a character count and picking a tab width and a width table is not a CSS tool's business.
That stays true of the number a machine reads off `file:line:col`.
A caret has to land under the glyph a person is looking at, which is a different question.
Tabs are four cells, East Asian glyphs two, and a surrogate one each, which sums to the two an emoji occupies, so a pair needs no case of its own.

The default follows `--color`, for its reason and not for symmetry.
A redirected stream is being read by a machine and `file:line:col` is what gets scraped, so `auto` is rich at a terminal and short in a pipe.
`System.console()` is null under the test harness too, which is why every pre-existing renderer assertion still describes the default.

**Three alignment hazards are mutation-checked**, because each fails silently by nature: a caret one column off still looks like a caret.
Counting the elision marker as no cell, a tab as one, or a wide glyph as one each fail exactly one test apiece.

_One thing it turned up, which is a finding about the diagnostics rather than the renderer._
Drawing a caret under [the known selector-prelude gap](ARCHITECTURE.md#error-recovery--diagnostics) makes it plain how misleading that message is: for `.c { font-family: "unterminated;` it points at the colon and says _expected a pseudo-class or pseudo-element name after ':'_.
The one-line form never showed that.

### Native image is viable, and the charset table is the whole risk

The CLI is a good native-image candidate and the library does nothing to prevent it.
`src/main` contains no reflection, no dynamic class loading, no resource lookup, no `ServiceLoader`, no proxies, no `MethodHandle`/`VarHandle`/`Unsafe`, and every case fold is ASCII-only.
Zero runtime dependencies means no third-party reflection configuration to chase.
Records, sealed hierarchies and pattern matching are all statically analyzable.

`./gradlew nativeCompile` produces a 23 MB executable in about 24 s.
`./gradlew build` resolves no GraalVM toolchain, which is the standing constraint and worth re-checking if the plugin is upgraded.
`org.graalvm.buildtools.native` is a build-time Gradle plugin, the same category as `me.champeau.jmh`, so it does not touch the [zero-runtime-dependency policy](ARCHITECTURE.md#project-setup).

The one real risk is `CssEncoding`'s charset table.
Native Image ships only UTF-8, ISO-8859-1, US-ASCII, the UTF-16 family and the platform default unless `-H:+AddAllCharsets` is passed, and windows-1252 is _not_ on that list, which is where the Encoding Standard sends `iso-8859-1`, `latin1`, `us-ascii` and `ascii`.
So the flag is needed even for a build that only claims to handle Western text.
Dropping it costs **33 of the 36 catalogued charsets**, everything except UTF-8 and the UTF-16 pair, which come from `StandardCharsets`.

That failure is invisible to every other check: the binary builds, runs, and parses every UTF-8 stylesheet there is.
It fails on legacy input, at run time, on someone else's machine.
So `nativeCharsetCheck` asks the **binary**, over the same label list, and greps for the `this build cannot decode` branch of `CssParser.charsetFallbackMessage`.
The list lives in `src/test/resources/charset-labels.txt` so the JVM test and the binary check read one list instead of two that have to agree.
The check carries a positive control, a label nothing could resolve which must still be reported, so a binary whose diagnostics were broken outright cannot pass it vacuously.

The library half of this is already in place: a declared `@charset` that cannot be resolved produces a `WARNING`, and `CssEncoding.catalogues` distinguishes "no such encoding" from "this build cannot supply it", which is exactly the native-image case.
Charsets also resolve lazily rather than in a static initializer, which keeps the table out of the build-time heap.

**Two plugin traps, both of which fail quietly.**

* **Without the `application` plugin, the GraalVM plugin builds a shared library.**\
  The build succeeds, prints `BUILD SUCCESSFUL`, and produces `cassette.dylib` plus four C headers.
  Setting `mainClass` does not flip it back; only `sharedLibrary = false` does.
  This project has no `application` plugin on purpose, so it lands in the trap by construction.
* **`JvmVendorSpec.GRAAL_VM` means GraalVM _Community_.**\
  SDKMAN's `-graal` candidates are Oracle GraalVM, which reports its vendor as `Oracle` and does not match.
  Selecting on `nativeImageCapable = true` says what is actually required and holds for either distribution.

Both artifacts ship; the native one does not replace the jar.
Native image has no profile-guided optimization unless asked for and defaults to Serial GC, on a workload that allocates several megabytes per parse.
Both are the same code behind the same flags.

**What it measures**, wall-clock per invocation over 20 runs, 5 for Tailwind:

|                            | native       | `java -jar` |       |
|----------------------------|--------------|-------------|-------|
| `minify` 3.6 kB            | **5.3 ms**   | 61.2 ms     | 11.5x |
| `minify` Bootstrap, 281 kB | **11.9 ms**  | 115.7 ms    | 9.7x  |
| `minify` Tailwind, 3.6 MB  | **104.7 ms** | 280.2 ms    | 2.7x  |
| `minify -O` Tailwind       | **112.8 ms** | 321.4 ms    | 2.8x  |

The ratio narrows with size exactly as a fixed startup cost should, but **it never crosses**.
Output is byte-identical between the two on every case checked.

_The hedge that the jar might win on large input did not survive being measured._
A one-shot CLI process never reaches steady state.
It pays 40-60 ms of JVM startup and then interprets its way through most of the work before C2 would have finished compiling anything.
The hedge is sound for a _long-lived embedder_ of the library, which is a different artifact and a different question.

_These are not JMH numbers and must not be filed next to them._
They are wall-clock timings of a whole process, which is the thing a native image is for and the thing JMH excludes.
A native figure is a different measurement of a different runtime, and filing it next to the corpus numbers would corrupt the one baseline the performance work is held to.

---

## Surface

```
cassette format [flags] <input…>
cassette minify [flags] <input…>
cassette check  [flags] <input…>

Output
  -o, --output <file>        write to one file (single input, or --bundle)
      --out-dir <dir>        write one output per input, names mirrored
  -i, --in-place             rewrite each input where it sits
                             (default: stdout, single input only)

Serialization                                     format, minify
      --nesting preserve|flatten                  default: preserve
      --expand is-wrap|duplicate                  default: is-wrap; only read when flattening
      --identifiers literal|ascii                 default: literal
      --legacy                                    legacy-safe defaults for the three above
  -O, --optimize[=<list>]                         default: none
                                                  all | none | lowercase-names, shorten-colors,
                                                  drop-zero-units, compact-numbers
                                                  'all' and a bare -O mean those four; the two
                                                  below rewrite what the input claims about
                                                  itself and must be named explicitly:
                                                  drop-charset, drop-source-map-url
                                                  -O repeats to compose, as -O -O=drop-charset

Input
      --charset <name>       transport-supplied encoding; a BOM and @charset both outrank it

Source maps
      --source-map[=file|inline|none]  default none; bare --source-map means file. 'file'
                                       writes <output>.map beside the output and needs one,
                                       so it cannot go to a pipe; 'inline' can
      --source-map-url <url>           what the trailer names; default is the map file's name
      --no-source-map-content          omit sourcesContent

Bundling
      --bundle                     inputs become one stylesheet in cascade order
      --import-root <dir>          resolve @import under this root (repeatable)
      --no-imports                 leave every @import in the output
      --banners                    a comment naming each source at its boundary
      --max-import-depth <n>       default 64

Diagnostics
  -q, --quiet                errors only
      --strict               warnings affect the exit code
      --color auto|always|never
      --diagnostic-format auto|rich|short   default auto
      --max-diagnostics <n>  default 100, then a count of the rest

  -h, --help[=<verb>]        also `cassette <verb> --help`
      --version
```

`-O` names map to `Optimizations` methods one-for-one, kebab-cased.

`all` is not every name, and that is the library's decision showing through.
`Optimizations.all()` is every optimization that _rewrites a value_.
`dropCharset` and `dropSourceMappingUrl` remove an assertion the input made about itself, and whether that assertion has gone false depends on what is done with the output, a question no "optimize everything" list can answer for a caller.
`dropCharset` is the sharp case: the library returns a `String` and cannot know what encoding it is written in, so the CLI, which _does_ know it always writes UTF-8, is the one that can honestly warn, and the user is the one who opts in.
So a bare `-O` means what it always meant, `Transform` carries an `inAll` flag, and the two are reachable only by name.
They compose by repetition, `-O -O=drop-charset`, because `all` is a whole-value keyword and admitting it into a list would raise the precedence question `none` is kept out of.

**Both stale-metadata warnings name the flag that silences them**, which is the point of building the transforms at all.

**`-O` accumulates.**
Every occurrence adds to one set, so `-O=all` and a named subset compose without a precedence rule, and `none` names no transform rather than clearing what another `-O` asked for.
A subset is passed to `Optimizer.optimize` in `Optimizations.all()`'s canonical order rather than the order given on the command line, because the pass fuses them into one walk and [re-dispatches when a transform changes a node's type](ARCHITECTURE.md#the-optimization-pass), so command-line order is not a meaningful control.

The enum is what makes `-O` four small pieces rather than four decisions.
`Transform`'s constants kebab-case through the same `spell` the other enum flags use, so `-O=shorten-colors` validates on the path `--expand=is-wrap` already took; `Arguments` collects into an `EnumSet`, so canonical ordering and de-duping are properties of the data structure; and `Options` holds `List<Transform>`, already ordered, so `Cli` re-interprets no flag.

What is not structural is the agreement with the library.
Nothing makes the enum match `Optimizations.all()` in either membership or order, and a fifth transform added there would be missing by name, missing from the help text, and quietly absent from bare `-O`.
`ArgumentsTest.declaresEveryLibraryTransformInTheLibrarysOrder` is the only thing standing there.
It compares the two lists by the node types each transform declares, since that is what distinguishes them from outside, and it was mutation-checked by reordering two constants.
Two future transforms declaring the same node types would weaken it without breaking it.

`--expand` is accepted under `--nesting preserve` and ignored, matching the builder.
A warning there would fire on every invocation that sets both from a shell alias.
`check -O` and `check --source-map` are accepted and do nothing, on the same principle.

### Bundling

`--bundle` makes `-o` legal with several inputs and `--out-dir`/`--in-place` usage errors.
`check --bundle` validates an import graph and writes nothing.

The filesystem `Importer` is CLI code, around the six lines BUNDLING.md predicts, and it carries one policy the library cannot: **it resolves only within a declared `--import-root`, and never over a network.**
A specifier escaping every root resolves to empty, which [leaves the `@import` in the output with a warning](BUNDLING.md#an-unresolved-import-stays-in-the-output), already the designed behavior for an importer that declines, so the safe default costs no new mechanism.
With no `--import-root`, the root is each input file's own directory.

Four details worth stating:

* **A relative specifier resolves against the importing sheet's own directory**, which is what CSS says.
  The roots are a fence around the answer, not the base for computing it.
* **Standard input contributes no root**, having no directory, so a bundle read from a pipe resolves nothing unless a root is declared.
* **The group is accepted without `--bundle` and does nothing**, the answer `--expand` already gets.
* **`--max-import-depth` is validated here**, because `BundleOptions` throws below 1 and a constructor's `IllegalArgumentException` reaching a command-line user as a stack trace is the wrong way to say that 0 is not a number of levels.

A source needs a canonical id, and `SourceIds` is the only place that decides one.
Cycle detection compares ids, so a file reached as `a.css` on the command line and as `./a.css` through an `@import` has to be one source.
But a canonical path is absolute, and printing `/Users/.../src/app.css:12:3` where the same file without `--bundle` prints `src/app.css:12:3` is the same tool disagreeing with itself about what a file is called.
So ids canonicalize through `toRealPath` and are then relativized against the working directory when they sit under it.

The first version of the cycle test was worthless, and the mutation check is what said so.
It asserted that a cycle was reported, and passed with canonicalization disabled, because two ids that disagree do not close the cycle on the first lap but the second lap re-enters on the canonical id and closes there.
What is actually lost is an extra inlining and a chain naming one file by two paths.
It asserts the exact chain now.
This is the hazard [`CssParserTest.AbandonedBuilds` records](PERFORMANCE.md#building-a-child-list-once) in a new place.

And one policy turned out not to be load-bearing, which is written into its own javadoc rather than left to look stronger than it is.
`FileImporter.hasScheme` declines a URL up front, but removing it changes nothing a test can see: a URL names no file inside a root, so the fence declines it anyway.
It stays as an early exit and as the one place the policy is written down.

The bundling path proved the library had settled what it claimed to have settled.
`BundleResult.sourceIndex()` implements `SourceResolver`, so `DiagnosticRenderer` took a bundled tree with **no change at all**, which is the real test of whether [that interface](#the-renderers-interface-is-not-sourceindex-and-cannot-be) was shaped right.
And a diagnostic in an imported sheet names that sheet rather than the entry, which works only because the renderer resolves through the index instead of reaching for text it decoded.
`SourceSpan.text` would have returned the wrong characters without failing.

### Both sides of a relativize have to agree

_Found by a bundle test, and it is a defect rather than a surprise._

`sources` entries are relativized against the map file's directory, which is [the CLI's job](SOURCEMAPS.md#file-sourceroot-and-relative-paths-belong-to-the-caller) because it is the one component holding both paths.
The obvious implementation relativizes the source id against the output's parent, and it is wrong whenever the two are spelled differently.

A bundled source's id has already been through `SourceIds`, which calls `toRealPath`.
An output path has not, being whatever was typed after `-o`.
On macOS every temporary directory is under `/var`, a symlink to `/private/var`, so the two disagree about the same directory and `relativize` climbs to the filesystem root and back down: a `sources` entry with a dozen `../` segments in it, which _resolves correctly_ and is unreadable.
Nothing fails; it just produces garbage that works.

The fix is to canonicalize both sides with one function rather than to canonicalize neither.
That function also has to cope with the output directory **not existing yet**, since it is created when the file is written, so it resolves the longest existing prefix and re-attaches the rest instead of giving up the first time `toRealPath` throws.

---

## Testing

The CLI's own tests live in `src/test` and drive it as a library, through `run(String[] args, ...)` returning an exit code, so no test forks a JVM or installs a `SecurityManager` to intercept `System.exit`.

What is worth asserting:

* **Flag-to-options mapping**, exhaustively, as a table.
  Every enum value, every alias, `--legacy` in both orders against an explicit override.
  This is where a CLI actually breaks, it is pure and fast to test, and none of it needs a file.
* **The diagnostic renderer against a source with CRLF**, the one case the whole `decode` scheme exists for and the one a hand-written test would never think to include.
* **Exit codes**, one case each, including that exit 1 still wrote output.
* **Usage errors**, since a bad flag hitting a stack trace instead of exit 2 is the most likely first bug and the least likely to be noticed.

_Not_ CSS behavior.
Golden fixtures already assert what the pipeline produces for every option combination, and a CLI test asserting the same output would only ever fail in pairs with one of them.
The CLI's tests assert that the right options reached the library, not what the library did with them.

One property: **`cassette format` is idempotent on its own output**, for the same reason and by the same mechanism as [the serializer property](ARCHITECTURE.md#round-tripping).
If it ever is not, the defect is in the library and the CLI is the thing that made it visible.
`CliTest.formatIsAFixedPointOnItsOwnOutput` holds it over four flag sets, against input that includes recovered wreckage, since that is the case where the first pass is allowed to normalize and no pass after it is.
