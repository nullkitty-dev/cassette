# Differential fuzz harness

Two plain `main` classes, deliberately outside every Gradle source set, so nothing here is compiled by `./gradlew build`, and deleting the directory costs the build nothing.

They exist because the jqwik idempotence property started failing on inputs it had never drawn before, and the only question that mattered was _did this change cause it_.
Reading the shrunk sample could not answer that: jqwik renders a sample with the escapes already applied, so a backslash-newline and a backslash-space print identically, and two attempts to reconstruct one by hand both produced inputs that were idempotent.

The harness answers it directly.
It generates the same inputs against two builds and diffs the output.

## Running it

```sh
# the build under test
./gradlew compileJava
javac -cp build/classes/java/main -d /tmp/cur tools/differential-fuzz/*.java

# whatever you are comparing against
git worktree add /tmp/baseline <ref>
javac -d /tmp/baseline-classes $(find /tmp/baseline/src/main/java -name '*.java')
javac -cp /tmp/baseline-classes -d /tmp/base tools/differential-fuzz/*.java

# same seed, same count, both sides
java -cp build/classes/java/main:/tmp/cur      Fuzz 42 30000 > /tmp/cur.tsv
java -cp /tmp/baseline-classes:/tmp/base       Fuzz 42 30000 > /tmp/base.tsv

grep -c NOTFIX /tmp/cur.tsv /tmp/base.tsv   # non-fixed-points on each side
diff /tmp/base.tsv /tmp/cur.tsv             # every behavioural difference
```

`Fuzz` covers the four `SerializerOptions` sets the property uses; `FuzzOpt` covers the `Optimizer` path, reaching the class reflectively so the same source runs against a tree from before the `Minifier` rename.

Output is one TSV row per sample: the input, then each mode's first serialization and whether it was a fixed point.
Two things are worth looking for, and they mean opposite things: `NOTFIX` rows are bugs on that side, while rows that _differ between the two files_ are behavioural changes, which may be intended.

## Why the generator is a list of fragments

`PIECES` is concatenated at random rather than generated from a grammar.
Everything interesting here lives at the seams (a bad token next to whitespace next to a closer, an at-rule prelude that turns out to write nothing), and gluing hostile fragments together reaches those seams far faster than a generator that produces well-formed CSS and then corrupts it.

The list is worth extending whenever a new defect is found, and extending it pays immediately: adding the `@a`, `@a `, `@a \`, `url(x` and `@a url(` fragments took the count against a tree with a known defect from 0 non-fixed-points across 200k samples to roughly 15 per 30k, all of them real.
A harness that reports zero is only as trustworthy as the shapes it generates.

## Seams are fragments, not atoms

Everything in `PIECES` up to the last block is an _atom_, and concatenating atoms reaches a seam only by accident.
The last serializer defect needed `c`, whitespace, `url(` and `"` in that order: four picks from a seventy-entry pool at one to six pieces per sample, and 200,000 samples never produced it.
It had to be found by reasoning about the code instead.

So the shape every defect either generator has ever found is now a fragment in its own right: **something that writes text, then whitespace, then something that writes nothing.**
The values that write nothing are a bad-string, a bad-url and a lone `\` delimiter, joined by a `url()` whose first argument is not a string; the prefixes written ahead of them are a separator, an indent, the space before an at-rule prelude and an opening brace.
The seam block crosses the two, with both open-ended forms and closed ones that do not depend on the fragment drawn next.

Measured against a stashed build that still has the unspellable-`url()` defect:

| pool       | against the defect | on the current build |
|------------|--------------------|----------------------|
| atoms only | 2 per 200,000      | 0                    |
| with seams | 15-20 per 30,000   | 0 across 320,000     |

Roughly a 200x improvement in sensitivity against a defect known to be there, which is the only way to read a zero on the current build as evidence rather than as silence.

`CssLikeArbitraries` carries the same list, and `CssLikeArbitrariesTest.mostInputCarriesASeam` asserts jqwik still draws it, at 83% of samples.
Keep the two lists in step; a defect found here should be reproducible there, and a pool that stops covering a shape reports zero failures exactly as convincingly as one that covers it and finds nothing.
