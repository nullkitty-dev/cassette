# wpt-charset-is-not-a-rule

Vendored from web-platform-tests, `css/css-syntax/charset-is-not-a-rule.html`.
License and commit: [`../WPT-LICENSE.md`](../WPT-LICENSE.md).

**Upstream asserts:** `@charset` never appears in the object model.
All three are dropped and only `foo { color: blue }` remains.

**Deliberate difference.**
cassette keeps all three as ordinary statement `AtRule`s.
`@charset` is a byte-level hack handled during encoding detection, and dropping it afterwards is a CSSOM rule rather than a syntax one: this parser has no CSSOM, and semantic validation of at-rules is a non-goal.
`foo { color: blue }` parses identically either way.

This is the one place the difference could become visible: a passthrough serializer will re-emit a mid-stylesheet `@charset` that a browser would have discarded.
Dropping it belongs to an opt-in transform, which is what `Optimizations.dropCharset()` is, and not to something the parser does behind the caller's back.
