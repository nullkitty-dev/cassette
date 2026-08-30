# c(a)ssette - Bundling & import resolution

_Design record for the coordinate space and `@import` resolution._
_Companion to [ARCHITECTURE.md](ARCHITECTURE.md), which owns the library, [SOURCEMAPS.md](SOURCEMAPS.md), which consumes the coordinate space, and [PERFORMANCE.md](PERFORMANCE.md), which owns what bundling costs._

Two things share one mechanism here: concatenating several sources into one tree, and resolving `@import` through a caller-supplied importer and inlining the result.
Both are tree-level operations handing back an ordinary `Stylesheet`, so `Flattener`, `Optimizer` and `CssSerializer` work on the result unchanged.

This is the substrate for a cross-file optimizer, not the optimizer itself.
Nothing here removes a rule, merges a media query or dedupes a declaration.

---

## Goal & non-goals

**What it is.**
An ordered list of sources becomes one `Stylesheet` in cascade order.
Each `@import` the caller's importer can resolve is replaced by the imported sheet's contents, wrapped in whatever group rules the import's prelude implied.
Every node in the result is traceable to the file and offset it came from.

**What it is not.**
Not a module bundler: no tree-shaking, no code splitting, no dependency graph output, no asset rewriting.
`url()` contents are not rewritten when a file moves, because that needs a base-URL model and a notion of what a relative reference means, both of which the importer owns.
Not a resolver: cassette never touches a filesystem, a classpath or a network.
It hands a specifier to an `Importer` and takes bytes back.

---

## Decisions

### Provenance is a virtual coordinate space, not a field on `SourceSpan`

`SourceSpan` stays `(int start, int length)`.
The bundler assigns every source a base offset in one logical coordinate space and threads that base through decoding, so each span is born global.
A `SourceIndex` maps an offset back to `(sourceId, local offset)` by binary search over the segment table.

A third component on `SourceSpan` would be self-describing but costs a breaking change to every AST record's canonical constructor, every test that builds one, and every `ast.txt` golden, in exchange for information a 32-entry array already holds.
It also costs 4-8 bytes on the single largest line item in the retained tree, which [AST memory shape](PERFORMANCE.md#ast-memory-shape) is trying to shrink.

Because bundling needs no new component, the packing change was free to happen: every record stores a `long packedSpan` and `start` stayed 32-bit, since a bundle's coordinate space is the sum of its sources' decoded lengths and real bundles are megabytes, not gigabytes.
The bundler lays out global offsets and calls `SourceSpan.pack(base + start, length)`.

### Spans become global at decode time, not by rewriting

`SourceText.decode` takes a base offset.
The tokenizer adds it once when constructing a span, so nothing is ever rewritten and bundling allocates exactly one tree per source.

Parsing every source at base 0 and rebasing afterwards would rebuild every record in every tree, roughly 38,000 records for Bootstrap alone.
The price of the chosen mechanism is that bundling is sequential: a source's base is not known until everything before it has been laid out, so top-level sources cannot be parsed in parallel.
That is the right trade for a library whose largest realistic input parses in tens of milliseconds.

Threading the base through `SourceText.decode` alone is not enough.
A bundler does not decode and parse in one call.
It decodes first, to hold the text its spans index into, exactly as [the CLI does](CLI.md#the-cli-cannot-see-the-text-the-spans-index-into).
So we let the base reach the public surface too, as `CssParser.parse(CharSequence, int)` and `CssParser.decode(byte[], Charset, int, Consumer)`, a plain `int` in each, leaking no type and meaningful to any embedder laying out sources of their own.

**The base is added where a span is built, and nowhere else.**
`TokenBuffer.start` and `end` stay local, because interning, delimiter comparison and the token adjacency the selector grammar leans on all index the decoded `char[]` with them.
A based offset there reads the wrong characters or runs off the end.

There are two spans no token produces, and we had to find both.
`SourceText.unresolvedCharsetSpan` is the obvious one.
The `Stylesheet`'s own span is the other: it covers the whole input whether or not a single token was scanned, so it cannot be expressed as a token range.
Left unbased, every source's stylesheet node would report as starting at offset zero, and the charset warning would point into whichever file sat at offset 0 of the bundle with a message naming an encoding, and nothing about it would have looked wrong.
`TokenBuffer.packedSpanOfSource` is where the second lives, next to `packedSpan`, so the two places a base is added sit together.

### Segments are laid out in decode order, which is not cascade order

Offsets are allocated from a monotonic cursor as sources are decoded, and imports are resolved depth-first.
So a file's imported children occupy the space immediately after it, and the next top-level source starts after those.

**In a bundle, tree order and span order diverge**, which is a property rather than a defect.
Tree order is cascade order, which is what the CSS means.
Span order is decode order, which is where the text came from.
`bundle.txt` prints both, so a reader is never guessing which one they are looking at.

A synthesized wrapper node carries the span of the content it wraps, not of the `@import` that caused it.
The union of the two would produce a node overlapping its own siblings and break the parent-contains-children invariant the fuzz suite asserts.
The causal link is not lost, because each segment records the `Origin` it was imported from.

### The importer returns bytes

```java
@FunctionalInterface
public interface Importer {
    Optional<Source> resolve(String specifier, Origin from);
}
```

cassette owns decoding, charset and BOM detection, parsing, recursion and cycle detection.
The importer owns everything cassette cannot know: what a specifier means, whether it is relative to the importing file, and whether it should be fetched at all.
That keeps a filesystem importer to about six lines and keeps every span in a coordinate space cassette laid out itself.

The `id` on the returned `Source` is opaque to cassette.
It is compared for cycle detection and printed in diagnostics and banners, and is otherwise never interpreted: a filesystem importer returns a canonical absolute path, a classpath importer a resource name.
**Canonicalization is the importer's job**, so two ids differing by a `../` are two different files as far as cassette is concerned, and a graph that would have been a cycle becomes an infinite regress cut by the depth bound.

`Source` is the same record the caller passes in for concatenation, rather than a separate `ImportedSource` type.
One record, two directions.

The `Optional` costs nothing at a real call site.
A wrapper at a monomorphic boundary is free until the site sees both a present and an empty answer, and then it is 16 bytes, so the worst a declined `@import` can cost a bundle is 16 B.
The measurement is in [the benchmark notes](../benchmarks/README.md#optional-at-a-returning-boundary).

### Encoding is per source, and an import inherits its parent's

Every source is decoded on its own.
A UTF-8 index importing a Shift_JIS partial is legal and works.
There is no single bundle encoding, only a single bundle _coordinate space_, and that space is decoded characters rather than bytes.
Each source's BOM is stripped by its own decode and never reaches the coordinate space, so a segment's length is its length in **decoded characters, after BOM-stripping and after section 3.3 preprocessing**, not its byte count.
Getting that wrong misaligns every segment after it.

An `@import`ed sheet that determines nothing for itself inherits the importing sheet's encoding.
That is CSS Syntax section 3.2: BOM, then `@charset`, then the _environment encoding_, then UTF-8, and for a sheet reached through `@import` the environment encoding is the encoding of the sheet that imported it.

This needs no lexer change, because `SourceText.decode`'s `protocolEncoding` parameter already _is_ the environment-encoding slot and `determineEncoding` already implements the chain in that order.
The bundler passes the importing sheet's resolved encoding down as the child's `protocolEncoding`.
Where the importer supplied one of its own, **the importer wins**, since an importer that sets it knows something cassette does not, such as an HTTP `Content-Type` parameter.
So `Source`'s field is meaningfully nullable and an importer should leave it null rather than defaulting it.

Answering section 3.2's chain for the parent before the child is decoded is what `CssParser.detectEncoding` exists for.
`decode` returns text rather than an encoding, so there was otherwise no way to ask.

Following the rule rather than always falling back to UTF-8 is not a close call, even though UTF-8 is over 95% of real input.
Every difference from spec this project has accepted is _semantic validation_, a stated non-goal; a charset rule decides what characters the tokenizer sees, and "we implement CSS Syntax literally" is what makes WPT usable as an oracle.
Same reasoning as the one that [kept the full label table](ARCHITECTURE.md#lexer--input-handling).

A source declaring an `@charset` nothing can resolve still warns, and the warning names that source.
The warning is emitted at the decode step rather than by `CssParser.parse`, because a caller that decodes and parses as two steps would otherwise drop it.
The bundler is such a caller, and [so is the CLI](CLI.md#decode-needs-the-diagnostic-sink-or-the-cli-loses-the-charset-warning): two independent consumers losing the same diagnostic for the same structural reason is the argument for fixing it once, where it happens.

### `@import` conditions become the group rules they imply

An `@import` prelude is `<url> [layer | layer(<name>)]? [supports(<condition>)]? <media-query-list>?`, and every part has an exact group-rule equivalent.
Wrapping re-emits the prelude's token list.
It never evaluates a media query or a supports condition.

| prelude                             | wrapping, outermost first                                   |
|-------------------------------------|-------------------------------------------------------------|
| `url(b.css)`                        | none, contents spliced in directly                          |
| `url(b.css) screen`                 | `@media screen`                                             |
| `url(b.css) supports(display:grid)` | `@supports (display:grid)`                                  |
| `url(b.css) layer(base)`            | `@layer base`                                               |
| `url(b.css) layer`                  | `@layer` (anonymous)                                        |
| all four                            | `@layer base { @supports (...) { @media screen { ... } } }` |

Layer outermost, then supports, then media.
Layer assignment applies to the contents regardless of whether the conditions match, and the conditions apply within the layer.

One shape needs care.
`supports()` is a function whose argument is a condition _or_ a bare declaration, while `@supports` takes a bare condition.
So `supports(display: grid)` becomes `@supports (display: grid)`, with the parentheses re-added, and `supports((a) or (b))` becomes `@supports (a) or (b)`, with them not.
Getting that wrong produces a condition that is always false, which no test asserting tree shape would catch, so it gets its own fixture.
**A top-level colon is what tells a bare declaration from a condition**, since a declaration inside a condition is always already parenthesized.

Nested wrappers are left nested.
`@media` inside `@media` is not merged, for the reason [flattening](ARCHITECTURE.md#flattening) gives: merging conditions means evaluating them.

A synthesized wrapper is the one node assembled from two files, and two invariants bend around it.
Both were found by the fuzz property rather than reasoned out in advance, and neither is avoidable:

* _Its prelude lies outside its own span._
  The prelude is re-emitted from the **importing** sheet and keeps those tokens' spans, because that is where an author wrote the media query and where a diagnostic about it should point.
  So parent-contains-children holds for a wrapper's body and not for its prelude.
* _Its span may cover several sources._
  An imported sheet that imported others occupies its whole subtree, so `SourceIndex.resolve` refuses that span rather than attributing it to one file.
  Leaves resolve, and `Segment.importedFrom` is the question a wrapper does have an answer to.

### Cycles break; duplicates inline every time

A cycle is detected against the active import stack, keyed on resolved id.
The offending `@import` is dropped and an `ERROR` names the whole chain, `a.css -> b.css -> a.css`, because the chain is the only part of that message a reader can act on.

A repeated non-cyclic import is inlined at every site.
In CSS it genuinely applies twice and the later copy can win the cascade, so collapsing it changes what a stylesheet means, which makes it an optimizer's call and not a bundler's.

**Import depth is bounded at 64**, and, like the parser's 512-level recursion bound, exceeding it is reported once rather than once per level.

### An unresolved import stays in the output

If the importer returns empty, the `@import` rule survives verbatim and a `WARNING` names it.
Whatever consumes the CSS can then resolve it at runtime exactly as it would have without a bundler.
That is what makes selective inlining a one-line importer rather than a wrapper: inline my local partials, leave the web font URL alone.

### The prologue is normalized

Concatenation strands `@charset` and `@import` in positions CSS calls invalid, since an `@import` may only be preceded by `@charset` and `@layer` statements.

**Every `@charset` is dropped, silently.**
By the time a tree exists the text is already decoded, so a surviving `@charset` has nothing left to describe: a mid-stream one is meaningless and a leading one is stale.
One that was honored has already done its work at decode time, and one that was not is reported by the decode.

The reason is _not_ that a bundle has one encoding.
[Sources are decoded independently](#encoding-is-per-source-and-an-import-inherits-its-parents) and may genuinely differ.
An implementer following that reasoning would decode the whole bundle with one charset, which is a real bug.

This is a bundler decision, not a parser one.
`CssParser.parse` still keeps `@charset` as an ordinary statement at-rule, a difference from the CSSOM the library takes on purpose because dropping it is semantic validation.
The faithful path stays available; the bundler is the layer that has a reason to act.

**Surviving `@import`s are hoisted to the top of the bundle**, in first-seen order, with a `WARNING` per hoist naming how many rules it moved past.
**The warning fires only when the import actually moved**, since the first source's leading imports are already where they belong, and a warning on the correct case teaches people to ignore it.

Hoisting out of a wrapper is the subtle case.
An unresolved `@import` inside a sheet that was itself inlined under `@media print` cannot simply move to the top, because it would lose the condition, and dropping a condition quietly is what the rest of this design refuses to do.
The `@import` prelude grammar is isomorphic to the wrapping, so the conditions re-attach to the prelude on the way out: hoisting `@import url(x)` out of `@media print` yields `@import url(x) print`, and out of `@layer a` yields `@import url(x) layer(a)`.
`ImportPrelude.reattached` is the mechanical case.

Where re-attachment is not mechanical the rule is left where it is and the `WARNING` says the output is invalid there.
`Bundler.strand` is that fallback, covering two shapes:

* **Two conditions of one kind.**\
  Two media queries need `and` between them, and a comma in either needs distributing over the other; two named layers need joining into a dotted path.
  Both are expressible and neither is modelled, because a media query is opaque component values here by [stated non-goal](ARCHITECTURE.md#goal--non-goals) and combining them would start interpreting one.
  **The stranded rule's own prelude counts toward this budget:** one wrapper plus a media query on the import itself is two conditions, not one.
* **An anonymous layer, which is impossibility rather than conservatism.**\
  `layer` in an `@import` prelude creates a _new_ anonymous layer, and anonymous layers are distinct from one another and cannot be named.
  A hoisted import would land in a _different_ layer from the rules it left, a change in cascade order with nothing in the output to show it.
  No amount of modeling fixes this one.

**Slots are written in grammar order, not nesting order.**
`@media print { @layer a { ... } }` is reachable through a chain of imports, and its prelude is `layer(a) print`, so re-attachment collects by kind and emits in the grammar's order rather than the order it met the wrappers.
Mutation-checked, because emitting in encounter order is right for the common case and wrong here.

### Banners are opt-in

`BundleOptions.banners(true)` inserts an ordinary `Comment` node at each segment boundary naming the source id.
Because it is a real AST comment, `Formatting.MINIFIED` strips it for free and passthrough serialization keeps it, so there is no serializer change and no new axis in the fixture naming convention.

Source ids containing `*/` are escaped when written into a banner.
An id is caller-supplied text, and a banner that terminates its own comment turns the rest of the stylesheet into garbage.

---

## Surface

Exported package `dev.nullkitty.cassette.bundle`, a peer alongside `lexer`/`ast`/`parser`/`serializer`.

```java
package dev.nullkitty.cassette.bundle;

public record Source(String id, byte[] content, Charset protocolEncoding) { }

public record Origin(String sourceId, int offset) { }

@FunctionalInterface
public interface Importer {
    Optional<Source> resolve(String specifier, Origin from);
}

public record BundleOptions(Importer importer, boolean banners, int maxImportDepth) {
    public static Builder builder();          // mirrors SerializerOptions
    public static final BundleOptions DEFAULTS;  // no importer, no banners, depth 64
}

public final class SourceIndex implements SourceResolver {
    public static Builder builder();
    public Origin resolve(int offset);
    public Origin resolve(SourceSpan span);
    public CharSequence textOf(SourceSpan span);
    public List<Segment> segments();
    public int length();                       // the whole space, and the next base

    public Location locate(SourceSpan span);   // SourceResolver

    public record Segment(String sourceId, int base, int length, Origin importedFrom) { }

    public static final class Builder {
        public int nextBase();                                 // read before decoding
        public Builder add(String sourceId, CharSequence text);
        public Builder add(String sourceId, CharSequence text, Origin importedFrom);
        public SourceIndex build();
    }
}

public record BundleResult(Stylesheet ast, List<Diagnostic> diagnostics, SourceIndex sourceIndex) {
    public boolean hasErrors();
    public List<Diagnostic> diagnostics(Severity severity);
}

public final class Bundler {
    public static BundleResult bundle(List<Source> sources);
    public static BundleResult bundle(List<Source> sources, BundleOptions options);
    public static BundleResult bundle(Source source, BundleOptions options);
}
```

Static and stateless, like `CssParser` and `CssSerializer`.
With no importer configured, every `@import` is unresolved and left in place, so `Bundler.bundle(sources)` is pure concatenation and needs no SPI at all.

`BundleResult` duplicates `hasErrors()` and `diagnostics(Severity)` rather than sharing a supertype with `ParseResult`: two records cannot share state, and an interface holding two derived methods would be ceremony over four lines.

`Diagnostic` and `Severity` come from `dev.nullkitty.cassette.diagnostics`, not from `parser`.
`bundle` holding `parser.Diagnostic` was one of the shapes that made the old placement read wrong.

`SourceResolver` is defined by [the CLI's needs](CLI.md#the-renderers-interface-is-not-sourceindex-and-cannot-be), and `SourceIndex` implements it.
One method returning `(sourceId, that source's text, offset within it)`, all three of which `SourceIndex` already computes for `Origin` and `textOf`.
What it does _not_ add is line and column: counting lines is a rendering concern, and a bundler that grew one would be doing the CLI's job.

**`SourceIndex.Builder` hands out the base rather than taking one**, which makes segments contiguous by construction, so "a span resolves to exactly one segment" is a property of the type and not something a caller can get wrong by arithmetic.
It rejects text holding a carriage return, because CRLF collapsing is the one preprocessing rule that changes a _length_, so raw text there makes every later base too large and every later span resolve into the wrong file, in range and undetectable.
**An empty source gets no offset of its own:** a zero-length segment shares its base with whatever follows, and the follower wins.

### The trap this creates

`SourceSpan.text(CharSequence)` slices the buffer a span was created against.
In a bundle there is no single buffer, and calling it with any one source's `SourceText` returns the wrong characters, silently, since the offsets are in range.
`SourceIndex.textOf(span)` is the bundle-aware form, and `SourceSpan.text`'s javadoc says it is valid only for a tree from `CssParser.parse`.
This is the sharpest edge in the design, so we restate it wherever it can be reached.

---

## Algorithm

```text
cursor = 0
for each source in the input list, in order:
    segment = decode(source, base = cursor); cursor += segment.length
    tree    = parse(segment)
    resolve imports in tree, depth-first          <- recurses through this same step
    append tree's children to the bundle

drop every @charset
hoist surviving @imports to the front, re-attaching enclosing conditions
insert banner comments if enabled
```

Resolving one `@import`, in order:

1. Extract the specifier from the prelude, a `url()` function or a string.
   Neither present, or more than one: `ERROR`, rule dropped, it was never a valid `@import`.
2. Ask the importer.
   Empty -> leave the rule, `WARNING`, done.
3. Resolved id already on the active stack -> `ERROR` naming the chain, rule dropped, done.
4. Depth would exceed the bound -> `ERROR` once per bundle, rule dropped, done.
5. Decode at the current cursor, parse, recurse into its imports.
6. Wrap the contents per the prelude table, replace the `@import` node with the wrapper.

Diagnostics from every parse are merged into one list.
They carry global spans already, so `SourceIndex.resolve` renders any of them against the file it came from.

---

## Testing

**Bundle fixtures.**
A fixture directory holding `sources/` instead of `input.css` is a bundle fixture.
The loader builds a map-backed importer over `sources/` automatically, so specifiers resolve the way a filesystem importer would with nothing stubbed.
A source's id is its path under `sources/`, so a nested `base/buttons.css` is reachable by the name an `@import` writes.

```text
fixtures/bundle-import-media/
  sources/
    index.css
    base/buttons.css
  entry.txt                     entry sources, one per line, in cascade order
  expected/
    bundle.txt                  tree + diagnostics + segment table
    nested.unminified.modern.css
```

`bundle.txt` is `ast.txt` plus a segment table, and prints each node's origin next to its span, because [span order and tree order diverge](#segments-are-laid-out-in-decode-order-which-is-not-cascade-order) and a dump showing only spans would read as though the tree were out of order.

`TokenizerFixtureTest` and `ParserFixtureTest` skip bundle fixtures.
`SerializerFixtureTest` assembles instead of parsing and is otherwise unchanged.

**Cases that need a fixture rather than a unit test**, because the interesting part is the whole output: the four prelude wrappings and their combination; `supports()` with a bare declaration versus a compound condition; a cycle; a diamond, where one file is reached twice and must appear twice; an unresolved import surviving; an import hoisted out of a `@media` wrapper with its condition re-attached; `@charset` in a non-first source.
`bundle-hoist-reattached` carries two wrappers that re-attach and one that cannot, in one file.

**Encoding cases**, which the fixture harness has to be able to express, so `sources/` files are read as bytes and at least one fixture's sources are not all UTF-8:

* A non-UTF-8 source among UTF-8 ones, decoding correctly and landing at the right offsets.
  The point is the _segment table_: a multi-byte encoding makes byte length and character length differ, so a base computed from the wrong one puts every later span in the wrong file.
* An imported sheet with no BOM and no `@charset`, imported by a non-UTF-8 parent, which must [inherit the parent's encoding](#encoding-is-per-source-and-an-import-inherits-its-parents).
  Shift_JIS is the right choice for the parent, for the reason `decodesLegacyCjkRatherThanCorruptingIt` documents: a wrong decode produces a real backslash and eats the rest of a rule, so the failure is loud.
* A source with a BOM in a non-first position, asserting the BOM is stripped and the following segment's base is not off by one.
* A source declaring an unresolvable `@charset`, asserting the warning names _that_ source.

**Properties**, extending the jqwik suite:

* The span-fits property generalizes from "inside the input" to "inside the bundle's coordinate space", and gains a companion: every span resolves to exactly one segment, leaves excepted as [above](#import-conditions-become-the-group-rules-they-imply).
* Parent-contains-children must still hold, which is the assertion that catches a synthesized wrapper given the wrong span.
* Idempotence extends to bundles.
  Prologue hoisting means a bundle's output is not the concatenation of its inputs' outputs, so idempotence is the only round-trip property available, on the same reasoning as [round-tripping](ARCHITECTURE.md#round-tripping).
* Bundling terminates on any import graph a generator can build, cycles included.

### Allocation

Bundling allocates the sum of its sources' parses and nothing more: no rebase, no intermediate tree.
`BundleBenchmark` holds that claim.
The figures, how the gap was attributed, and the three bytes per source character the entry point was costing are in [PERFORMANCE.md](PERFORMANCE.md#bundling-allocates-the-sum-of-its-sources-parses-and-nothing-more).

One result there is worth knowing here, because it is a property of the JVM rather than of the design: [a bundle of ordinary-sized partials is roughly twice as fast as the same bytes concatenated](PERFORMANCE.md#bundling-is-not-faster-than-parsing-the-single-file-path-is-penalized), because one multi-megabyte `TokenBuffer` crosses G1's humongous-object threshold and seven smaller ones do not.

---

## What this enables

A cross-file optimizer is what the whole design is aimed at, and what it needs is the thing it cannot work without: one tree holding every rule that will end up in the output, with each node still traceable to the file that wrote it.
A transform that merges two identical rules can then say which two files it merged, and a transform that drops a declaration can say whose it was.

The transforms themselves (duplicate rule collapsing, adjacent `@media` merging, cross-file dedupe of the repeated imports this design keeps) belong in `Optimizations` alongside the existing set, opt-in and independent, for the reason that list is already opt-in: each one changes what a stylesheet means in some edge case, and the caller is the one who knows whether that edge case is theirs.
