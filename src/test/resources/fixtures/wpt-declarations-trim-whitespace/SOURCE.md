# wpt-declarations-trim-whitespace

Vendored from web-platform-tests, `css/css-syntax/declarations-trim-whitespace.html`.
License and commit: [`../WPT-LICENSE.md`](../WPT-LICENSE.md).

**Upstream asserts:** all nine custom properties compute to exactly `bar`, however the source spaced them and whether or not `!important` follows, including `--foo-9`, which is followed only by whitespace and the closing brace.

**Here:** nine declarations, each with a single `Ident` value `bar`, and `important` set on 5 through 8.
Whitespace trimming and `!important` stripping happen in the parser, so this is asserted on the tree rather than on a computed value.
