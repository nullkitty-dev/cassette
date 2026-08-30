# c(a)ssette - Architecture

_Design record for the library: what we decided, and why._
_Companion to [PERFORMANCE.md](PERFORMANCE.md), which owns every figure quoted here, plus [BUNDLING.md](BUNDLING.md), [SOURCEMAPS.md](SOURCEMAPS.md) and [CLI.md](CLI.md)._

Every decision below carries its cost, because every one of them cost something.
Where we measured that cost rather than argued it, the figure links into `PERFORMANCE.md`.

---

## Goal & Non-Goals

### What cassette Is

A real-world CSS 3.x (including CSS Nesting Module Level 1) parser, with spec-defined error recovery rather than hard failure.
Outputs optimized or minified CSS, nested as written or flattened.
Embeddable inside JVM tooling: parse, transform, serialize.

### What cassette Is Not

* **Not a CSSOM or live-DOM API:**\
  No cascade resolution, no computed values, no element tree.

* **Not a value-semantics validator:**\
  `url()` contents, `calc()` expressions and custom-property values are captured per grammar, never evaluated.

* **Not chasing the Vector API:**\
  It's still incubating, and a hand-written recursive-descent parser is bound by allocation discipline, not instruction-level parallelism.

---

## Project Setup

Gradle, single module, with packages for `lexer` / `ast` / `parser` / `serializer` and the rest, plus `src/jmh` and `src/cli` source sets.
The lexer/parser boundary is enforced by visibility and review rather than by the build graph.
A four or five subproject split would add cross-module version coordination for no real isolation at this scale.

Zero runtime dependencies is a policy, not a preference.
The test and benchmark allowlist is JUnit 5, jqwik, AssertJ and JMH.
Anything proposed for `api` or `implementation` is a review decision.

Named `cassette`, package `dev.nullkitty.cassette`, stylized c(a)ssette.

License Apache 2.0 ([LICENSE](../LICENSE)), compatible with the MIT (Bootstrap) and BSD-3-Clause (WPT) content in fixtures and the benchmark corpus.
It adds a patent grant, which matters for infrastructure embedded in commercial JVM tooling.

---

## CSS Dialect Scope

Nesting is CSS Nesting Module Level 1: explicit `&`, relaxed nesting where the spec allows it, and conditional group rules (`@media`, `@supports`, `@container`, `@layer`) containing nested style rules.

`var()`, `calc()` and custom properties are opaque, stored as raw `ComponentValue` token lists, because a custom property's value is arbitrary token soup without computed-value context.
A constant-folding pass over `calc()` could be layered on later without an AST redesign, since function arguments are already nested token lists.

---

## Lexer & Input Handling

The primary entry point takes raw bytes.

```java
ParseResult parse(byte[] source)
ParseResult parse(byte[] source, Charset protocolEncoding)
ParseResult parse(InputStream in)

// Convenience helper: skips charset detection, assumes already-decoded text
ParseResult parse(CharSequence source)
```

The CSS Syntax charset-detection algorithm (BOM sniff -> `@charset` sniff -> protocol encoding -> UTF-8) only makes sense on bytes.
Decoding to `String` first throws away what it needs.

Decoding happens fully upfront into one `char[]`, with no streaming support, and token spans are `(int start, int length)` pairs into that array.
Full buffering costs roughly 3x source size at peak.
Streaming would bound memory only against many small tokens spread across a huge file, never against one huge token, since a single long `url()` forces the window to grow to fit it.
It costs logical-offset bookkeeping, decoder underflow state across chunks, and eviction deferred until every span into a region is materialized, all for a multi-GB-CSS threat model that does not match the use case.

The full Encoding Standard label table stays, for error detection.
We rejected restricting cassette to UTF-8, the UTF-16 pair and Latin-1:

* **It buys no simplicity:**\
  The table is 39 lines of label registration and the decoders are the JDK's.
  cassette calls `Charset.forName`; it does not implement Shift_JIS.

* **The obvious subset is wrong:**\
  The Encoding Standard maps `iso-8859-1`, `latin1`, `us-ascii` and `ascii` to windows-1252, which is not one of GraalVM Native Image's default charsets either, so a "UTF-8 and Latin-1 only" build still needs the charset flag.

* **A wrong encoding corrupts rather than garbles:**\
  Legacy CJK encodings put `0x5C` in the trailing byte of common characters: U+8868 is `95 5C` in Shift_JIS, as are U+5341, U+30BD and U+4E88.
  Decoded as UTF-8 the lead byte becomes U+FFFD and the `0x5C` survives as a real backslash, which starts a CSS escape, eats the string's closing quote and runs past the rule's closing brace.
  `CssParserTest.decodesLegacyCjkRatherThanCorruptingIt` pins both halves.

* **It would be the first deviation in a parsing rule:**\
  The two differences from spec we accept are both semantic validation, a stated non-goal.
  A charset carve-out changes what characters the tokenizer sees, and "we implement CSS Syntax literally" is what makes WPT usable as an oracle.

What was wrong was the silence.
`forLabel` returned `null` for both "no such encoding" and "this runtime lacks it", `SourceText` fell back to UTF-8, and nothing recorded it.
`CssParser` now emits a `WARNING` naming the label and what was decoded instead, and `CssEncoding.catalogues` separates the two cases: a label cassette knows and the build cannot supply means the build is incomplete.
That is also the native-image failure mode, reported rather than silent.

Only the label tables are eager.
UTF-8 and the UTF-16 pair come from `StandardCharsets`; every other label maps to a charset name and resolves through `Charset.forName` on first use, cached after.
That keeps some 30 lookups out of class initialization, which matters for a CLI whose whole native-image argument is startup, and out of a native image's build-time heap.

The charset warning belongs to `decode`, not to `parse`.
`reportCharsetFallback` ran inside `CssParser.parse(SourceText)`, so a diagnostic about decoding was produced by parsing, and anything separating the two dropped it.
Both consumers do separate them:

* The [CLI](CLI.md#decode-needs-the-diagnostic-sink-or-the-cli-loses-the-charset-warning) decodes once to a `String` so its diagnostics and the parser's index into one buffer it holds.
* The [bundler](BUNDLING.md#encoding-is-per-source-and-an-import-inherits-its-parents) decodes each source at its own base offset and parses separately.

So `CssParser.decode` takes a `Consumer<Diagnostic>`, the pattern [`CssSerializer.serialize` established](#public-api-surface), and reports the fallback itself.
`parse(byte[])` routes through the same helper, so the two paths cannot disagree.
`CssParserTest.Decoding` pins it: the byte entry point reports it, the text entry point cannot.

Two spans exist that no token produces.
`unresolvedCharsetSpan()` is the obvious one; the `Stylesheet`'s own span is the other, since a stylesheet covers its input whether or not a token was scanned and so cannot be expressed as a token range.
Both are built outside the tokenizer, which is why a plan to make spans global inside it would reach neither.
`TokenBuffer.packedSpanOfSource` is the second one's home.
See [BUNDLING.md](BUNDLING.md#spans-become-global-at-decode-time-not-by-rewriting).

### Lexer/Parser Handoff

The parser reads a `TokenBuffer`, not the `Tokenizer`.
`Tokenizer` is a forward-only cursor whose fields describe one token at a time, the right shape for scanning and the wrong one for parsing, since CSS Syntax's algorithms reconsume the current token and the selector grammar backtracks across a whole prelude.
`TokenBuffer` tokenizes once into parallel arrays and hands out an index.

Structure-of-arrays rather than a `Token[]`: no per-token object header, and no `String` materialized until someone asks.
A record per token would have been the single largest allocation a parse makes.

A `limit` on the cursor is what makes sub-range parsing free.
`TokenCursor`, shared by `Parser` and `SelectorParser`, reads end-of-input past its limit.
That is how a selector list inside `:is(...)` gets parsed by a fresh parser over the argument's token range with no copying, and how the selector parser stays inside a rule's prelude.

Escape resolution lives in a shared `Escapes` helper, because both `Tokenizer` and `TokenBuffer` decode a token's value, the second long after the cursor has moved past.

---

## AST Design

Every node carries a source span, and comments are preserved.

```java
record Declaration(String property,
                   List<ComponentValue> value,
                   boolean important,
                   long packedSpan) implements Node
```

A declaration value is a sequence, since `1px solid red` is three values and two separators, so a single `ComponentValue` could never hold one.
`Comment` is a `ComponentValue` because comments appear inside values too.

The span component is a `long` rather than a `SourceSpan`, unpacked on demand by `Node.span()`.
Every record still takes a `SourceSpan` through a convenience constructor, so that stays how you build one, just not how it is stored.
See [AST memory shape](PERFORMANCE.md#ast-memory-shape).

Spans carry spec-faithful recovery and [source maps](SOURCEMAPS.md).
Comments are real AST nodes rather than discarded at the tokenizer, which is what lets the passthrough serializer keep them: cheap now, expensive to retrofit.

Selectors get the full structural Selectors Level 4 grammar rather than an opaque prelude token span, because flattening `&` correctly means finding it wherever it sits and wrapping the right parent sub-list in `:is()`.
This is a second grammar alongside CSS Syntax Level 3's, over the tokens rather than over component values; see [Selector grammar](#selector-grammar).

At-rules are generic, except conditional group rules:

```java
record AtRule(String name, List<ComponentValue> prelude,
              List<ComponentValue> block, long packedSpan)
  implements Rule // @font-face, @import, @keyframes, @page, ...

record ConditionalGroupRule(String name, List<ComponentValue> prelude,
                            List<Node> body, long packedSpan)
  implements Rule // @media, @supports, @container, @layer
```

`AtRule.block` is component values, not nodes, because an opaque at-rule's block is opaque, and `null` there means a statement at-rule ended by `;`, which differs from an empty block.
`ConditionalGroupRule.body` is `List<Node>` rather than `List<Rule>` because comments are real nodes and because a group rule nested inside a style rule may hold bare declarations.

Most at-rules stay opaque, matching CSS Syntax's own grammar.
But `@media`, `@supports`, `@container` and `@layer` can contain nested style rules per the Nesting spec, and flattening has to recurse into that structurally, so the distinction is encoded in the AST rather than discovered by duck-typing block contents.

Numbers keep their raw text next to a parsed `double`, because a `double` cannot tell `.500`, `+5` and `1e2` apart and all three have to round-trip byte-for-byte.
The raw text is authoritative and the number is a derived view, which avoids committing to `double` versus `BigDecimal`.

Escapes need only the one form, so `\2603` and `caf\e9` reach the tree decoded as `café`, with the raw spelling still recoverable from the span.
The serializer re-escapes canonically and nothing ever reads the author's escape style, so a second `String` per identifier would be pure allocation.

`Specificity` is public API: a small, pure function already needed internally for `:is()`-wrapping, and directly useful to any tool doing CSS analysis or linting.
The counts are not digits of a base-10 number.
Eleven classes beat one id in no ordering anyone has implemented, so comparison is lexicographic on the triple and the counts are never carried or clamped.

### AST Shape

Structural marks share one record.
`,` `:` `;` `<!--` `-->` and the three unmatched closers are one `Punctuation` record with an exhaustive `Kind`, not eight records: they have one shape, and eight types differing only in name would buy pattern-matching precision nothing needs.

Unmatched closers reach the tree only because the spec preserves them.
A matched pair becomes a `SimpleBlock` or a `FunctionValue`, so a lone `)` means the source had one too many.
`SimpleBlock` stores only its opening bracket; whether the pair was closed is a diagnostic, since a block closed by end-of-input has no closing bracket to record.

The spec's bare `Function` is called `FunctionValue` here, because `java.util.function.Function` is imported by roughly every consumer this package will ever have.

The universal selector is a `TypeSelector` named `*`.
That is what the grammar says, and both occupy the same position in a compound selector.
They differ only in specificity, which `isUniversal()` answers.

Namespace prefixes have three states, and `null` is not `""`.
`null` means no prefix written (`div`); `"_"` means any namespace (`_|div`); `""` means explicitly no namespace (`|div`).

One piece of source style is preserved, `:before` versus `::before`.
Four pseudo-elements predate the `::` syntax and are still legal with one colon, and normalizing them would break exactly the old engines legacy serializer mode exists for, so `PseudoElementSelector.doubleColon` records which spelling was written.

Nothing else about source style survives.
Attribute values do not record whether they were quoted, strings do not record which quote character was used, and whitespace does not record its exact characters.
All three are serializer decisions.

### Selector Grammar

Selectors parse from tokens, not from component values.
A component-value list has already turned `[href^="x"]` into a block and `.card` into a delimiter plus an identifier, but worse, it has discarded whether two tokens were written with nothing between them: `a|b` is a namespaced type selector and `a |b` is not.
So `SelectorParser` runs over the prelude's raw token range, and `Parser` never builds component values for a qualified rule's prelude.

Comments inside a selector are dropped, the one place in the library where a comment does not survive.
Every grammar that cares about adjacency has to look through them, since `.a/_x_/.b` is one compound selector and not two, and there is no node in the selector grammar to hang the comment on.
If selector-internal comments ever matter, they need a home in the selector AST first.

Forgiving and non-forgiving selector lists behave differently, on purpose.
`:is()` and `:where()` take forgiving lists: an alternative that does not parse is dropped and the rule stays valid, which is what lets a stylesheet use a selector some browsers do not know.
`:not()` and `:has()` are not forgiving.
Dropped alternatives are reported as `WARNING`, an invalidated rule as `ERROR`.

`:has()` takes a relative selector list, so `:has(> img)` opens with a combinator relating its argument to the scoping element.
That breaks `ComplexSelector`'s otherwise-invariant "first step carries `Combinator.NONE`", and the `selectors-level4` fixture is what guards it.

A nested rule's prelude is also a relative selector list, so `> .title { }` inside another rule is legal and means `& > .title`.

Pre-standard `:is()` spellings are parsed structurally.
`:matches()`, `:any()`, `:-moz-any()` and `:-webkit-any()` all get real selector arguments, so flattening and specificity see through them.

`:nth-child()` is half-parsed.
The `An+B` part stays as unparsed component values, a micro-grammar with no bearing on flattening, but the selector list after `of` is parsed, because specificity depends on it.

`&` has no specificity this parser can compute.
Its real weight is that of the enclosing rule's selector list, which a node with no parent pointer cannot see, so `Specificity.of` counts it as zero.
Compute specificity after flattening has substituted the parent in.

---

## Error Recovery & Diagnostics

Recovery is the literal CSS Syntax Module Level 3 algorithm.
Bad-string and bad-url handling, "consume a component value", "consume the remnants of a bad declaration" and mismatched-bracket recovery are implemented as the spec defines them rather than approximated with resync-on-next-`;`-or-`}` heuristics.
That is what makes WPT fixtures work as a real oracle: any deviation shows up as a failure you would otherwise explain away as an intentional difference.

Diagnostics are returned, never thrown.

```java
public record ParseResult(Stylesheet ast, List<Diagnostic> diagnostics) {}
record Diagnostic(Severity severity, String message, SourceSpan span) {}
```

The spec treats recovery as defined behavior, not an error state, so the API does not force exception handling around it.
A returned list also stays testable against golden fixtures.

Two differences from the CSSOM are deliberate, and both are recorded in the fixtures.
`@charset` is kept as an ordinary statement at-rule instead of being dropped, and a custom property whose value contains a mismatched bracket is kept rather than invalidated.
Both are semantic validation, a stated non-goal.
The first is what makes an `@charset` [able to lie about its own bytes](CLI.md#output-is-utf-8-and-an-charset-can-lie) once something writes UTF-8, which the CLI warns about and `Optimizations.dropCharset()` can act on.

### Parsing Behavior Worth Knowing

`color: red` and `a:hover { }` start identically, and the colon settles nothing.
What settles it is which comes first at bracket depth zero after the colon: an opening brace means the rest was a selector; a `;`, a `}` or end of input means it was a value.
Custom properties short-circuit the check entirely, since their value is arbitrary token soup by definition, braces included, so `--x: {a}` is a declaration.

The lookahead is bounded by the first depth-zero `;`, `}` or `{`, which in well-formed CSS is one declaration away.
Adversarial input with no terminator makes it scan further, but the rule is then dropped and the cursor jumps past the scanned region, so it does not compound.

`@layer` is two different rules: `@layer base, components;` is an `AtRule`, `@layer base { ... }` a `ConditionalGroupRule`.

Names keep their source case and matching is ASCII case-insensitive at compare time, so `@MEDIA` parses as a conditional group rule and serializes as `@MEDIA` until a lowercasing transform says otherwise.

Whitespace survives only where it means something.
Between component values it is meaningful, since `1px solid` is two values and the gap says so, so it is kept as a `WhitespaceToken`.
At the edges of a declaration value, an at-rule prelude, or an opaque at-rule block it means nothing and is trimmed.
A declaration's span excludes both its trailing whitespace and its terminating `;`.

An unclosed `(` is catastrophic for everything after it, because it swallows the rest of the stylesheet looking for its partner.
That is what browsers do and it is spec-correct, but one typo can drop every rule that follows, so the diagnostic names the consequence: _unclosed ( ) block, which consumed everything after it_.

A bad-string-token swallows to end of line, semicolons included.
`font-family: "unterminated;` consumes the `;` into the bad string, so the declaration continues into the next one and merges with it.
Also spec-correct, which is why the diagnostic says _ended by a newline_.

Recursion is bounded at 512 levels.
Past that, a block is closed where it stands and the rest consumed flat, because "never throws on malformed input" has to include `StackOverflowError`.
Every opener in a long run hits the bound again, so the diagnostic is reported once per cursor rather than once per level.

Every parse error section 4 defines for the tokenizer is reported: a bad-string-token, a bad-url-token, and a string, `url()` or comment that end of input cut short.

One known gap is left open on purpose.
A bad-string or bad-url token in a selector prelude is never named.
The rule is still rejected, but the diagnostic describes the shape the selector grammar tripped on rather than the malformed token that caused it: for `.c { font-family: "unterminated;` it points at the colon and says _expected a pseudo-class or pseudo-element name after ':'_.
It costs correctness nothing and costs a reader clarity, and [drawing a caret under it](CLI.md#drawing-the-source-costs-one-field-nobody-was-reading) is what made plain how misleading the message is.

---

## Serializer & Output Modes

Passthrough-nested reformats; it does not byte-match.
Comments and nesting structure are preserved exactly, while whitespace and indentation are re-formatted to a consistent style.

Byte-exact round-trip would need either the original source buffer kept alive alongside the AST for its whole lifetime, or trivia nodes throughout the tree that every walker has to skip, both real costs for an invariant that is not a goal.
A side effect: passthrough plus the returned diagnostics makes this a de facto CSS formatter and linter.

Flattening's `&`-expansion is a serializer option, defaulting to an `:is()` wrap, with naive concatenation as the legacy option.

```css
.card, .panel { & .title { font-weight: bold; } }

/* default (:is()-wrap) - one rule, specificity-correct: */
:is(.card, .panel) .title { font-weight: bold; }

/* legacy (naive concatenation) - duplicated, but no :is(): */
.card .title, .panel .title { font-weight: bold; }
```

`:is()` is spec-correct and has shipped in evergreen browsers since early 2021, but old Android TV and smart-TV WebViews may predate it.
The legacy expansion duplicates the selector rather than the rule, one prelude with two alternatives, and it is not `:is()`-free in every case, since a `&` in a position no single parent selector can be spliced into still falls back to `:is()`.
See [Flattening](#flattening).

Identifier encoding is the same kind of option: `café` literally by default, `caf\e9` under the legacy-safe option.

Legacy options are individual flags plus one preset.

```java
SerializerOptions.builder()
                 .legacyCompatible()          // flips every legacy-safe flag at once
                 .identifierEncoding(LITERAL) // ...but this still overrides just this one
                 .build()
```

`legacyCompatible()` is sugar over the individual setters, not a separate code path, so a new legacy-relevant option joins the group automatically and an explicit override always wins.

### "Minify" Means Exactly One Thing

`Formatting.MINIFIED` strips whitespace, comments and redundant separators, and nothing else.
Zero risk of changing meaning.
Anything semantic is opt-in and lives in `Optimizer`.

That separation cost a rename.
`Minifier.minify` used to run semantic transforms while `Formatting.MINIFIED` only stripped whitespace, and one of the two had to stop claiming the word.
`Formatting.MINIFIED` kept it; the tree pass is `Optimizer.optimize`, matching the `Optimizations` it takes.
Do not reintroduce the second meaning.

### Flattening

Whether `&` appears at all is the whole decision.
A nested selector with no `&` is relative and the parent is prepended to it; a nested selector with a `&` anywhere is already absolute and nothing is prepended, only substituted.
That second half is what makes `.card { .theme-dark & { } }` mean `.theme-dark .card` rather than `.card .theme-dark .card`, and it is the rule a naive "always prepend, then substitute" implementation gets wrong for the one nesting pattern people write on purpose.

Substituting a `&` has three outcomes, and the third is not a fallback for ugly cases.
It is the only correct answer for most of them.

* Splice, when the `&` opens the selector and the parent is a single alternative: `.card .body` + `& > .title` is `.card .body > .title`.
* Inline, when the parent is one compound selector carrying no type selector or pseudo-element: `.card` + `.open &` is `.open.card`.
  The exclusions are positional, since a type selector has to come first, so inlining `div` into `.open&` would spell `.opendiv`, and a pseudo-element has to come last.
* Wrap in `:is()` otherwise.
  Not cosmetic: `.x > &` with a parent of `.a .b` is not `.x > .a .b`, which relates `.x` to the wrong element entirely.
  It is `.x > :is(.a .b)`.

Legacy `DUPLICATE` mode substitutes one parent alternative at a time, which removes the multi-alternative reason for `:is()` but not the positional one, so it can still emit `:is()` for a single complex parent.
There is nothing else correct to write.

A rule splits where a nested rule interrupts its declarations, so `.a { color: red; .b { } background: blue }` flattens to three rules in that order.
A declaration written after a nested rule cascades after it, and hoisting it back up would silently change which one wins on a tie.

A nested conditional group rule is hoisted, so `.a { @media print { color: red } }` becomes `@media print { .a { color: red } }`.
One nested inside another group rule is not: `@media print { @supports (...) { } }` stays nested, because merging conditions means evaluating media queries, a stated non-goal, and every engine that understands `@media` has understood nested ones since CSS 2.1.

A top-level `&` is left alone.
It has no parent to stand for, and rewriting it would be a guess about what the embedding document means by it.

### Writing

Whitespace is dropped only where the grammar cannot want it: next to a bracket that opens, before a mark that closes.
The exception that decides the rule is `(`, since `and (min-width: 0)` and `and(min-width: 0)` are different token streams, because `and(` is one function token.
`{` and `[` have no such analogue, so `from {` minifies to `from{`.

A dropped comment can fuse two tokens, so it may leave a space behind: `a/_x_/b` is two identifiers and `ab` is one.
The writer tracks a pending separator and spends a byte on it only where the two sides would actually join, so `a/_x_/,b` still minifies to `a,b`.

A bad-string, a bad-url or a lone `\` delimiter writes nothing, because there is no text that reads back as itself.
The source verbatim would reproduce the error, and inventing a terminator would invent a value.
For the backslash, section 4.3.8 says one before a newline does not start an escape, so it reaches the tree as a `DelimToken`, and written verbatim it starts an escape against whatever the writer emits next: `b: c\` came back as a declaration that had swallowed its own terminator.
`CssWriter.writesNothing` is the predicate for all three, and what it leaves behind is whitespace with nothing on one side of it, which is dropped too, so `background: url(bad url)` comes back as `background:;`.

Any prefix written before a value must be revocable.
The writer commits an indent, a separator, a space or an opening brace before it knows whether the value produces text, and when that value writes nothing, the prefix is left behind as output that re-parses to nothing.
Every such path marks the buffer length and rolls back.
Equally, every forward scan asking "does anything come after this" has to look through the write-nothing tokens.

This is the family four of [the five fuzz-found defects](#what-the-fuzz-suite-is-for) shared, and [source maps turned it into a named invariant](SOURCEMAPS.md#anything-that-shortens-the-buffer-must-shorten-the-mapping-list): **`CssWriter.rollback(mark)` is the only thing in the writer that may shorten the buffer**, and `setLength` appears once, inside it.

A `url()` function whose first argument is not a string writes nothing.
`url(` is a function token only when a quote follows it (section 4.3.4); otherwise 4.3.6 lexes a url-token, whose body admits no whitespace, quotes or parentheses and which swallows everything up to the next `)`.
So writing `url(` in front of contents that do not open with a quote produces a bad-url however faithful those contents are.
`CssWriter.isUnspellableUrl` is the test, and it joins `writesNothing`.
Unlike the bad tokens, this drop is reported through the `Consumer<Diagnostic>` overload, because it is the serializer's own limitation rather than something the parse already complained about.

That test is structural, read off the tree and not off the output.
The first implementation decided it from the rendered text: strictly less lossy and completely wrong, because a value that writes nothing has to be visible to the forward scans before it is written.
`x url("` with a newline inside the string came out as `b: x ;`, the separator left behind in front of a value that produced nothing.
Anything `writesNothing` answers for must be answerable from the AST alone.

What the structural test gives up is a url whose wreckage happens to spell a legal url-token body: `url("` plus a newline plus `foo)` could be written `url(foo)` and is instead dropped.
On the differential harness that is three samples in 160,000, all recovered garbage and all fixed points either way.

Two related traps.
Escaping the function name does not sidestep the rule, because section 4.3.4 matches "url" against the ident sequence's decoded value, so `\75 rl(` is still a url-token, and cassette gets this right.
And the broken set is narrower than "the argument is not a string": `url("a" b)` holds three arguments and round-trips perfectly, because what decides the tokenizer's branch is only the quote it sees first.
A rule written against the argument count would have dropped output that works.

A trailing `;` inside an opaque at-rule block is a token, not a separator.
Dropping the last one, as is right at the end of a declaration list, ate another `;` on every round trip through `@a { color: red;;;; }`.

A hex escape always ends with its terminating space.
The case that bites is the separator: `.caf\e9 .x` without the space is `.café.x`, a different selector.
Costing a byte unconditionally is cheaper than being clever.

Escapes, quotes and unquoted values are decided from scratch.
The AST holds decoded text, so there is no author style to reproduce: strings get `"`, attribute values are always quoted, and a `url()` is written bare when its grammar allows and quoted when it does not.

### Round-Tripping

The property is idempotence, not identity.
"Output re-parses to the same tree" is false by construction for recovered input, since an unclosed block gains its closer and a bad token disappears, and demanding it would mean either giving up recovery or lying about it.
What has to hold is that the second pass changes nothing: `serialize(parse(serialize(parse(x))))` equals `serialize(parse(x))`, for all four option sets.

Seven defects came out of that property alone, every one invisible to every fixture.
That is the argument for it, and also the warning: every one was present long before the property found it, because the generator had never drawn the input.
The property is only as strong as `CssLikeArbitraries`.

---

## Optimizations

Optimizations fuse into a single tree pass.

```java
interface NodeTransform<T extends Node> {
    Set<Class<? extends T>> types();   // the driver dispatches on these
    T apply(T node);
}

Stylesheet optimize(Stylesheet ast, List<NodeTransform<?>> enabled)   // Optimizer
```

AST nodes are immutable records, so independent passes would mean N full tree rebuilds for N optimizations.
One driver walking the tree once and invoking every enabled per-node-type handler inline keeps allocation to one rebuild regardless of how many are on.
Chosen for the allocation goal, not for CPU time.

### The Optimization Pass

A transform declares the node classes it wants, and naming one the pass does not walk is an error.
Selectors are not walked, since nothing shipped rewrites one and a transform that wants to can take the `StyleRule` and rebuild its prelude, so `NodeTransform<ClassSelector>` fails loudly instead of silently never firing.

A custom property's value is not walked at all.
It is token soup by definition, and every optimization that is safe for `margin` is a guess about a value whose meaning lives somewhere this library cannot see.

Dropping a zero's unit is a declaration-level transform, not a token-level one, because inside `calc()` a unitless zero is a different type: `calc(0px + 5%)` parses and `calc(0 + 5%)` does not.
The transform rewrites only the top level of a declaration's value.

A transform may change a node's type, and the driver re-dispatches when it does, since `0.0px` loses its unit and becomes a number the number transform had already passed over.
The lookup repeats until the type settles, with a visited set so two transforms that undo each other stop rather than spin.

### What the Optimizations Are Worth

`-O` is not a size feature, and that is the finding.
It costs 11-13% of a parse in time and 6-8% in allocation when enabled, and buys 0.2% of bytes raw and nothing compressed.
On Bootstrap the gzipped output is a few bytes larger, because shortening a color removes a repetition deflate was exploiting.
The cost argument for removing the transforms does not apply, though, since they are off by default.

The win is concentrated in one transform:

|                   | share of the `-O` size win                 |
|-------------------|--------------------------------------------|
| `compact-numbers` | **100%** on Bootstrap, **79%** on Tailwind |
| `drop-zero-units` | 0% on Bootstrap, **21%** on Tailwind       |
| `shorten-colors`  | 0% on both                                 |
| `lowercase-names` | 0% on both                                 |

All four work against input that needs them: `#FFFFFF` -> `#fff`, `#aabbcc` -> `#abc`, `0px` -> `0`, `0.50em` -> `.5em`.
Real dist CSS already uses short hex and lowercase names, so two of the four are insurance against hand-written input rather than size wins.
Two of the six transforms are not size plays at all: `drop-charset` and `drop-source-map-url` exist for stale input metadata, and `lowercase-names` is really normalization, since making `@MEDIA` and `@media` comparable is worth something to a tool at zero bytes.

cassette's minified Bootstrap is within **0.15%** of the `bootstrap.min.css` Bootstrap itself ships, 232,538 B with `-O` against their 232,803, and 173 B smaller gzipped.
That parity is the number to quote; an absolute 80-83% reduction mostly describes how much whitespace the input had.

Two transforms are named-only and not in `all()`.
`dropCharset` and `dropSourceMappingUrl` remove an assertion the input made about itself, and whether that assertion has gone false depends on what the caller does with the output.
`dropCharset` is the sharp case: `serialize` returns a `String` and the caller decides the bytes, so `@charset "shift_jis"` is true for a caller writing Shift_JIS and a trap for one writing UTF-8.
Enabling it is the caller supplying that assertion, which `all()` cannot do on anyone's behalf.
So `all()` is documented as every optimization that rewrites a value.

Shorthand merging is not implemented.
Folding `margin-top` and its three siblings into one `margin` needs a property database and a cascade-aware view of a rule that this library does not have, and getting it subtly wrong changes what a stylesheet means.

Quote normalization is not an optimization at all.
The AST never recorded which quote character was written, so the serializer always writes `"`.

#### Why We Do Not Drop Empty Rules

There are no empty rules in real CSS.
Counted over the corpus: **zero, in 41,853 rules**, on all three entries.
Generated output does not contain them, and the obvious way cassette might create one does not either, since flattening `.card { .title { ... } }` emits `.card .title { ... }` and leaves no `.card {}` behind, which we checked rather than assumed.

`@layer base {}` is also not a no-op.
It declares the layer and fixes its position in the cascade order, so dropping it changes which rules win, while `@media print {}` next to it really is inert.
A transform dropping "empty group rules" would have to carve out `@layer` specifically, `@keyframes x {}` is arguable on the same grounds, and deciding either is semantic validation, which is [a stated non-goal](#goal--non-goals).

Zero measured benefit, no source of the input it targets, and a cascade judgment in the middle of it, against permanently widening a public surface.
Declined.

Duplicate rule collapsing is a different matter and is not declined.
It needs the cross-file, cascade-aware view a [cross-file optimizer](BUNDLING.md#what-this-enables) is scoped for.

---

## Public API Surface

```text
lexer/         CssEncoding, SourceText, CodePoints, Escapes, TokenType, Tokenizer, TokenBuffer
               - never exported; the tokenizer/parser boundary is internal
               - CodePoints and the hex helpers are `public` so other packages can share
                 them; unexported, so that widens nothing a consumer sees

text/          Ascii                                            - never exported

ast/           Node
                 +- Stylesheet
                 +- Rule: StyleRule, AtRule, ConditionalGroupRule
                 +- Declaration
                 +- ComponentValue: PreservedToken (14 records), FunctionValue, SimpleBlock
                 +- Selector: SelectorList, ComplexSelector, CompoundSelector,
                              SimpleSelector (7 records)
               plus Combinator, CombinatorStep, AttributeMatcher, AttributeCase,
               Specificity, SourceSpan                          - all exported

diagnostics/   Diagnostic, Severity, SourceResolver, LineIndex  - exported

parser/        CssParser, ParseResult, DecodedSource            - exported
               TokenCursor, Parser, SelectorParser, NodeStack   - package-private

serializer/    CssSerializer, SerializerOptions, NestingMode, Formatting, NestingExpansion,
               IdentifierEncoding, NodeTransform, Optimizer, Optimizations,
               Flattener, SerializeResult                       - exported
               CssWriter, NestingExpander, Escaping, Mappings,
               MapPass                                          - package-private

bundle/        Bundler, BundleOptions, BundleResult, Importer, Source, SourceIndex,
               Origin                                           - exported
               ImportPrelude                                    - package-private

sourcemap/     SourceMap                                        - exported
               Vlq, Json                                        - package-private
```

Six exported packages of eight.
**`public` inside `lexer` or `text` means "another package in this module needs it", not "a consumer can call it".**

The shared character utilities live in two places, and which one is not arbitrary.
ASCII case folding is `text.Ascii`, whose `lower` and `equalsIgnoreCase` are the only correct way to match a CSS name.
It is its own package precisely because `ast` needs it: `ast` imports nothing else internal and `lexer` imports `ast.SourceSpan`, so putting the folders in `lexer` would close a package cycle.
Everything that classifies rather than folds stays in `lexer` (`CodePoints`, the `isIdent` bit table, the hex helpers), because nothing above the tokenizer needs them and that loop is measured in instructions per token.

**Never reach for `String.equalsIgnoreCase` or `toLowerCase` on a CSS name.**
`String.equalsIgnoreCase` folds U+0130, U+0131 and U+017F into ASCII and `toLowerCase` folds U+212A KELVIN SIGN, which produced three real misparses, one of them meaning-changing.
`AsciiTest` names the four characters, and `AsciiNameMatchingTest` builds every input from `char` constants, because a test whose subject is an invisible character has to name its code points.

`LineIndex` sits in `diagnostics` rather than in the CLI because two consumers need line counting and neither can cache for the other: a renderer asking once per report, and a map generator asking once per mapping with nothing bounding how many.
It is 0-based, because a source map is and a diagnostic is the only thing here that is not, so the `+ 1` lives at the rendering end.

Entry points are stateless and static.

```java
ParseResult result = CssParser.parse(bytes);
String css = CssSerializer.serialize(result.ast(), SerializerOptions.builder().(...).build());
```

No parser objects to configure or manage, safe from any thread.
There is no per-call cost worth amortizing, so instance ceremony would cost the caller for no benefit.

`Flattener` is exported: tree in, tree out, next to `Optimizer`.
It was internal only because nobody had asked, and it is useful without serializing, to flatten once and serialize both ways, or to read absolutized selectors.

`CssSerializer.serialize` is overloaded for a single node rather than given a second name, with one guard: a `Stylesheet` reaching the `Node` overload is delegated to the `Stylesheet` one rather than written as a fragment.
Overload resolution is by static type, so without that a stylesheet held in a `Node` variable would silently skip flattening.

A caller that needs the text as well as the tree can keep the decoded buffer.

```java
DecodedSource decoded = CssParser.decodeSource(bytes, null, base, diagnostics::add);
ParseResult result = CssParser.parse(decoded);
index.add(id, decoded.text());
```

`decode` returning a `String` and `parse(CharSequence, int)` taking one is the simpler pairing and stays.
It costs three bytes per source character: one for the string, two for the fresh `char[]` parsing it builds.
Only the string survives, since a `bundle.SourceIndex` retains it, `sourcesContent` wants it, and a compact string retains half what the buffer underneath would, so materializing it is right.
The second buffer is not: decode had already built one and threw it away.
`DecodedSource` keeps it, worth 2.0 B/char and -6.7% of everything a bundle allocates.
It also answers `encoding()` from what the decode settled on, which saves a bundler sniffing the bytes a second time to pass an environment encoding down an `@import`.

The one asymmetry is that `parse(DecodedSource)` does not report the charset fallback, because `decodeSource` took the sink and already did.
Reporting twice would give a bundle two warnings per source read in the wrong encoding.
The other overloads hold text with no encoding left to question.

Writing can lose content, and says so through an optional sink.

```java
String css = CssSerializer.serialize(ast, options, diagnostics::add);
```

A value with no spelling that reads back as itself is dropped rather than written wrong; see [Writing](#writing).
Most are wreckage the parse already reported; a `url()` that cannot be spelled is the serializer's own limitation and nothing else would name it.

`Diagnostic.DISCARD` is what the overloads without a sink pass.
There were two private copies of the same do-nothing lambda in `src/main`, in `parser` and `serializer`, which share no internal package, so the choice was one public constant or permanent duplication.

The sink is a `Consumer<Diagnostic>` rather than a `SerializeResult` mirroring `ParseResult`, because the two entry points are not symmetric: a parse of real CSS routinely produces diagnostics, while serialization of a cleanly parsed tree produces none, ever.
A second return type on every overload would tax every caller for a case almost none of them hit.
It does mean `serializer` depends on `diagnostics.Diagnostic`, weighed as cheaper than a second diagnostic type.

There is no package cycle: `parser` imports nothing from `serializer`, so `serializer` -> `parser` is a one-way dependency in pipeline order.
What put `Diagnostic` and `Severity` in their own package instead was `cli` being not a fourth internal package but an outside consumer.
Otherwise an embedder would write `import dev.nullkitty.cassette.parser.Diagnostic` in order to print a bundler's import-cycle error, from code that never parses anything.
The package also had a third member waiting, since the CLI needs a narrow span-to-text interface that `bundle.SourceIndex` must also satisfy.

[SOURCEMAPS.md](SOURCEMAPS.md#surface) adds a `SerializeResult` anyway, and the two do not conflict.
The argument above is about taxing callers who did not ask, and a caller reaching `serializeWithMap` has asked for the second output by name, while the `serialize` overloads keep returning `String`.

The `SourceSpan.text` trap is stated in its own javadoc.
The method is valid only for a single-source tree, a wrong buffer returns the wrong characters rather than failing since the offsets are still in range, and `SourceIndex.textOf` is the bundle-aware form.
Written as `{@code}` and not `{@link}`, because `ast` cannot see `bundle` and doclint runs under `-Xwerror`.

---

## Testing Strategy

One input per fixture, with named output variants.

```text
fixtures/nesting-basic/
  input.css
  expected/
    nested.unminified.modern.css
    nested.minified.modern.css
    flattened.unminified.modern.css
    flattened.unminified.legacy.css
    ... (only the combinations actually asserted)
```

Output varies along three independent axes (nested/flattened, minified/not, modern/legacy) and growing.
Named files under `expected/` are additive; a flat filename convention encoding every axis has no way to mark "intentionally untested" versus "forgot this combination".

`ast.txt` holds the tree and the diagnostics in one file, because a fixture asserting only the tree would pass just as happily whether a malformed rule was recovered or silently swallowed.
Regenerate with `./gradlew test -Dcassette.fixtures.update=true` and read the diff as the review artifact.

`Golden` takes update mode as a parameter rather than reading the system property directly, so its own self-tests can pass `false` and cover the comparison that update mode exists to skip.

Tests mirror the package layout: `TokenizerTest` / `TokenizerFixtureTest`, `CssParserTest` / `SelectorParserTest` / `ParserFixtureTest`, `SpecificityTest`, and the jqwik properties under `fuzz/`.

Five jqwik properties cover the parser and serializer: never throws, every span lies inside the input, every parent span contains its children's, every declaration value is whitespace-trimmed, and serializer idempotence.

A differential harness sits outside the build, for the question the property cannot answer.
When idempotence fails, the first thing worth knowing is whether the change under review caused it, and neither the shrunk sample nor a re-run tells you, because the seed moves and the sample's rendering is lossy.
[`tools/differential-fuzz`](../tools/differential-fuzz/README.md) runs the same generated inputs through two builds and diffs them, as two plain `main` classes in no source set, so it costs the build nothing.

WPT fixtures are BSD-3-Clause, compatible with Apache-2.0.
They are malformed-CSS cases pulled from web-platform-tests to assert recovered output against spec-defined behavior.

WPT's `css/css-syntax` suite is not a corpus of malformed-input cases with expected output, which is what it looks like from the outside.
It is about 25 `testharness.js` HTML documents asserting through the browser's CSSOM, and some of what it asserts (cascade, computed values, CSSOM-only rules) is out of scope by construction.
Seventeen were translated, two rejected with reasons, and each carries a `SOURCE.md` saying what upstream claimed and whether cassette agrees.
See the [fixtures README](../src/test/resources/fixtures/README.md).

### What WPT Found

Three bugs, every one of which the hand-written fixtures had happily agreed with.
That is the argument for an outside oracle in one line.

_A prelude starting `--x:` is not a selector._
Section 5.5.3 drops a qualified rule whose first two significant prelude values are an identifier beginning `--` followed by a colon.
Without that step `--x:hover { }` parses as "identifier followed by pseudo-class", and a stylesheet that meant to declare a custom property silently grows a rule.
Nested, the answer is the opposite and cassette already had it right, because `looksLikeDeclaration` short-circuits on the `--` prefix.

_A mismatched closer closed a block._
Three separate scans (`findMatchingClose`, `findBlock` and `looksLikeDeclaration`) counted every closing bracket alike, so a stray `]` inside `{ ... }` ended the block early.
The spec keeps an unmatched closer as a preserved token, and all three now share `nextDepth`, which tracks the closer expected per level.
This had been hiding in plain sight in the `recovery-structural` fixture, which had been recovering better than the spec allows.

_A backslash at end of input inside a string became U+FFFD._
Section 4.3.5 says to do nothing with it, while 4.3.7, which every other token type goes through, resolves it to U+FFFD.
The tokenizer was leaving the backslash inside the value span, so `Escapes` applied the wrong rule.
`consumeString` now ends the span before it.

### What the Fuzz Suite Is For

The idempotence property found five defects no golden file saw, and pulling that thread produced the [prefix-before-nothing rule](#writing).
Four of the five shared that one shape: an indent, a separator, the space before an at-rule prelude, or the brace of an opaque block, written ahead of something that turned out to write nothing.
The fifth is the [unspellable `url()`](#writing), a different failure, the writer not modeling a tokenizer special case rather than not rolling back a prefix.

That is a finding about the property: it is load-bearing, and its coverage is limited by what the generators happen to draw.

What that calls for is fragments carrying a seam, not more atoms.
Both generators concatenate atoms, so a defect needing a particular token sequence is drawn essentially never.
The regression the structural `url()` rewrite fixed needed `c`, whitespace, `url(` and `"` in that order, four picks from a seventy-fragment pool at one to six fragments per sample, and 200,000 samples never produced it.
Every defect either generator has found shares one shape, _something that writes text, then whitespace, then something that writes nothing_, so that sequence is a fragment:

```text
"a{b:c url(\""      "@a c url(\""     "x url(\""      <- separator before a dropped value
"a{b:c \""          "a{b:c \\"                        <- the same seam, other write-nothings
"url(\"x\" y)"      "@a url(\"\n)"                    <- write-nothing before a closer
```

Both generators carry a 25-entry seam block crossing the four values that write nothing (a bad-string, a bad-url, a lone `\`, an unspellable `url()`) with the four prefixes written ahead of one.
Roughly half are closed forms, which is the half worth having: `a{b:c url(x y)}` carries its seam whatever fragment is drawn next, while `a{b:c url("` depends on it.

The widening was validated rather than assumed, because a zero from a fuzzer is only evidence if the pool can find something.
Measured against a tree that still had the `url()` defect, atoms alone found 2 non-fixed-points per 200,000 samples; with seams it is 15-20 per 30,000, every one the known defect.
On the fixed tree it is 0 across 320,000 samples of `Fuzz` and 120,000 of `FuzzOpt`, and the 739-806 rows per 30,000 that differ between the two builds all contain `url("`.
`CssLikeArbitrariesTest.mostInputCarriesASeam` pins the coverage at 83%, so a later narrowing fails loudly instead of turning the property green and silent.

Two cautions.
jqwik's shrunk sample cannot be trusted as a literal input, since it prints the string with escapes already applied, so a backslash-newline and a backslash-space render identically.
And a test written against a hazard whose symptom is silent can very easily assert something that was never going to break.
`CssParserTest.AbandonedBuilds` is the standing example, and [the mapping rollback test](SOURCEMAPS.md#the-test-that-cannot-exist) is the case where the specified test turned out to be impossible.

---

## Performance Strategy

Allocation rate is the tracked JMH metric, not just throughput.
A correctness-preserving-but-slow regression, a stray `String` in the hot loop or an optimization pass rebuilding the tree redundantly, shows up only in allocation profiling.
So every design decision above that names a cost was settled against a measurement rather than against an argument.

Those measurements are their own record.
[PERFORMANCE.md](PERFORMANCE.md) holds every one of them: the corpus and what its silence means, the token buffer's sizing rate, G1's humongous-object cliff and the two changes that cleared it, the `isIdent` bit table, the AST memory census, the `NodeStack` child-list change, what a source map costs, and the two negative results that stop the same sweep being run twice.

Read [`benchmarks/README.md`](../benchmarks/README.md) before quoting any figure from it.
The measurement traps recorded there have cost us more time than the changes themselves.

None of the decisions above is permanent, and this record exists so that changing one stays cheap.
Each says what it cost and what it bought, so a change has something to argue against instead of a blank slate.

The overcorrection to avoid is treating the measured ones as settled for good.
The corpus is a thin witness, the JVM underneath keeps moving, and two of the findings in `PERFORMANCE.md` are negative results that hold only for the shapes we fed it.

So when we change something in here, the question is not whether the old reasoning was right.
It is which measurement would now say otherwise.
