# c(a)ssette - Performance

*Every measured finding, and the method that produced it.
Companion to [ARCHITECTURE.md](ARCHITECTURE.md), which owns the design the measurements were taken against, [BUNDLING.md](BUNDLING.md) and [SOURCEMAPS.md](SOURCEMAPS.md).*

Read [`benchmarks/README.md`](../benchmarks/README.md) before quoting any figure below.
The measurement traps recorded there have cost more time than the changes recorded here.

---

## What is measured, and against what

**Allocation rate is the tracked JMH metric, not just throughput.**
A correctness-preserving-but-slow regression, a stray `String` in the hot loop or an optimization pass rebuilding the tree redundantly, shows up only in allocation profiling.

Benchmark corpus: vendored real-world CSS, not synthetic.

```text
src/jmh/resources/corpus/
  small-handwritten.css         3.6 kB   (committed)
  medium-bootstrap.css          281 kB   (Bootstrap 5.3.3, MIT; fetched, LICENSE committed)
  large-generated-tailwind.css  3.6 MB   (Tailwind 2.2.19 dist, MIT; fetched, LICENSE committed)
```

Synthetic generators under-represent the messy shapes real CSS contains: deep nesting, vendor prefixes, unusual selectors.
So we benchmark against a real corpus, and pin it.
The two large entries are fetched, not committed: a fresh checkout must run the two `curl` commands in the [corpus README](../src/jmh/resources/corpus/README.md) before benchmarking, and `Corpus.isAvailable()` degrades the suite to what is present rather than failing.

We pin a Tailwind dist build rather than generating one locally, because CLI output is generation-config dependent and, in Tailwind 4, content-dependent.
Both entries parse with zero diagnostics and serialize idempotently, which is the standing check when a corpus entry is replaced: a file that provokes a diagnostic is either a parser bug or not the well-formed CSS these are here to represent.

The corpus is also a thin witness, and reading its silence matters.
`small-handwritten.css` is the only entry containing a `&` at all; Bootstrap and Tailwind are compiled, flat CSS.
So any change to flattening shows up on SMALL alone, and the real figure for authored nested input is probably larger than anything here reports.
**We read a benchmark's silence as a statement about the corpus before we read it as a statement about the change.**

---

## Parsing

### The token buffer is sized from a measured rate

The first thing benchmarking found was a one-character mistake.
The token buffer sized its arrays for one token per four characters.
Real CSS runs 3.06 to 4.94 characters per token (3.89 for Bootstrap 5.3.3, 3.85 for Tailwind, 3.06 for Bootstrap 3), so that estimate landed _just_ under the true count on essentially every real stylesheet, filled, grew by half, and copied everything scanned so far.
**Being a few percent short cost 140%:** tokenizing allocated 2.4x the arrays it kept.

|          | allocation           |      |
|----------|----------------------|------|
| tokenize | 5.62 MB -> 3.66 MB   | -35% |
| parse    | 13.42 MB -> 10.60 MB | -21% |

_(Bootstrap 5.3.3; the other two entries move by the same proportions.)_

The replacement sizes for two tokens per five characters, below anything measured, because the two directions are not symmetric: overshooting costs one array discarded whole, undershooting costs a copy of everything so far.
`TokenBufferTest` guards both halves: that the estimate covers a deliberately dense sample without growing, and that the sample really is denser than the corpus it stands in for.

That asymmetry is the general rule for every estimate in the library, and it has been got wrong twice more in the same direction: [the source map's JSON buffer](#tojson-and-the-two-things-measuring-it-found) was short by 3.7-5.6% on every corpus entry, and [the mapping density](SOURCEMAPS.md#mappings-are-two-parallel-primitive-arrays) would have been taken a third low if it had been counted from the corpus alone.

### A multi-megabyte stylesheet costs G1 roughly double, and it is the token buffer

*Found chasing a gap the [bundling benchmark](#bundling-is-not-faster-than-parsing-the-single-file-path-is-penalized) turned up.
The figures here are the pre-fix ones, kept because they are what the diagnosis was made from.*

Parsing Tailwind whole cost **40.3 ms**; parsing the same bytes as the seven files `BundleBenchmark` cuts it into cost **22.6 ms**, at allocation identical to within 0.4%.
Two ways of asking the JVM to stop treating our arrays specially each erased the gap:

| `ParseBenchmark`, LARGE, 6 GB heap | `parse`     | `tokenize` |
|------------------------------------|-------------|------------|
| default (4 MB regions)             | 40.3 ms     | 23.2 ms    |
| `-XX:G1HeapRegionSize=32m`         | **22.3 ms** | **9.6 ms** |
| `-XX:+UseParallelGC`               | **22.2 ms** | n/a        |

It is not collection cost; the counters mislead.
`gc.time` reads 4,821 ms against 572 ms, but those are totals over a 200-second measurement, not per-op like everything else JMH prints, so 2.4% of wall time against 0.29%, two points of a ninety-four point gap.
The cost is in the mutator, allocating.

It is the token buffer, not the decoded text.
`tokenize` builds the buffer from an already-decoded `SourceText`, so it isolates the two: tokenizing is **2.43x** faster with larger regions, while `DecodeBenchmark.decodeUtf8` is unaffected (86 against 93 us) and is 0.2% of a parse anyway.
That is the opposite of the first guess.

And it is structural rather than a growth cascade.
The capacity estimate is `length x 2/5` tokens across seven parallel arrays totalling 32 bytes per token, so the buffer was **12.8 x the source length**, 46.6 MB for Tailwind, matching the 47.1 MB measured.
Each array alone is 1.6 x length (the five `int[]` and `TokenType[]`) or 3.2 x length (`double[] numbers`).
At a 6 GB heap's 2 MB threshold every one of the seven was humongous, with the estimate working perfectly and no growth copy at all.

The diagnosis made a falsifiable prediction.
If the expensive part is humongous _reference_ arrays card-scanned by every young collection, which only the single multi-megabyte buffer produces, then removing the one reference array closes most of the one-file gap and leaves the seven-file case untouched.
It does:

| Tailwind's bytes, `LARGE` | one file | seven files | gap       |
|---------------------------|----------|-------------|-----------|
| before                    | 41.7 ms  | 21.5 ms     | **1.94x** |
| after                     | 25.7 ms  | 21.6 ms     | **1.19x** |

The seven-file case moved +0.4%, inside its error bars, on a change both paths run, so the 38% the one-file case gained is attributable to the humongous behavior and not to tokenizing getting cheaper in general.
**19% of the gap remained**, consistent with the 40.6 MB of humongous _primitive_ arrays still there, and is what `-XX:G1HeapRegionSize=32m` would still recover.

The threshold moves with the heap, and not in the direction that flatters us.
G1 sizes regions at heap/2048, clamped to [1 MB, 32 MB], and calls anything over half a region humongous:

| heap          | region | humongous over |
|---------------|--------|----------------|
| 512 MB - 2 GB | 1 MB   | **512 kB**     |
| 6 GB          | 4 MB   | 2 MB           |
| 32 GB         | 16 MB  | 8 MB           |

So a build tool at `-Xmx2g`, the common case, has the _lowest_ threshold available.

**What is not established.**
Bootstrap is unaffected at both 6 GB and 2 GB (1.95 against 1.92 ms), even though at 2 GB its `double[]` is 899 kB and therefore humongous.
So a humongous allocation is not by itself a cliff; what costs is the _volume_, and one 899 kB array per parse is nothing beside Tailwind's 46.6 MB across seven.
We have not measured where between 281 kB and 3.6 MB of input it starts to bite, and we have not pinned down the precise mechanism inside G1: contiguous-region search, zeroing, or card scanning over the one reference array.

#### What was done: the token type became an ordinal byte

`TokenType[]` was the buffer's only **reference** array.
Storing `(byte) type.ordinal()` with a cached `TokenType[] TYPES = TokenType.values()` to map back is seven touch points, all inside `TokenBuffer`, in a package that is never exported.
Differentially benchmarked at 2 forks x 20 iterations against a stashed build:

|                   | before              | after                |                         |
|-------------------|---------------------|----------------------|-------------------------|
| `parse` LARGE     | 42,543 +/- 2,260 us | 26,069 +/- 104 us    | **-38.7%**              |
| `tokenize` LARGE  | 23,829 +/- 288 us   | 10,537 +/- 10 us     | **-55.8%**              |
| `tokenize` MEDIUM | 758.0 +/- 4.3 us    | **734.3 +/- 1.4 us** | -3.1%                   |
| `parse` MEDIUM    | 2,010.9 +/- 52.6 us | 2,026.2 +/- 9.0 us   | bars overlap; no change |

Allocation fell everywhere and deterministically: **-4.4% on a `parse`, -9.3% on a `tokenize`**, at every corpus size.
On LARGE that is 4,370,832 bytes against 3 x 1,456,928 tokens = 4,370,784 predicted, a match to 48 bytes.

Read the error bars as a result in their own right.
`parse` LARGE went from +/-2,260 to +/-104 and `tokenize` LARGE from +/-288 to +/-10.
Humongous allocation was not merely slow, it was _erratic_, so pre-change measurements of anything touching large input carried noise unrelated to the code under test.

Why it recovered so much is an inference, not a measurement.
Two things changed at once: the array stopped holding references, and at 1.46 MB it stopped being humongous.
Six humongous primitive arrays remained, five `int[]` at 5.83 MB and the `double[]` at 11.66 MB, 40.6 MB in all, and they cost only the 12-15% still separating default regions from `-XX:G1HeapRegionSize=32m`.
So _volume_ of humongous primitive data is comparatively cheap, and the expensive thing was a humongous **reference** array in old generation being card-scanned by every young collection.
That fits every number here but was not isolated.

The one cost is that `type(int)` does two loads rather than one, in the accessor the parser reads most.
`parse` MEDIUM is where that would show, and the bars overlap.
`TokenBufferTest.TypeStorage` guards the assumption underneath: a 128th `TokenType` constant would overflow the ordinal silently.

**What is left, and not scheduled.**

* **Chunk the remaining arrays.**\
  Fixed blocks of 128k entries give a 512 kB `int[]`, under the threshold even at `-Xmx2g`, indexed as `blocks[i >>> SHIFT][i & MASK]`.
  It would take the last 4-6% and retire the growth-copy hazard, since appending a block never copies.
  Against that: one more indirection in the hottest loop in the library, for a remainder smaller than either change below it already took, and both have shown that per-token work there is expensive enough that a branch or three reference comparisons cost 3% of a parse.
  Worth a prototype behind a benchmark; do not assume it wins.
* **Tell embedders about the flag.**\
  `-XX:G1HeapRegionSize=32m` recovers the remainder and costs a line of documentation.
* **Off-heap via FFM.**\
  _Considered and rejected._
  An unusually clean fit, since `TokenBuffer` never escapes `parse` so a confined `Arena` has an exact provable lifetime, the package is unexported, and `Arena.allocate` is not restricted so there is no `--enable-native-access`.
  **Ruled out because the library targets Java 21, where FFM is preview** (JEP 442, final in JDK 22 as JEP 454).
  Compiling with `--enable-preview` forces every consumer onto it and version-locks the class files.
  It would also trade a measured allocation cost for an unmeasured access cost in the inner loop.
  A native-image-only path would change this calculus, since the objection is entirely about what a published artifact forces on consumers.
  The ordinal change above also removed the one array FFM could not have taken without converting it first.

#### And the numbers array became dense

**Only 5-9% of tokens in real CSS carry a number**, 86,314 of 946,206 on LARGE and 3,729 of 72,283 on MEDIUM, and `double[] numbers` had a slot per token.
At 8 bytes it was the largest of the seven arrays, 3.2 bytes per source character, 11.66 MB for Tailwind, and **91% of it stored nothing**.
It was also the first array to cross the humongous threshold, at roughly 650 kB of input rather than the 1.3 MB the `int[]`s need.

It is now sized from the numeric fraction and addressed by an index packed into the spare bits of the token's own `flags` word.
`Tokenizer` defines six flag bits, so 26 are free, an index for 67 million numeric tokens.
**Packing rather than a parallel `int[]` is the whole reason the dense array is free:** an index array would cost four bytes per token and give back only half the eight this saves.

|                               | before              | after                |              |
|-------------------------------|---------------------|----------------------|--------------|
| `parse` allocation, LARGE     | 94.53 MB            | 84.33 MB             | **-10.8%**   |
| `parse` allocation, MEDIUM    | 6.951 MB            | 6.164 MB             | **-11.3%**   |
| `tokenize` allocation, MEDIUM | 3.326 MB            | 2.539 MB             | **-23.7%**   |
| `parse` LARGE                 | 25,541 +/- 2,428 us | **23,819 +/- 79 us** | -6.7%        |
| `parse` MEDIUM                | 2,038.8 +/- 82.9 us | 1,974.1 +/- 47.0 us  | bars overlap |
| `parse` SMALL                 | 25.7 +/- 0.2 us     | 25.7 +/- 0.5 us      | bars overlap |

The buffer went from 11.6x the source length to 8.8x, **worth more than twice what the ordinal byte was**, -10.8% against -4.4% on a parse.
The downstream half, which the differential could not reach: `parseAndSerialize` **-8.0%**, `parseOptimizeAndSerialize` **-7.6%**, the whole bundling family **-10.3 to -11.2%** on LARGE.
The `int[]`s are untouched, so the remaining 4-6% is unaffected; what changed is that nothing crosses the threshold until about 1.3 MB of input, where it used to start at about 650 kB.

Two things the measurement forced, both the same lesson.
The design was "store it densely, look it up by index", and both times the naive form cost more than it saved:

* **The accessor may not ask what type a token is.**\
  Testing `TYPES[types[at]].isNumeric()`, an array load and three reference comparisons, where there had been one array read measured **+3.6% on a Bootstrap parse**.
  Slot zero is reserved holding 0.0 instead: a token that stored no number has no index in its flags, so it reads zero and finds that slot.
* **And `append` may not branch either.**\
  Skipping the store for a non-numeric token costs a branch on every token, which is _more than the 8-byte sequential store it saves_.
  The value is written unconditionally at the dense cursor and the cursor advances only for a numeric token, a non-numeric write being overwritten by the next number, with the index masked in as `& -numeric`.
  That took MEDIUM from +3.6% to parity.

**The general finding, worth more than this change:** at roughly 26 ns per token, the append/read loop is tight enough that _two instructions per token is measurable_.
Three reference comparisons cost 3%.
That is the number to weigh any future per-token work against, chunking included.

**Three guards, each mutation-checked**, because all three failures are silent.
A seventh `FLAG_` constant would land on bit 6 and corrupt both the index and every flag; a 65th `TokenType` would shift out of the numeric mask and make that type read as carrying no number; and the sizing test asserts the dense array _never grew_ rather than that its count fits its capacity, since growth guarantees the second by construction and the test would have passed even if every token took a slot.

#### And `isIdent` answers from a bit table

**The predicate was ordered wrongly, and that mattered more than how it was written.**
Section 4.2's name code points are letters, digits, `_`, `-` and fifteen non-ASCII ranges, and `isIdent` tested them in that order, so anything that is _not_ a letter failed all fifteen ranges before `isDigit` or `== '-'` was reached.
About twenty comparisons to recognize a hyphen, and in the LARGE corpus entry **25% of identifier characters are digits or hyphens** (12.8% and 12.2%).

Measured over the corpus's characters, three ways:

|                                       |             |
|---------------------------------------|-------------|
| as written                            | 3.65 ms     |
| ASCII tests moved ahead of the ranges | 3.13 ms     |
| a bit per ASCII code point            | **2.27 ms** |

Reordering alone takes 14% and is a two-line change; the table takes 38%.
In the real tokenizer, **`tokenize` -16.7 / -14.3 / -9.5%** on LARGE / MEDIUM / SMALL at full rigor, allocation unchanged to 0.00%.
The rest of the tokenizing family moves with it: `scan` **-18.6 / -16.0 / -13.8%**, `scanAndMaterializeValues` about **-19.5%** at every size, `decodeAndScan` **-16.6 / -13.6 / -8.1%**.

A quick-mode differential had put it at -18.0 / -16.3 / -11.6%, over by 1.3 to 2.1 points at every size and in the same direction.
`parse` is not worth quoting from either measurement.
The full run has it at -6.4 / -8.1 / -1.2% against the differential's -3.5 / -7.9 / +0.1%, and the same run has `DecodeBenchmark.decodeUtf8` **+8.2%** on a path nothing touched, so the drift between two full runs is the size of the effect.
Allocation is unchanged in both.

The first measurement of this was wrong.
A `switch` inside the timing loop picked which implementation to call, defeating inlining and adding a branch per iteration, and it reported the bit table as the _slowest_ of the three.
Three separate loops reversed the verdict.
**A micro-benchmark that dispatches inside the measured region is measuring the dispatch.**

The table is derived, not written.
The masks are built from the predicates they replace in a static initializer, because a hand-written `0x7fffffe` is a second statement of the spec that can drift from the first with nothing failing.
`CodePointsTest` checks every code point from `EOF` to `MAX_CODE_POINT` against the definition written out independently, exhaustively rather than sampled, which is what makes a table safe at all.

_Two implementation details._
`1L << c` and `>>> c` use only the low six bits of the shift distance, so the `64..127` half needs no subtraction; and EOF is the one negative input, which reaches the non-ASCII fallback whose every range is positive rather than indexing anything.

**What this does not touch.**
`isWhitespace` is three comparisons and `isDigit` two, and a table cannot beat that.
The same arithmetic says why the table wins for `isIdent`: the alternative was twenty comparisons deep for a quarter of real input.
`isHexDigit` is six comparisons on the escape path only.

The serializer had the same predicate, ordered the same way wrong.
`Escaping.isNameChar` tested `-`, `_` and the digits ahead of the lowercase letters.
Counted over the corpus, of all name characters: lowercase is **83.0%** on Bootstrap and **75.0%** on Tailwind, hyphens 11.1 and 12.8, digits 5.7 and 12.2, uppercase **0.02%**.
It now delegates:

```java
private static boolean isNameChar(int codePoint) {
    return codePoint < 0x80 && CodePoints.isIdent(codePoint);
}
```

The explicit ASCII gate is the point of that line, not a redundancy.
Both callers dispatch `>= 0x80` above it, so `isIdent`'s non-ASCII half is unreachable today.
But `isNameChar` must answer _false_ for section 4.2's non-ASCII name characters and `isIdent` answers _true_, so a future caller skipping the dispatch would get a silently different answer.
One comparison buys the narrower contract, and the JIT folds it against the identical test inside `isIdent`.

This is what made `CodePoints` `public`.
`lexer` is not exported, so the widening is module-internal; the alternative was a third copy of a predicate that already existed twice.

### Building a child list once

Every AST record takes a `List` and its canonical constructor calls `List.copyOf`, which is what makes the node immutable when a caller builds one by hand.
The parser was that caller 20,000 times per Bootstrap parse, and the path cost far more than the result: an `ArrayList`, its backing array (twice over when the default capacity of ten was not enough), the array `toArray()` copies out of it, a _second_ array because `List.of(E[])` will not adopt an array it did not create, and the list object.
Measured on a three-element list, **144 bytes for a result that occupies 56**.

The way out is that `List.copyOf` returns its argument unchanged when the argument is already one of `List.of`'s own implementations.
So nothing about the records changes, since a caller handing one a mutable list still gets a copy, and the copy costs nothing when the parser hands in a finished immutable list.
The public surface is untouched.

`NodeStack` is the scratch space that makes that possible: one `Object[]` per parse, which both parsers push onto and take finished lists off.
Same shape of decision as `TokenBuffer`, one array and no per-item object, one level up.

The ten-argument ladder is the load-bearing detail, and it was measured rather than assumed.
`List.of` has a fixed-arity overload for every length up to ten, each adopting the varargs array the compiler creates at the call site; past ten only the array-taking overload exists, and it copies:

|                 | lists   | <= 10 values |
|-----------------|---------|--------------|
| Bootstrap 5.3.3 | 19,943  | 99.4%        |
| Tailwind 2.2.19 | 279,114 | 99.8%        |

So the ladder covers essentially everything and the over-ten fallback pays one copy on a rounding error.
A `Stylesheet`'s own child list is the one guaranteed member of that tail, once per parse.

The cost this adds, since it is the one thing the change makes worse: the stack's high-water mark is not the nesting depth but the sum of every list open along the current path, which the stylesheet's own child list dominates.
Tailwind's has 39,105 entries, so the stack doubles from 128 up to 65,536 slots and allocates roughly 524 KB of arrays doing it, half discarded on the way.
Against a 99 MB parse that is half a percent, and it buys the other 21%, but sizing the initial capacity from the token count, known before parsing starts, would take most of it back if the tail ever matters.

What it costs is a discipline with exactly one hazard.
Every push must be matched by a take or a reset against the same mark.
Both parsers are recursive descent and finish innermost-first, so the nesting is honest, but a selector that gives up half way has already pushed, and anything it leaves behind would be swept up by the next list to close and land in a rule that had nothing to do with it.
`SelectorParser` funnels all seven abandonment paths through one `unwindIfNull`, and `CssParserTest.AbandonedBuilds` pins the four shapes that reach it.

_The first attempt at guarding this was worthless._
The assertion sat at the end of `parseStylesheet` and checked that the stack came back empty, and it cannot fail, because a leak does not survive to be seen there: the enclosing list's `take` sweeps the stray values up as its own children and the stack balances anyway.
The symptom would have been a neighbouring rule silently growing a child, with the assertion green.
The check has to be per-build, next to the paths that can leak.

Verification was identity, not just green tests.
200,000 differential-harness samples across five seeds under `-ea`, byte-identical to a stashed build; and all three corpus entries byte-identical across all four serializer option sets plus the optimizer path, about 18 MB of output.
The four new tests were then mutation-checked by disabling the unwind, which failed all four, worth doing here because a leak is invisible by construction: the values reappear as somebody's children rather than as an error.

|                            | allocation before | after     |            |
|----------------------------|-------------------|-----------|------------|
| `parse` SMALL              | 0.138 MB          | 0.110 MB  | -20.0%     |
| `parse` MEDIUM             | 9.591 MB          | 7.288 MB  | **-24.0%** |
| `parse` LARGE              | 125.852 MB        | 98.899 MB | -21.4%     |
| `parseText` MEDIUM         | 9.029 MB          | 6.726 MB  | -25.5%     |
| `parseAndSerialize` MEDIUM | 12.806 MB         | 10.517 MB | -17.9%     |
| `tokenize` (all three)     | n/a               | n/a       | **-0.00%** |

`tokenize` came in identical to three decimals on all three entries, as did `DecodeBenchmark` and `TokenizeBenchmark.scan`.
That is the control: this change is confined to the parser, so a tokenizer number that had moved would have meant something unintended did.
Serializing moved by +0.2 to +0.3%, at the edge of what this metric resolves.

The retained figure did not move at all.
`memoryCensus` reports 2,276,960 B, byte for byte what it reported before.
A corroboration rather than a disappointment: the finished lists are the _same objects_ they always were, since `List.copyOf` produced a `List12` for one or two elements and a `ListN` beyond that, and so does the ladder.
Everything saved was scaffolding thrown away during the parse.

Time also improved, and the honest version is softer than the numbers look.
Every parse benchmark got faster, `parse` MEDIUM 2192.8 -> 1978.7 us, -6% to -11% across all six.
But the same run has `DecodeBenchmark.decodeUtf8` on LARGE _slower_ by 9.9% and `SerializeBenchmark.minified` on MEDIUM slower by 20.5%, neither code this change touched.
**Read it as: allocation fell 21-25%, measured; time consistently improved by something in the 5-10% range, not established to that precision.**

### AST memory shape

Two different questions.
`gc.alloc.rate.norm` says what a parse _allocates_; `./gradlew memoryCensus` says what it hands back still _holding_.
They differ by more than a factor of three, since parsing Bootstrap allocates 7.29 MB and retains 2.28 MB, so an optimization aimed at one is not automatically one for the other.
[Building a child list once](#building-a-child-list-once) is the sharpest illustration: it took a quarter off the first number and left the second byte-for-byte unchanged.
The census is a size model (64-bit, compressed oops, 8-byte alignment); the profiler is a measurement.

Strings are duplicated ninefold, and interning them is a trade, not a win.
The tree holds 20,990 string references across Bootstrap and 299,192 across Tailwind, of 3,247 and 39,837 distinct texts, 85-87% duplicates.
`TokenBuffer` interns them through an open-addressed table keyed by hashing the character range and comparing against it without allocating, scoped to one parse so it dies with the buffer and needs no thread-safety.
That is not `String.intern()`, which would put every identifier in every stylesheet into a JVM-wide table for the life of the process.

What the estimate had not said: it costs time.
Allocation in a young generation is a pointer bump, while a hit here is a hash and a comparison.
Measured on Bootstrap against no interning:

| cap       | allocation | time   |
|-----------|------------|--------|
| 16 chars  | -8.0%      | +3.0%  |
| 128 chars | -9.4%      | +10.0% |

So the cap is 16: everything longer buys 1.4% more allocation for 7% more time, because long values repeat less and cost more to hash when they miss.
Sixteen is also where CSS property names sit, since `background-color` is exactly sixteen.
The census reports the cap's cost in the open: 5,503 string instances for 3,247 distinct texts.

`SourceSpan` was the largest single line item, and it is packed.
It was 38,399 objects and 922 KB on Bootstrap, 614,000 and 14.7 MB on Tailwind: two `int`s in a separate 24-byte object, referenced from every node.
Every record's span component is a `long` now, and `Node.span()` builds the record on demand.

Retained on Bootstrap, before packing (the interning reasoning above is measured against these) and after:

|         | objects | bytes   | share              |
|---------|---------|---------|--------------------|
| records | 76,798  | 1.92 MB | 62%                |
| lists   | 19,851  | 832 KB  | 27%                |
| strings | 5,503   | 336 KB  | 11%                |
|         |         | 3.09 MB | **11x the source** |

|         | objects | bytes   | share               |
|---------|---------|---------|---------------------|
| records | 38,399  | 1.11 MB | 49%                 |
| lists   | 19,851  | 832 KB  | 37%                 |
| strings | 5,503   | 336 KB  | 15%                 |
|         |         | 2.28 MB | **8.1x the source** |

Lists and strings are byte-for-byte what they were; the entire saving is the span objects that no longer exist, less the 4 bytes per node a `long` costs over a reference.
That last term is why the saving is 26% and not the 30% first predicted: the earlier estimate counted the span objects removed without counting the `long` added back.
25% was predicted and 26% landed, where an older note had called it 30%.
In bytes the retained tree went from 3,091,506 B to 2,276,960 B.

Allocation fell **9.5%** with it, Bootstrap from 10.60 MB to 9.59 MB, against a prediction of 16% that came from a JFR _sample_ of the ten largest classes rather than a measurement.
Time did not move: 2.193 ms against the 2.2 ms quoted, and the pipeline 3.317 ms against 3.3.
**Tokenizing came in identical, at 3.66 MB to three figures**, which is the control, since packing touched the parser and the AST records rather than the tokenizer.

Packing touches every record, every test that builds one, and the canonical constructors, and it cost 32 records plus a mechanical pass over the parser.
What made it affordable is that nothing in the library reads a span's components: every use in `src/main` is a pass-through, so the packed form moves without ever being unpacked.
`TokenBuffer.packedSpan` exists so the parser never materializes a span it is about to pack.
The cost is in the open: `Declaration.toString()` prints `packedSpan=17179869185`, and a record deconstruction pattern must bind a `long` where it used to bind a `SourceSpan`.
Value classes would make `SourceSpan` free to hold directly, at which point the `long` could go back to being a record component without changing a caller.

Rejected: a specialized `CharSequence` over the source buffer, in the shape of the ClassFile API's `Utf8Entry`.
Costed on Bootstrap at 20,990 24-byte views plus the 562 KB decoded `char[]` they pin, 1.07 MB against the 1.11 MB plain strings cost before interning, a 4% saving.
Against interning as built it is three times worse, 1.07 MB versus 336 KB, because once the duplication is gone the retained buffer is the entire cost.
It would also reintroduce the source-buffer retention [already declined](ARCHITECTURE.md#serializer--output-modes) for byte-exact round-tripping, and put `CharSequence` in a public API where records stop comparing structurally, `switch` over a property name stops compiling for the caller, and every `Set<String>` name lookup needs a key-compatible replacement.

The analogy does not carry: a class file's constant pool is _already deduplicated by the compiler that wrote it_, so entry count is distinct count and lazy inflation is the only win left.
CSS has no constant pool; our equivalent is the intern table above.

---

## Serializing

### Pretty-printing stopped allocating its indents

`CssWriter.indent` wrote `INDENT.repeat(depth)`.
`String.repeat` returns `this` for a count of one and `""` for zero, so depths 0 and 1 were free, but **every declaration inside an `@media` is at depth 2**, and real dist CSS is mostly `@media`.
An append loop allocates nothing at any depth and needs no static state.
A cache would have added a lookup and a lifetime question to buy back an allocation that need not happen.

`SerializeBenchmark.pretty`, allocation:

| corpus | before      | after      |                                                 |
|--------|-------------|------------|-------------------------------------------------|
| SMALL  | 28,600 B/op | 25,888     | **-8.9%**                                       |
| MEDIUM | 2,488,936   | 2,392,983  | **-3.9%**                                       |
| LARGE  | 17,817,960  | 15,418,244 | **-13.5%**, 2.40 MB off a Tailwind pretty-print |

`pretty` is the only serializer benchmark that is not `MINIFIED`, so it is the only one that reaches `indent`, and the other four moved by nothing.
That is the control that makes the attribution safe rather than plausible.

### Stream pipelines on recursive and per-item paths

Two of these, found by the same sweep, and the second is worth four times the first.

`Flattener.emit` computed its `declares` predicate with a stream pipeline, eagerly, ahead of the `selectors == null` short circuit that can skip it.
It is an indexed loop behind that check now: `flattened` **-1.11%** and `legacy` **-2.06%** on SMALL, with `parseAndSerialize` and `parseOptimizeAndSerialize` at -0.68 / -0.48%, and every benchmark in that list flattens, while `pretty`, `minified` and `asciiEscaped` do not and did not move.
MEDIUM and LARGE are flat to 0.00%.

`Selector.containsNestingSelector` was three implementations, all streams, and _mutually recursive_: `ComplexSelector` per step into `CompoundSelector`, `CompoundSelector` per simple into `PseudoClassSelector`, and back round into `ComplexSelector` for a functional pseudo-class's argument list.
One question about one selector allocated a pipeline per selector, per compound inside it, and again per argument list, and `NestingExpander` asks it at four sites.
Indexed loops instead:

| SMALL       | before   | after    |           |
|-------------|----------|----------|-----------|
| `flattened` | 48,024 B | 46,064 B | **-4.1%** |
| `legacy`    | 36,544 B | 33,288 B | **-8.9%** |

Roughly four times what the single-pipeline fix was worth, which is the shape the recursion predicts.
_Allocation only: three quick runs of `flattened` read 12.9, 14.1 and 11.3 us, a 25% spread, so no timing is claimed._

Two stream pipelines were left alone.
`Selector.specificity` and the `declarations()` / `rules()` accessors on `StyleRule`, `Stylesheet` and `ConditionalGroupRule` are stream-and-`toList`, and nothing inside the library calls any of them.
The CLI reads `sheet.rules()` once per file and is the only caller in the repository.
They are convenience for a consumer, and pre-optimizing against an unmeasured caller is not worth it.

### The largest allocation term nothing has touched

`SerializeBenchmark.minified` allocates **15.48 MB on Tailwind** to produce a few megabytes of CSS, identical in every run.
That is the `StringBuilder` growth series plus the final `toString`, and every `CssSerializer.serialize` overload returns a `String`.
It is the same shape `SourceMap.toJson` had before `writeJson(Appendable)` cut it by [a factor of 169](#tojson-and-the-two-things-measuring-it-found), and unlike that case it is on the path every caller walks.
Recorded rather than proposed: an `Appendable` overload is a public addition.

---

## Bundling

### Bundling allocates the sum of its sources' parses and nothing more

No rebase, no intermediate tree.
`BundleBenchmark` holds that claim, and it holds.
The graph is a corpus entry cut into pieces at top-level node boundaries rather than a corpus entry repeated, so the total text stays the corpus size and the numbers sit beside `ParseBenchmark.parse`.
Balancing the cut by bytes rather than by node count is not a detail: Tailwind's 6618 top-level nodes are thousands of small ones followed by five `@media` blocks of roughly 613 kB each, so an even cut by count gives fifteen scraps and one 3.1 MB source.

Against a control that parses the same sources one at a time:

|                                              | SMALL       | MEDIUM      | LARGE       |
|----------------------------------------------|-------------|-------------|-------------|
| bundling over the sum of the parses          | 5.82%       | 4.89%       | 4.13%       |
| ...of which **the bundler's own structures** | 2.39%       | 0.61%       | **0.47%**   |
| ...and the rest, the retained source text    | 1.00 B/char | 1.00 B/char | 1.00 B/char |

The last row is as exact as it gets: on LARGE the pairing-without-the-bundler sits **1.00015 bytes per source character** above the control, which is one compact Latin-1 string per source and nothing else.
The segment table, the combined child list and the walk for `@import` and `@charset` are the claim, and the claim is right.
SMALL's 2.39% is per-source constants spread over nine sources of a few hundred bytes.

Getting there needed the gap attributed, and the first version of the benchmark could not do that.
Bundling first read about **12% above the sum of the parses**: no second tree, but not nothing either, and none of it the bundler's.
Three rungs could show the gap and not whose it was, which is an invitation to blame the wrong thing.
A middle rung doing exactly what `Bundler` does per source _minus the bundler_ is what separated them.

The 12% was three bytes per source character, and it was the entry point rather than the design.
`CssParser.decode` returned a `String`, one byte per character while it stays Latin-1, and `CssParser.parse(CharSequence, int)` copied that into a fresh `char[]`, two more.
Both halves measured 3.00 B/char on MEDIUM and on LARGE, which made it arithmetic rather than a hypothesis.

Only one of the three bytes was waste.
The `String` is _retained output_: [the index has to be given the same text every span indexes into](BUNDLING.md#the-trap-this-creates), `sourcesContent` wants it again, and a compact string retains **half** what the `char[]` underneath would.
The second `char[]` was waste, since the `SourceText` built inside `decode` already held one.

Closed by `CssParser.decodeSource` and `CssParser.parse(DecodedSource)`, a public addition to `parser` that hands back the decoded buffer instead of a string.
`Bundler` keeps it, parses from it, takes `text()` off it for the index, and reads `encoding()` from it too, which removes a second sniff over the bytes for a BOM and an `@charset` the decode had already found.
**Every bundle allocates 2.0 B/char less: -6.81% on MEDIUM and -6.78% on LARGE.**

One asymmetry the addition creates: **`parse(DecodedSource)` does not report the charset fallback**, because `decodeSource` took the sink and already did, and repeating it would give a bundle two warnings for every source read in the wrong encoding.
`CssParserTest.DecodedSources` pins that, along with the tree being identical to the string pairing's, span for span.

### Bundling is not faster than parsing; the single-file path is penalized

One thing the measurements turned up that the design did not ask for, and it is a property of the JVM rather than of cassette.
Parsing Tailwind as one 3.6 MB file cost **41.7 ms**; parsing the same bytes as the seven files `BundleBenchmark` cuts it into cost **21.5 ms**, 1.9x apart, for allocation identical to within 0.4%.

_The first reading of this was wrong, and the wrong version is the tempting one._
The GC counters differ sharply, 4,118 collections and 4,821 ms against 967 and 572, which reads like collection cost until the units are checked.
Those are totals over a 200-second measurement, not per operation: **2.4% of wall time against 0.29%**, about two points of a ninety-four point gap.

**The cause is G1 humongous allocation, in the mutator, and it is `TokenBuffer` rather than the decoded text.**
Seven parallel arrays totalling 12.8x the source length mean every one is humongous for a 3.6 MB stylesheet, while the seven chunks here never produce an array over 1.4 MB.
[The write-up is above](#a-multi-megabyte-stylesheet-costs-g1-roughly-double-and-it-is-the-token-buffer): the arithmetic, how the threshold moves with heap size, what has _not_ been established, and why FFM is ruled out.
Shrinking the buffer has since closed most of the gap.

The consequence for a caller is one sentence: a bundle of ordinary-sized partials quietly avoids a cost that one concatenated megabyte-plus stylesheet would pay.

---

## Source maps

### What a map costs

`SourceMapBenchmark` has four cases, each isolating one term.
_Both estimates in the design were low by three to four times._
It guessed a map with content at **+30% on the serialize step and +10% on the pipeline**.
Measured, minified, from a full run:

|           | serialize step | whole pipeline |
|-----------|----------------|----------------|
| estimated | +30%           | +10%           |
| Bootstrap | +133%          | **+30%**       |
| Tailwind  | +131%          | **+17%**       |

So a map costs roughly **2.3x** what serializing allocates.
That is fine, being a development build's cost with the CSS byte-identical either way, but the reason the estimate missed is instructive: it counted the map's _output_ and forgot the per-mapping work that produces it.

_The table read +186 / +181% and +42 / +24% until it was requoted from a full run, after the `toJson` buffer estimate was fixed._
Which of the two moved it is not a guess, because the benchmark separates them.
`withMap` builds a map and does not render it, and its figure is unchanged between the two measurements, at 884 kB against the 903 kB the build-term table below sums to on Bootstrap, 10.67 MB against 10.84 MB on Tailwind.
Everything that moved is in `toJson`, all four ratios fell by the same factor (0.71-0.72), and `toJson` on Tailwind allocates **9,564,167 B** here against the **9,564,600 B** [measured directly for it below](#tojson-and-the-two-things-measuring-it-found), 433 bytes apart by two different methods.

`sourcesContent` is the dominant term, as claimed, but not by the margin the JSON suggests.
It is **87% of the map's JSON** and **42-64% of the map's allocation**, two different denominators, both true, and worth keeping apart.
It is nonetheless **88-95% of what `toJson` allocates**, which is the number that matters to a caller rendering one.
Dropping it is one record and no serializer option, so `--no-source-map-content` removes the largest single term rather than a convenience.

**Read every figure here as a difference taken inside one run.**
`minified` builds no map, yet it has moved 409,672 B between runs on code nothing touched, which is one grow-copy of a 204,836-char buffer and the [cross-run allocation multi-modality](../benchmarks/README.md#reading-a-result) at forty times the magnitude usually recorded for it.
`withMap` carries the identical term in the same run, so the subtraction cancels it exactly: `minified` +409,672 against `withMap` +271,748, and the difference is the 137,924 the first change saved.
The same subtraction reads 767,300 in a full run and 767,332 in a quick one, **32 bytes apart across measurement modes**.
_The later run needed none of that machinery, because its control did not move at all: the reason to take differences inside a run is that you cannot know in advance which kind of run you are in._

### Locating allocated 64 bytes per mapping, and now allocates none

Nothing predicted this, and it was the biggest part of _building_ a map, larger than the arrays the design sized so carefully.
The table is the state this section was written in; all of it is gone now.

| building a map, by term                | Bootstrap       | Tailwind         |
|----------------------------------------|-----------------|------------------|
| what `tryLocate` allocated per mapping | **414 kB, 46%** | **4.68 MB, 43%** |
| the two mapping arrays at capacity     | 169 kB, 19%     | 2.19 MB, 20%     |
| the VLQ string                         | 50 kB           | 567 kB           |
| everything else                        | ~270 kB         | ~3.4 MB          |

Three allocations, none of them visible as one at the call site:

| per mapping                       | B      | why it was on the heap                                      |
|-----------------------------------|--------|-------------------------------------------------------------|
| the `Optional` from `tryLocate`   | 16     | allocated inside a `try`                                    |
| the `Location`                    | 32     | allocated inside a `try`, _and_ captured by a lambda        |
| a lambda capturing the `Location` | 16     | `computeIfAbsent(id, id -> new LineIndex(at.sourceText()))` |
|                                   | **64** |                                                             |

`MapPass` reached its per-source `LineIndex` through `computeIfAbsent`, and the lambda it passes captures `at`, so a capture is an allocation on every mapping, whether the map hits or misses.
And `SourceResolver.tryLocate`'s default form wraps the `Location` in a `try`/`catch`: **an allocation inside a `try` is not scalar-replaced**, so both it and the `Optional` around it reached the heap on every call, every _successful_ call included, since the exception has nothing to do with it.

Removed in two independent changes, each confirmed by its own full run:

| the build term, Bootstrap                            | B       | per mapping   |                   |
|------------------------------------------------------|---------|---------------|-------------------|
| before                                               | 905,224 |               |                   |
| `MapPass` uses `get` instead of `computeIfAbsent`    | 767,300 | -16 B         | **-15.2%**        |
| `SourceResolver.of` range-checks instead of catching | 353,416 | -48 B         | **-53.9%**        |
|                                                      |         | **-64.000 B** | **-61.0% in all** |

That last column is an exact byte count, not a rounded one.
905,224 - 353,416 = 551,808 = 64.000 x 8,622 mappings, and the second measurement reads its 48 B half over a control that moved **exactly zero bytes**: `minified` on Bootstrap is 2,510,625 B in both runs.
Three map benchmarks moved by the same figure to within 28 bytes.
SMALL agrees independently at 4,752 B over about 99 mappings; LARGE carries exactly one 70,272-byte step of the usual drift and puts Tailwind at about 95,300 mappings.
A later run reproduced the result byte-for-byte on two of three sizes.

**The `try` is the pin, and that is isolated rather than inferred.**
The second change moved two things at once, the catch and `of` returning an anonymous class where it had returned a lambda, so the control is the same anonymous class with the `try`/`catch` body put back: **767,277 B**, which is the inherited default's figure to within 55 bytes.
Body, not shape.

So the arrays are the first term now, which is what the design expected all along, and the `SourceResolver` question that had been parked closes without touching the interface.
`of`'s signature, `tryLocate`'s contract and the default method are all unchanged.
What changed is that the one implementation the library ships answers the range question by arithmetic instead of by catching what `Location`'s own constructor throws.
`SourceIndex` already did.

And the method lesson has two halves, the second earned the hard way.
A term measured in total and _attributed_ by arithmetic is two claims wearing one number's clothes, and only the total had ever been checked, which is how the recorded figure came to be 48 B rather than 64, short by one object nobody had counted.
But the first attempt to fix that replaced a correct attribution with a wrong one, on the strength of a microbenchmark that reported the `Optional` free, because the micro's locator allocated outside a `try` and the real one did not.
**A model differing from the code in one unnoticed way is worse than no model**, since it produces a confident number.
What settled it was measuring the real path, twice, with a control that isolated the single line responsible.

_And "a map allocates its arrays once" is a test rather than a benchmark._
It is a question about the estimate rather than about cost, and a benchmark can only show total allocation, so `CssWriterMappingTest.Sizing` asserts the arrays do not grow on a nested authored sample, and that the sample really is denser than the corpus.
Exactly the pair `TokenBufferTest.Sizing` holds.

_A retained `SourceMap` with content is still at least 1x the source on top of the [8.1x tree](#ast-memory-shape), which is unmeasured: `memoryCensus` covers a parsed tree and not a map._

### `toJson`, and the two things measuring it found

**The buffer estimate was short on every corpus entry**, by 3.7% to 5.6%, which doubles a multi-megabyte builder and copies everything written so far.
Exactly the token buffer's mistake in a second place, and found by measuring the cost rather than by any test.
What it forgot is escaping: the mappings string cannot expand, being base64 and punctuation, but source content can, and CSS is full of the one character that does it.
Headroom is a quarter of the content now.
A tenth was the first guess and newlines alone are 4-5% of formatted CSS, but a tab-indented stylesheet carrying `content: "..."` strings reaches **19%**, four newlines, two quotes and a tab in every 37 characters.
Tailwind went from 3.8x its output in allocation to 2.2x.

And then `writeJson(Appendable)` removed the buffer entirely.
2.2x is the floor for returning a `String`, a builder plus the string copied out of it, so the way below it is not to build one:

| writing Tailwind's map to a file | allocation   |
|----------------------------------|--------------|
| `toJson()` then write the string | 9,564,600 B  |
| `writeJson(writer)`              | **56,576 B** |

A factor of **169**, and Bootstrap is 3.26 MB against 28.5 kB.
Byte-identical output, verified on both entries and pinned by a test, because `toJson` delegates and the two must never drift.

The obstacle was one line.
`Json` decided whether a member needed a comma by reading the last character back off the buffer, `charAt` against `'{'`, which is a trick only a `StringBuilder` allows, and it was the single thing stopping a map from being written anywhere else.
Holding that answer in a field instead also retires the objection the static version had recorded against a flag: the state is one field on one object, not something every call site has to remember to update.

_The API is additive._
It is here because the CLI is the only caller that exists and it wanted the map in a file, so the round trip through a `String` was pure waste on the one path a user walks.

---

## Method

### `Optional` at a returning boundary costs nothing, unless the site sees both answers

_Measured by `OptionalWrapperBenchmark`, from a question about whether the library's two exported `Optional`-returning boundaries, `Importer.resolve` and `SourceResolver.tryLocate`, should return a bare reference instead._

They should not, and the reason generalizes past this library.
The wrapper's cost is a property of the _call site_, and the benchmark reads it off three sites differing in nothing but how often they resolve:

| the site sees      | B per call | why                                                                                |
|--------------------|------------|------------------------------------------------------------------------------------|
| every call present | **0**      | C2 scalarizes it; the paired nullable arm is byte-identical                        |
| every call empty   | **0**      | `Optional.empty()` is a singleton and allocates nothing                            |
| both, hot          | **16**     | a phi merges that singleton with a fresh allocation, so the fresh one materializes |

**With one condition that is not in the table: none of those zeroes survive a `try`.**
An allocation inside a `try` block is not scalar-replaced, so a wrapper built there is on the heap whatever the site's shape, and the exception has nothing to do with it, since successful calls pay too.

Every figure is exact at full rigor, with a `scoreError` of **zero** on all fifteen rows.
This is structural, not statistical.
**So the interesting number is not 16 bytes, it is which row a real call site lands on.**
A `Bundler` resolving from one importer, or a `MapPass` walking one resolver over one source, is the first row and pays nothing; a bundle that declines some imports is the third and pays 16 B per `@import`.
**`Importer` keeps its `Optional`.**

Read this as the escape-analysis contrast that `-XX:-DoEscapeAnalysis` was going to be used for.
The unimodal-versus-bimodal rows give the same contrast _in the configuration that ships_, which is strictly better evidence, and the flag was never needed.

**The finding that paid for the trip was the second boundary, where the answer was 64 bytes a mapping and every one came off**, written up [above](#locating-allocated-64-bytes-per-mapping-and-now-allocates-none).

The cautionary half is about how to use a microbenchmark.
The first attempt replaced a _correct_ attribution with a wrong one on the strength of the table above: the micro reported the wrapper free, so the wrapper was declared innocent, but the micro's locator allocated outside a `try` and the shipping one did not.
What settled it was the real benchmark plus a control that changed one line, the same body with the `try` restored, reading 767,277 B against 353,416.

### The allocation-shape sweep, and what it did not find

*After the finding above turned up 64 bytes per mapping that were invisible as allocations in the source, the question was whether the same shapes occur elsewhere: an allocation inside a `try`, and a lambda capture on a per-item path.
The negative results are recorded because they are what stops this being done again.*

**Allocations inside a `try`: there is no second instance.**
`src/main` contains **four** `try` blocks in total:

| where                                   | verdict                                                                                                                                  |
|-----------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------|
| `SourceResolver.tryLocate`, the default | the one that cost; `of` and `SourceIndex` both override it now. The default stays, being the only form correct for an arbitrary `locate` |
| `SourceText.decodeCharset`              | allocates a `char[]` inside the `try` and **returns** it, so it escapes regardless. Once per source                                      |
| `SourceMap.toJson`                      | the `StringBuilder` is allocated before the `try` and returned. Once per map                                                             |
| `CssEncoding.resolve`                   | once per distinct charset label, memoized after                                                                                          |

**Lambda captures on a per-item path: none.**
Every lambda and method reference in `src/main` is either **non-capturing** (a static or unbound instance method reference, or a body closing over nothing, all handed out as singletons) or allocated **once per source, parse or transform**.
`Optimizer`'s `computeIfAbsent(type, ignored -> new ArrayList<>())` looks exactly like the one that cost and is harmless on both counts.
`Bundler`'s `this.diagnostics::add` does capture, and is one object per source read.

**And the `Optional` inventory is complete: two boundaries, both settled**, plus the two overrides that answer without catching.

What the sweep found instead was the [recursive stream pipeline](#stream-pipelines-on-recursive-and-per-item-paths) above.
