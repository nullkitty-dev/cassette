# c(a)ssette - Source maps

*Design record for Source Map v3 generation.
Companion to [ARCHITECTURE.md](ARCHITECTURE.md), which owns the library, [BUNDLING.md](BUNDLING.md), which owns the coordinate space, and [CLI.md](CLI.md), which owns the command-line tool.
[PERFORMANCE.md](PERFORMANCE.md) owns what a map costs.*

`CssSerializer.serializeWithMap` returns CSS and a map, `sourcemap` is an exported package, and the CLI has `--source-map`.

The rest of the library had already solved the input half of the problem for us.
Every node carries a span; every rebuild site in `Flattener`, `NestingExpander`, `Optimizer` and `Optimizations` passes the originating node's span through, so provenance survives flattening and optimization; and `SourceIndex` already answers "which file, and where in it".
What we were missing is the _output_ half, since the writer tracked no position at all, plus a `SourceResolver` at the serializer's door, a line index in the library rather than in the CLI, and a VLQ and JSON encoder, which zero dependencies means writing.

---

## Goal & non-goals

**What it is.**
Source Map Revision 3, one map per serialization.
`CssSerializer` records where in the output each rule, selector and declaration was written, resolves each back to the file and offset it came from, and hands back a `SourceMap` alongside the CSS.
It works identically for a tree from `CssParser.parse` and a tree from `Bundler.bundle`, because the only difference is which `SourceResolver` the caller passes.

**What it is not.**
Not an index map, so no `sections`.
Not a map _reader_: chaining an input map, so that a cassette-processed stylesheet generated from Sass points at the `.scss`, needs a parser for the format and is a separate feature.
It is the first thing anyone will ask for, so where it attaches is [written down](#chaining-an-input-map-is-out-of-scope-and-attaches-at-one-line).
No `url()` rewriting, for the reason [BUNDLING.md](BUNDLING.md#goal--non-goals) gives.
And no notion of a filesystem, so no relative paths; see [the caller owns `file`](#file-sourceroot-and-relative-paths-belong-to-the-caller).

---

## The constraint everything else is subordinate to

**Maps on and maps off must produce byte-identical CSS.**

Not the weaker "output is unchanged when the feature is off".
The workflow this exists for is a development build that minifies and optimizes _with_ a map and a production build that emits the same CSS _without_ one, so any byte of difference breaks the premise that what shipped is what was debugged.

As a rule: **no mapping requirement may change a byte of CSS.**
A construct that is awkward to map goes unmapped.
The map degrades; the output never does.
This is written first because it forbids the shortcuts before anyone reaches for them: suppressing a rollback so a mapping survives, inserting a newline so a mapping lands somewhere convenient, keeping a whitespace token that minification drops.

The blast radius, stated precisely.
The recording sites are guarded and cannot append: `if (this.mappings != null) record(...)` writes nothing to the buffer.
The entire risk is one method, `rollback(mark)`, which replaces six raw `out.setLength(mark)` calls in code that runs whether or not maps are on.
Written as the old line plus a conditional truncate, the maps-off path is textually the old code.

_Rejected: a separate mapping-aware writer_, which would keep `CssWriter` untouched.
It is two implementations of the thing that must never disagree, the trade [the CLI declined](CLI.md#the-cli-cannot-see-the-text-the-spans-index-into) when it considered re-implementing preprocessing rather than exposing `decode`.

And maps-on cost is a development cost.
Production is the maps-off path.

---

## Decisions

### The writer records output offsets; line and column come from one post-pass

`CssWriter` appends to a single `StringBuilder`.
The obvious design tracks a line counter and a line-start offset as it writes, and records `(line, column)` at each mapping point.
It is wrong twice.

_It drifts._
`CssWriter.comment()` appends `comment.text()` verbatim, and a comment can contain newlines.
So the writer emits line terminators it does not know it emitted, and every mapping after the first multi-line comment is on the wrong line, silently, since nothing downstream can tell a wrong line from a right one.

_And it cannot be rolled back._
The buffer is truncated by offset in six places.
A mapping list truncated to the same offset is exactly correct; a `(line, column)` pair already committed needs separate bookkeeping to un-commit.

So a mapping is recorded as an **output offset**, and offsets become line and column in a single pass after the last character is written.
That pass is close to free, and only because of ordering: mappings are recorded in increasing offset, so it is one merge walk over the output rather than a binary search per mapping.
Minified output degenerates to `column = offset`.

### Mappings are two parallel primitive arrays

`int[] outputOffset` and `long[] packedSpan`, twelve bytes per mapping and no per-mapping object.
The same discipline as `TokenBuffer`'s structure-of-arrays, one level up, for the same reason: this is the one new allocation the feature makes per serialization, and [allocation is a tracked metric](ARCHITECTURE.md#performance-strategy).
Null when maps are off, which is what makes the guard at each recording site a branch on a field that does not change within a serialization.

Sized from an estimate, not grown blindly.
How many mappings real CSS produces is a question about real CSS, so it was counted before the code was written, the move whose absence let the token buffer's estimate come in a few percent short and [cost 140%](PERFORMANCE.md#the-token-buffer-is-sized-from-a-measured-rate).
The unit is mappings per source character, chosen high: overshooting discards one array, undershooting copies everything so far.
The VLQ `StringBuilder` gets the same treatment, at roughly ten characters per mapping.

Counted over the three corpus entries and one hand-written nested sample, at the three recording sites:

| input                          | as parsed  | re-minified |
|--------------------------------|------------|-------------|
| `small-handwritten.css`        | 0.0275     | 0.0378      |
| `medium-bootstrap.css`         | 0.0307     | 0.0370      |
| `large-generated-tailwind.css` | 0.0268     | 0.0333      |
| a nested authored sample       | **0.0419** | **0.0612**  |

All three corpus entries are compiled, flat CSS, and they understate the rate by a third.
The cause is structural: a nested rule's prelude is `&:hover`, while the flat rule it compiles to repeats the whole ancestor chain, so nesting raises the mapping count and lowers the character count at the same time.
Authored CSS with nesting is precisely the maps-on input, which makes the corpus the one shape this estimate must _not_ be taken from.
Flattening and optimizing move the count by under 1%.

**The rate is 0.05 per source character**, which covers everything measured except re-minified nested input, a shape that has already been through a minifier once and is then processed as nesting, which is not a pipeline anybody runs.
Missing it costs one doubling.

The sizing input exists and survives the pipeline.
`SourceSpan.lengthOf(stylesheet.packedSpan())` is exactly the source character count, and `Flattener` and `Optimizer` both pass it through unchanged.
A hand-built tree carries `NONE`, so the estimate needs a floor rather than trusting a zero.

_The stakes here are far below the token buffer's, which is worth stating so this is not over-tuned later._
Twelve bytes per mapping at 0.05 is **0.6 bytes per source character**, against the token buffer's 12.8x, about 21x less exposure.
This is a cheap check that the estimate is not wrong by an order of magnitude, and it found one thing worth having: the number the obvious corpus would have produced was the wrong number.

### Anything that shortens the buffer must shorten the mapping list

This is the [prefix-before-nothing family](ARCHITECTURE.md#writing) in a new guise.
The writer commits output before it knows whether what follows produces text, and takes it back when it does not.
A mapping recorded inside a region that is taken back points past the end of the output, or into text that means something else.

Six sites:

|                                      |                                                            |
|--------------------------------------|------------------------------------------------------------|
| `CssWriter.atRule`                   | the block that wrote nothing, rewritten as `{}`            |
| `CssWriter.atKeyword`                | the separating space before a prelude of pure wreckage     |
| `CssWriter.opaqueBlock`              | the indent or separator before a value that writes nothing |
| `CssWriter.declaration`              | the space before a value of nothing but bad tokens         |
| `CssWriter.closeBlock`               | the trailing `;` a minified declaration list drops         |
| `CssSerializer.serialize(Node, ...)` | `stripTrailing()` on a fragment                            |

`rollback(mark)` replaces the raw `setLength` at each, so the invariant is structural rather than remembered.
A seventh path added later gets it by using the helper.

The last of the six is not a buffer truncation, which is why it moved into the writer.
`stripTrailing()` ran on the returned `String`, after the writer had finished, so there was no mark to roll back to and no way for a mapping list to follow it.
It is `CssWriter.writeFragment` now, which scans the buffer and rolls back to what it finds.
Chars rather than code points is the same answer: `Character.isWhitespace` is what `String.stripTrailing` tests, and no whitespace code point is supplementary.
It is also slightly cheaper, since the strip no longer copies the string.

### The rollback discipline drops nothing today, and that is measured

Every region the writer takes back holds component values or a single punctuation mark.
Every recording site is a rule, a declaration or a prelude alternative.
**The two sets do not intersect**, so the truncation is reachable on real input and the mapping drop is not: **283,700 rollbacks over the three corpus entries and 200,000 differential-harness samples removed no mapping at all.**

This is not an argument against the helper but the argument _for_ it stated properly: the discipline is insurance against a recording site added later at a finer granularity, which would land inside one of these regions.
What it is not is a live correction, and that difference is what [retires the obvious test for it](#the-test-that-cannot-exist).

### Six recording sites, at rule and declaration granularity

| construct                           | where the mapping points                         | span              |
|-------------------------------------|--------------------------------------------------|-------------------|
| each `ComplexSelector` in a prelude | its first character                              | the selector's    |
| each `Declaration`                  | the property's first character, after the indent | the declaration's |
| `AtRule` and `ConditionalGroupRule` | the `@`                                          | the rule's        |

Not block closers, not component values, not comments.
This is what browser devtools consume for CSS, it is where a person clicking on a rule expects to land, and every finer granularity costs mappings (the array, the VLQ string and the post-pass all scale with the count) for resolution nothing asks for.

We considered a token-level axis on `SerializerOptions` and left it out.
It is surface that would have to be frozen against a use case nobody has named, and the recording sites are the easy part of adding it later.

### The serializer has to be handed a `SourceResolver`

A span is an offset and a length.
It does not know which text it indexes, which is the whole subject of [the trap BUNDLING.md calls its sharpest](BUNDLING.md#the-trap-this-creates), so a tree alone cannot say which file a rule came from.

The interface already exists and already has both implementations: `SourceResolver.of(id, text)` for a tree from one parse, `BundleResult.sourceIndex()` for a bundle.
This is the second consumer of the interface [the CLI shaped](CLI.md#the-renderers-interface-is-not-sourceindex-and-cannot-be) and the first inside `src/main`, which makes it the real test of whether it was shaped right.
It was: `Location` carries the source id, that source's whole text, and the offset within it, which is precisely `sources`, `sourcesContent` and the source position of a mapping.

### A mapping is emitted only for a span with a non-zero length

`SourceSpan.NONE_PACKED` is `0L`, and `SourceSpan.pack(0, 0)` is also `0L`.
A synthesized node with no source is therefore bit-identical to a real zero-width node at offset zero of the first source, and mapping one produces a map that points confidently at the wrong place.

The length test closes it with no new sentinel and no change to `SourceSpan`.
What it gives up is that a genuine zero-width node is never mapped, which costs nothing, because a construct covering no characters is not one of the three things above and the writer has nothing to point at for it.

### A span that resolves to no single source is skipped, silently

`SourceIndex.resolve` and `locate` **throw** for a span covering more than one source, and [BUNDLING.md records why](BUNDLING.md#import-conditions-become-the-group-rules-they-imply): a wrapper synthesized around an imported sheet that imported others covers the whole subtree and came from no one file.
A tree walk that maps at-rules hits this on the first nested import.

So resolution happens in the post-pass, where it can afford to ask rather than assume, and `SourceResolver` has one method for it:

```java
default Optional<Location> tryLocate(SourceSpan span)
```

`SourceIndex` overrides it with the range check it already computes in `segmentFor`, so the non-throwing path costs nothing extra.
Unresolvable spans are dropped with no diagnostic: a map is a best-effort artifact, and a warning per synthesized wrapper would fire once per `@import` on every bundle.

One consequence to state rather than discover.
A synthesized wrapper's prelude [lies outside its own span](BUNDLING.md#import-conditions-become-the-group-rules-they-imply), because the prelude is re-emitted from the _importing_ sheet and keeps that sheet's token spans while the wrapper's span covers the imported one.
So where a wrapper does resolve, its mapping points at the imported sheet's first character rather than at the `@media` an author wrote.

### `sourcesContent` needs no new enumeration API

This looks like a gap and is not, which is worth saying because the obvious fix adds public surface for nothing.
Nothing in the library lists "every source and its text": `SourceIndex.segments()` gives ids without texts, and `SourceResolver` answers per span.

It does not need to.
`Location` hands over the id _and_ the text, so sources are collected as mappings are resolved, in first-seen order.
That is identical for one file and for a bundle, and it cannot list a source that nothing maps into, which is the correct set, since `sources` exists to be indexed by mappings.

### `sourcesContent` costs nothing until `toJson`

It would be easy to assume the serializer needs telling not to collect it.
It does not.
A `Location` hands over the source's text as a `CharSequence` that already exists, held by the `SourceIndex` or the `SourceResolver` the caller passed in, so collecting it is a list of references and retains nothing new.
**The cost is entirely in `toJson`**, where the text is copied into the buffer.

So omitting content is one record away and needs no option, no flag threaded through `SerializerOptions`, and no second code path:

```java
SourceMap lean = new SourceMap(file, null, map.sources(), null, map.mappings());
```

`sourcesContent` is nullable for exactly this, and `toJson` omits the member when it is null.
Measured on Tailwind, minified: the map's JSON is **4,443,856 characters with content and 566,986 without**, against a mappings string of 566,927, so content is **87.2%** of the map and everything else but the segments is 59 characters.

### `names` is always empty, and that is a property of CSS

The `names` array exists for identifier renaming in minified JavaScript.
cassette renames nothing: `Optimizations` shortens colors and compacts numbers, which rewrites _values_, and `lowercase-names` changes a spelling the map still points at.
Every segment is therefore four fields.
Written down so nobody later reads an empty array as an unfinished one.

### `file`, `sourceRoot` and relative paths belong to the caller

The library has no filesystem and cannot compute a path from an output file to a source, which is the same argument [BUNDLING.md](BUNDLING.md#goal--non-goals) makes for declining to rewrite `url()` when a file moves.
Source ids go into `sources` verbatim, and `file` and `sourceRoot` are caller-supplied and nullable.

The CLI is what makes them relative, with `Path.relativize` against the map file's own directory, because it is the one component that knows both paths.

### The `sourceMappingURL` trailer is the CLI's, and it is the one written exception

The CLI's rule is that _it writes exactly what the serializer returned, and adds nothing, not even a trailing newline on minified output_, and `CliTest.writesExactlyWhatTheSerializerReturns` holds it across four flag sets.
Appending `/*# sourceMappingURL=... */` breaks that rule.

It should break it, rather than the serializer growing an option to emit the comment itself.
Two reasons, and the second is the better one:

* The library cannot know the map's file name, so an option would take the URL as an argument and exist only to concatenate it.
* **The trailer is the one byte that may legitimately differ between a development build and a production build of the same stylesheet**, and per [the constraint above](#the-constraint-everything-else-is-subordinate-to) that difference has to be visibly the CLI appending a link between two files it is creating, not the serializer behaving differently depending on a flag.

`SourceMap.trailerFor(String url)` returns the exact comment text, so the spelling stays in the library and the decision to append stays in the CLI.

### An input's own trailer is a problem, and it is the `@charset` problem again

Everything above is about the trailer cassette _writes_.
The trailer an input already **carries** is a separate matter, and it needs no source-map code to go wrong.

`/*# sourceMappingURL=app.css.map */` is an ordinary CSS comment, and cassette [keeps comments](ARCHITECTURE.md#ast-design).
So:

|            |                                                                                                                                                                                  |
|------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `format`   | the trailer passes straight through, and the output claims a map generated against the _input_, whose every offset moved when the file was reformatted                           |
| `minify`   | `Formatting.MINIFIED` strips comments, so it vanishes, correct but only as a side effect                                                                                         |
| `--bundle` | one trailer survives per input, in the middle of the output, and tools honor the **last** one in a file, so the bundle silently claims whichever input came last, for all of it |

This is the stale-`@charset` trap with different metadata.
Both are things the input asserted about itself which rewriting invalidates.
`@charset` then [lies about the bytes](CLI.md#output-is-utf-8-and-an-charset-can-lie); this lies about the positions.
Two independent instances is the argument for scoping the drop transforms as _stale input metadata_ rather than as one at-rule.

The CLI warns, mirroring the `@charset` warning exactly.
`Cli.warnAboutStaleSourceMap` reports each surviving annotation against its own file, on both the single-file and the bundling path.
A warning and not a rewrite, for the reason the `@charset` one gives.

**Whether it survived is asked of the finished output, not predicted from the options**, and that is the detail worth keeping, because the obvious version is wrong.
Comments live or die by `Formatting`, so predicting from the flags is a second copy of a rule the serializer owns.
This is _not_ the thing [the `url()` rule forbids](ARCHITECTURE.md#writing): `writesNothing` has to answer for text not yet written and must therefore be structural, while this runs after the last character, where the output is the most direct evidence available.

`isTrailer` skips CSS whitespace, not `Character.isWhitespace`.
The distinction is the opposite of the one [`writeFragment`](#anything-that-shortens-the-buffer-must-shorten-the-mapping-list) makes, and both are right: that one matches `String.stripTrailing` because it is replacing a call to it, while this one decides whether a comment gets _deleted_ by `dropSourceMappingUrl`.
A comment body has been through section 3.3 preprocessing, so the only whitespace that can reach it is a space, a tab or a newline, and accepting U+2028 as well would claim an annotation across a character no real tool looks past.
Same argument as matching the name case-sensitively.

A way to act on it exists: `Optimizations.dropSourceMappingUrl()`, and `dropCharset()` beside it.
The warning names its own fix.

Neither is in `Optimizations.all()`, and the reason is an asymmetry that is easy to miss.
The two are the same _diagnosis_, input metadata invalidated by rewriting, and not quite the same _fix_.
Dropping a `sourceMappingURL` is unconditionally correct: the map it names was made against the input and every offset moved.
Dropping an `@charset` is correct only if the output is not what the rule claims, and **the library cannot know that**: `serialize` returns a `String` and whoever encodes it decides the bytes, so `@charset "shift_jis"` is true for a caller writing Shift_JIS and a trap for one writing UTF-8.
Enabling it is the caller supplying that assertion, which is exactly what `all()` cannot do on anyone's behalf.
So `all()` is documented as _every optimization that rewrites a value_, both transforms are named-only, and a bare `-O` means what it always meant.

### Chaining an input map is out of scope, and attaches at one line

Written down because it is the first thing anyone asks after seeing the feature, and because being able to point at the seam is what makes deferring it a schedule decision rather than a design risk.

**The problem.**
Sass compiles `app.scss` to `app.css` plus a map.
cassette then minifies `app.css` and emits a map pointing at `app.css`.
A browser follows one hop and lands on the intermediate.
What we want is one flat map from the output to the `.scss`.

What everyone else does is compose.
PostCSS's `map.prev` is _on by default_, looking for an annotation or a `.map` sibling and consuming it.
Terser takes `sourceMap.content`; esbuild, swc and Rollup all compose; `@ampproject/remapping` and `SourceMapGenerator.applySourceMap` are the standalone implementations.
The algorithm is a substitution: for each of our mappings, take the position it resolved to, look _that_ up in the input map for that source, take the greatest mapping at or before it, and emit what it says instead.
`sources` and `sourcesContent` then come from the input map, since the `.scss` may not exist anywhere near the output.

**Where it attaches.**
[The algorithm's post-pass](#algorithm) already asks a resolver where a span came from, and composition is one substitution immediately after:

```
location = sources.tryLocate(span); skip if empty
location = inputMaps.rebase(location)          <- the whole of chaining
```

And because an input map would be keyed by **source id**, this works identically for one file and for a bundle.
`SourceIndex` already hands over exactly that key, which is the same property [`sourcesContent`](#sourcescontent-needs-no-new-enumeration-api) and the [line-index cache](#the-per-source-line-index-is-cached-and-that-is-an-invariant) get for free.
Nothing above this line moves.

What it costs, and why that is the reason to defer rather than to refuse.
Reading a map needs a JSON _parser_ and a VLQ _decoder_; everything designed here is the writer and the encoder.
Zero dependencies means both get written, and a JSON parser is a real component with its own test surface, a poor thing to bundle into the feature that has to first prove the coordinate space holds up.

One property to state now rather than discover.
Composition is lossy in a way nobody can fix: a position falling between two input mappings snaps back to the previous one, so the result is only ever as precise as the input map's granularity.
Every tool listed above has this.
A position no input mapping covers is conventionally dropped, which suits [the rule already here](#a-span-that-resolves-to-no-single-source-is-skipped-silently).

### Streaming the VLQ instead of buffering mappings is unavailable

Worth recording because the buffer looks like the obvious thing to optimize away later.
VLQ segments are delta-encoded against the previous one, so a [rollback](#anything-that-shortens-the-buffer-must-shorten-the-mapping-list) cannot be undone by trimming a length off the encoded string: the deltas after the removed segment are all wrong.
The mapping array is what makes rollback trivially correct, and happens also to be the cheap structure.

### The per-source line index is cached, and that is an invariant

Every mapping needs a line and column _within its own source_, so a line index per source.
[CLI.md names this hazard](CLI.md#the-renderers-interface-is-not-sourceindex-and-cannot-be) for diagnostics, where recounting newlines from offset zero per report is quadratic in the thing `--max-diagnostics` exists to bound.
**Here nothing bounds it.**
Uncached, a Tailwind-scale bundle is on the order of mappings x 3.6 MB of character reads.

And the cache cannot be "whichever source the last mapping used".
Mappings arrive in _output_ order, which for a bundle is cascade order, and [tree order and span order diverge](BUNDLING.md#segments-are-laid-out-in-decode-order-which-is-not-cascade-order), so consecutive mappings hop between files.
It has to be keyed on `sourceId`, and it wants a test rather than a comment.

### `LineIndex` lives in the library, not the CLI

It began in `src/cli`, package-private and 1-based, with a `lineTextOf` the `note:` line uses.
A second consumer inside `src/main` is the [same "two independent consumers, one structural cause"](ARCHITECTURE.md#lexer--input-handling) pattern that moved the charset warning to `decode` and `Diagnostic` to its own package.

It sits in `diagnostics`, beside `SourceResolver`, **0-based**, with the CLI adding one where it prints.
A source map is 0-based and a diagnostic is 1-based, and the `+ 1` belongs at the rendering end rather than a `- 1` at the generating one.

This is a partial reversal of the CLI's "counting lines is a rendering concern, and a bundler that grew one would be doing the CLI's job".
That argument was about `SourceIndex`, and it still holds: `SourceIndex` gains nothing.
What changed is that a _library_ consumer now needs line counting.

### JSON escaping, and the one rule it does not need

`"`, `\`, and U+0000-U+001F, plus U+2028 and U+2029 as insurance for a map inlined into a JavaScript context.
Everything else passes through; the output is UTF-8.

No lone-surrogate rule is needed, because section 3.3 replaces every unpaired surrogate with U+FFFD during preprocessing, so text that reached a `SourceIndex` or a `SourceResolver.of` cannot contain one.
That is a property of the decode contract those two enforce, not an assumption about input.

---

## Surface

```java
package dev.nullkitty.cassette.sourcemap;

public record SourceMap(String file, String sourceRoot, List<String> sources,
                        List<String> sourcesContent, String mappings) {
    public String toJson();
    public void writeJson(Appendable out);
    public static String trailerFor(String url);
    public static boolean isTrailer(CharSequence commentText);
}
```

```java
package dev.nullkitty.cassette.serializer;

public record SerializeResult(String css, SourceMap sourceMap) { }

public final class CssSerializer {
    // existing overloads unchanged
    public static SerializeResult serializeWithMap(Stylesheet stylesheet,
            SerializerOptions options, SourceResolver sources);
    public static SerializeResult serializeWithMap(Stylesheet stylesheet,
            SerializerOptions options, SourceResolver sources,
            Consumer<Diagnostic> diagnostics);
}
```

```java
package dev.nullkitty.cassette.diagnostics;

public interface SourceResolver {
    Location locate(SourceSpan span);
    default Optional<Location> tryLocate(SourceSpan span) { ... }
    static SourceResolver of(String sourceId, CharSequence text) { ... }
}

public final class LineIndex {          // 0-based
    public LineIndex(CharSequence text);
    public int lineOf(int offset);
    public int columnOf(int offset);
    public CharSequence lineTextOf(int offset);
}
```

A distinct entry point rather than a fifth `serialize` overload, and a result record despite [ARCHITECTURE.md arguing against one](ARCHITECTURE.md#public-api-surface).
That argument was that a `SerializeResult` mirroring `ParseResult` would tax every caller with a second return type for diagnostics almost none of them have.
It does not transfer: a caller reaching `serializeWithMap` has asked for a second output.
The `serialize` overloads keep returning `String`.

`sourcemap` is a package of its own, and exported.
The case against was that three types is thin for a package and that the map is produced by writing and by nothing else, which would put it beside `Optimizer` and `Flattener` in `serializer`.
The case that won is that **Source Map v3 is an external format with a future of its own** (`ignoreList`, index maps, ongoing standardization), and a format model plus a VLQ codec plus a JSON writer is a self-contained concern the way `bundle` was.
Two consequences:

* **`SerializeResult` stays in `serializer`** and `SourceMap` does not.
  The split is between what serialization returns and what the format is, which is exactly the seam [chaining](#chaining-an-input-map-is-out-of-scope-and-attaches-at-one-line) would grow a reader against: a JSON parser and a VLQ decoder belong beside their encoders, not beside `CssWriter`.
* **The VLQ codec and the JSON writer stay package-private.**\
  Neither is a general-purpose utility, and exporting either would freeze a shape that only `SourceMap` uses.

---

## Algorithm

```
serialize the tree as today, and at each of the six recording sites:
    if mapping:  append (out.length(), node.packedSpan())

every rollback(mark):
    out.setLength(mark)
    if mapping:  drop every mapping whose offset >= mark

after the last character:
    walk the output once, building line starts          <- merge, not per-mapping search
    for each mapping, in order:
        skip if SourceSpan.lengthOf(packed) == 0        <- synthesized
        location = sources.tryLocate(span); skip if empty
        sourceIndex = first-seen index of location.sourceId()
        lineIndex   = cache.get(sourceId) or new LineIndex(...)
        emit segment (generatedCol, sourceIndex, sourceLine, sourceCol)
```

A segment is four VLQ base64 fields, every one a delta against the previous segment except the generated column, which resets at each output line.
`names` is never written, so there is no fifth field.

---

## Testing

**Byte-identity, which is [the constraint](#the-constraint-everything-else-is-subordinate-to) as a test.**
`serializeWithMap(...).css()` equals `serialize(...)` for the same tree and options, across all four option sets and the optimizer path.
A jqwik property rather than a fixture, because the generators already draw the wreckage the rollback paths exist for.

**Identity verification for the `rollback` refactor, not just green tests.**
The [`NodeStack` standard](PERFORMANCE.md#building-a-child-list-once): the [differential harness](../tools/differential-fuzz/README.md) at roughly 420,000 samples plus all three corpus entries across all four option sets and the optimizer path, byte-identical against a stashed build.
That refactor is the only change touching code that runs with maps off.

**Golden fixtures, decoded rather than raw.**
A VLQ string is an unreadable diff, and the diff [is the review artifact](ARCHITECTURE.md#testing-strategy).
So `expected/<nesting>.<minification>.<compat>.map.txt`, one line per mapping:

```
outLine:outCol -> source:line:col   «the source text at that position»
```

Plus exactly one `.map.json` fixture, to pin the encoder itself.
`SerializerFixtureTest` is the one place mapping a variant name to options, and the existing "a missing file means not asserted, an empty file is a request for output" convention carries over unchanged.

**Properties.**
Generated positions strictly increasing in `(line, column)`; every generated offset inside the output; every source position inside the segment it names; and the sharp one, that the character at each generated offset is the first character the writer emitted for that node.

**A bundle fixture with a nested import**, asserting that the wrapper spanning two sources is skipped rather than throwing, and that everything under it still maps.
None of the existing bundle fixtures had a conditional import of a sheet that itself imports one, which is the only shape that makes a wrapper straddle two sources.

**Allocation as a control.**
With maps off, `SerializeBenchmark` unchanged to the byte, which is the same control span packing and `NodeStack` both used, and the thing that would catch an accidental cost in the shared path.

### The test that cannot exist

The obvious test for the rollback discipline is: an at-rule whose prelude is nothing but wreckage must leave no mapping past the end of the output, and disabling the mapping half of `rollback` must make it fail.
**That test cannot exist.**
The at-rule's mapping is recorded at the `@`, which is before the mark the prelude rolls back to, so it survives correctly whether or not `rollback` truncates the mapping list, and [no other rollback region contains a recording site either](#the-rollback-discipline-drops-nothing-today-and-that-is-measured).
Disabling the mapping half changes no output any input can produce.

Which is the hazard `CssParserTest.AbandonedBuilds` names, one level up: _a test written against a hazard whose symptom is silent can very easily assert something that was never going to break._
What replaces it is `MappingsTest.Truncation`, which tests `truncateFrom` directly rather than through a writer path that cannot reach it, and the mutation check moves to the recording sites, where removing one fails six assertions.

### What it costs

A map costs roughly **2.3x** what serializing allocates, and `sourcesContent` is the dominant term.
Three write-ups in [PERFORMANCE.md](PERFORMANCE.md#source-maps): [what a map costs](PERFORMANCE.md#what-a-map-costs), against an estimate that was low by three to four times; [the 64 bytes per mapping that locating allocated](PERFORMANCE.md#locating-allocated-64-bytes-per-mapping-and-now-allocates-none) and no longer does; and [`toJson`](PERFORMANCE.md#tojson-and-the-two-things-measuring-it-found), whose buffer estimate was short on every corpus entry and which `writeJson(Appendable)` then removed entirely.

---

## What the CLI adds

One flag group, on `format` and `minify`:

```
Source maps                                        format, minify
      --source-map[=file|inline|none]   default none; bare --source-map means file
      --source-map-url <url>            what the trailer names; default <output>.map
      --no-source-map-content           omit sourcesContent
```

`--source-map` is value-optional, so per [the CLI's rule](CLI.md#a-value-optional-flag-takes-its-value-only-attached) it takes its value **only with `=`**, and the token after a bare `--source-map` is always an input.

|                                 |                                                                       |
|---------------------------------|-----------------------------------------------------------------------|
| `--source-map=file` to stdout   | usage error, nowhere to put the sidecar                               |
| `--source-map=inline` to stdout | legal, a base64 data URI in the trailer                               |
| `-o`, `--out-dir`, `--in-place` | `x.css.map` beside each output                                        |
| `--bundle`                      | one map for the bundle, which is what `SourceIndex` already describes |
| `check --source-map`            | accepted, does nothing, exactly as `check -O` already is              |

`sources` entries are relativized against the map file's directory.
Standard input has no path, so it is `<stdin>` and leans on `sourcesContent`, which is the one case where omitting content produces a map nothing can resolve, and is worth a warning.

**The `<stdin>` warning is not a `Diagnostic`**, which is the more interesting of the two things building this settled.
Every `Diagnostic` carries a span, and this warning has no location: it is about the flags, not about anything in the stylesheet.
Given `SourceSpan.NONE` it renders as `<stdin>:1:1` with the first line of input quoted underneath, pointing at something innocent and making every honest location beside it less believable.
So it goes out as a plain `cassette: warning:` line, once per invocation rather than once per map, read off the flags because that is all it depends on.
`--quiet` silences it; `--strict` does not fail on it, because nothing about the input is wrong and the map that was asked for was produced.

And relativizing needs both sides canonicalized, which is a real defect a bundle test found rather than a design point.
[`CLI.md` owns the write-up](CLI.md#both-sides-of-a-relativize-have-to-agree).
A bundled source id has been through `toRealPath` and an output path has not, so on any system where a directory has two spellings the two disagree and `relativize` climbs to the root, giving a `sources` entry that resolves correctly and is unreadable.
