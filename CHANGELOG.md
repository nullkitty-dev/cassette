# Changelog

## [1.0.0] - 2026-08-30

First public release.

### Added

* **Parsing** per CSS Syntax Level 3, with the spec's error recovery.
  Nothing throws on malformed input, `StackOverflowError` included, because recursion is bounded.
  Malformed input becomes a `Diagnostic` and the parse continues.

* **An AST** in which every node knows the byte range it came from, and parent spans contain their children's.

* **Selectors** per Selectors Level 4, including forgiving lists and specificity.

* **Nesting** per CSS Nesting Level 1, and a flattener that lowers it to CSS 2.x for targets that cannot handle it.

* **Serialization** with pretty and minified formatting.
  `minify` means whitespace and comments and nothing else.
  Output is a fixed point of its own round trip.

* **An optimizer** for transforms that change what a stylesheet says.
  Every one is opt-in, because none of them is free of semantics.

* **Bundling**: several sources in one coordinate space with `@import` resolution, and `BundleResult.sourceIndex()` to map any span back to the file that wrote it.

* **Source maps** per ECMA-426, generated alongside the CSS, whose bytes are identical to what a plain serialize returns.

* **Encoding detection** per WHATWG Encoding, from a BOM or an `@charset`, with a diagnostic when the label is unknown.

* **A CLI** with three verbs, `format`, `minify` and `check`, where `check` writes nothing and exits nonzero if anything is wrong.
  It ships as `cassette-1.0.0-cli.jar` on the GitHub release, not on Maven Central.

* **A GraalVM native image** of that CLI, for startup.

### Notes

* Zero runtime dependencies, and that is policy rather than a current fact.
* A JPMS module, `dev.nullkitty.cassette`.
* Java 21 or later.
* There is no cascade, no computed values and no CSSOM, and each is a stated non-goal.

[1.0.0]: https://github.com/nullkitty-dev/cassette/releases/tag/v1.0.0
