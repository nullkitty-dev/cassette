# Fixtures

One directory per case.
Loaded by `dev.nullkitty.cassette.fixtures.FixtureLoader`.

```
<fixture-name>/
  input.css                 required - read as raw bytes, BOM included
  SOURCE.md                 optional - provenance and license, required for vendored cases
  expected/                 optional - omit entirely for input-only cases
    tokens.txt
    <variant>.css
```

A directory holding **`sources/` instead of `input.css`** is a bundle fixture:

```
<fixture-name>/
  sources/                  every file, read as raw bytes; its id is its path under sources/
    index.css
    base/buttons.css
  entry.txt                 the sources to bundle, one per line, in cascade order
  expected/
    bundle.txt              tree + diagnostics + segment table
    <variant>.css
```

`entry.txt` ignores blank lines and `#` comments.
Which marker file is present is the whole difference: everything under `expected/` means what it always meant, because a bundled tree is an ordinary `Stylesheet` and the four serializer axes select the same four things.

## Variant names

A variant is the expected file's name, extension included.
The loader parses nothing about it, so a fixture can assert whatever a case needs.

| variant                             | asserted by             | what it covers                            |
|-------------------------------------|-------------------------|-------------------------------------------|
| `tokens.txt`                        | `TokenizerFixtureTest`  | tokenization                              |
| `ast.txt`                           | `ParserFixtureTest`     | the tree and its diagnostics              |
| `<nesting>.<min>.<compat>.css`      | `SerializerFixtureTest` | serializer output                         |
| `bundle.txt`                        | `BundleFixtureTest`     | a multi-source tree and its segment table |
| `<nesting>.<min>.<compat>.map.txt`  | `SourceMapFixtureTest`  | source maps, decoded                      |
| `<nesting>.<min>.<compat>.map.json` | `SourceMapFixtureTest`  | source maps                               |

`bundle.txt` is `ast.txt` plus a segment table, and only a bundle fixture has one.
The table is there because **tree order and span order diverge in a bundle**: a tree is in cascade order and the coordinate space is in decode order, so a hoisted `@import` can be the first child of the tree and start halfway through the offsets.
A dump showing only spans would read as though the tree were shuffled.

`.map.txt` is a source map **decoded**, one line per mapping:

```
outLine:outCol -> file:line:col  «what the source says there»
```

Raw is what `.map.json` is for, and exactly one fixture has one.
The reason for the split is that the diff is the review artifact: a `mappings` string is a wall of base64 whose diff says nothing, while a decoded dump makes a moved mapping legible.
The one JSON case pins the encoder and the JSON writer themselves; everything else is checked through a decoder written against the format rather than against the encoder.

Both take the same three axes, so `SerializerFixtureTest.optionsFor` remains the one place that maps a name to options.

For CSS output the convention is `<nesting>.<minification>.<compat>`:

| axis         | values                   |
|--------------|--------------------------|
| nesting      | `nested`, `flattened`    |
| minification | `unminified`, `minified` |
| compat       | `modern`, `legacy`       |

Only write the combinations actually being asserted.
A missing file means "not asserted"; if a combination is _intentionally_ untested, say so in `SOURCE.md` rather than leaving it to be guessed at.
When a fourth axis appears, it joins the name, since the loader parses none of it.

Asserting a new combination is two steps: create the empty file, then regenerate.
An empty expected file is a request for output, not an assertion that the output is empty.

The optimization transforms are deliberately _not_ an axis here.
They are opt-in, each independent of the others, and asserting them as whole-file output would say less than the per-transform cases in `OptimizerTest` do.

## Token dumps

`tokens.txt` is one line per token: type, span, raw text in `|...|`, then only the fields that carry information.

```
DIMENSION   14..20     |.500em| value=|em| number=0.5
BAD_STRING  76..90     |"unterminated;| value=|unterminated;|
COMMENT     50..76     |/* unterminated comment\n}\n| value=|...| unterminated
```

Generated from the fixture's raw **bytes**, so charset detection and section 3.3 preprocessing are part of what these assert: spans are offsets into the decoded, preprocessed buffer and will not agree with byte offsets in a file containing a BOM or CRLF.

## AST dumps

`ast.txt` is the parsed tree, one node per line, indented by depth, each with its span.
A `diagnostics` section follows the tree whenever the parse produced any:

```
Stylesheet 0..24
  StyleRule 0..24
    SelectorList 0..5
      Complex 0..5 (0,1,0)
        Compound 0..5
          Class |card|
    Declaration |color| 8..18
      Ident |red|

diagnostics
  ERROR 25..76 unclosed block in '@media'
```

Tree and diagnostics live in one file deliberately: a fixture asserting only the tree would pass just as happily whether a malformed rule was recovered or silently swallowed.
The `(0,1,0)` on a complex selector is its specificity.

Also generated from raw bytes, so the same span caveat applies.

## Regenerating expected output

```
./gradlew test -Dcassette.fixtures.update=true
```

Rewrites every expected file from actual output and passes unconditionally, so the diff _is_ the review.
Never commit with it enabled in CI.

Every expected file is generated and reviewed, `*.css` included.
They were hand-written during scaffolding and re-derived from the serializer once it existed, and two of the hand-written guesses turned out to be wrong: the legacy flattening of `nesting-basic` writes one rule with two selectors rather than two rules with duplicated bodies, and a comment does not survive minification.

## Vendored WPT cases

Every `wpt-*` directory is derived from [web-platform-tests](https://github.com/web-platform-tests/wpt), under `css/css-syntax/`.
BSD-3-Clause, compatible with this project's Apache-2.0 license.
The license text and the pinned commit live once in [`WPT-LICENSE.md`](WPT-LICENSE.md); each fixture's `SOURCE.md` names its upstream file, the assertion it encodes, and any place cassette deliberately differs.

**These are translations, not copies.**
Upstream is `testharness.js` HTML asserting through the browser: `style.sheet.rules`, `document.querySelector`, computed values.
There are no input/expected pairs to vendor.
Each fixture takes upstream's CSS and re-expresses its assertion as a tree and a diagnostic list, and its `SOURCE.md` records what upstream asserted that cassette does not: cascade resolution, CSSOM-level rules like dropping `@charset`, and value-semantics validation are all non-goals.

So `ast.txt` is generated like every other golden file, but it is not self-certifying.
What makes these an oracle is that each generated tree was read against its upstream assertion once, by hand, and the verdict written down in `SOURCE.md`.
Regenerating one after a parser change means re-reading it the same way, not just accepting the diff.

Two upstream files were evaluated and not vendored:

| upstream                    | why not                                                                                                                                                                                                                                    |
|-----------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `unclosed-constructs.html`  | Asserts `[foo` and `:nth-child(1` are valid _selector strings_ via `querySelector`. Inside a stylesheet an unclosed bracket consumes the block it was supposed to introduce, so there is no input that puts cassette in the same position. |
| `non-ascii-codepoints.html` | A generated sweep of the section 4.2 ident ranges, checking each range's edges. That is a table-driven unit test, not a fixture; expressing it as CSS would mean thousands of near-identical rules.                                        |
