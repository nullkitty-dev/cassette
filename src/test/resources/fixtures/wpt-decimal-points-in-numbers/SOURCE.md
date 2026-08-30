# wpt-decimal-points-in-numbers

Vendored from web-platform-tests, `css/css-syntax/decimal-points-in-numbers.html`.
License and commit: [`../WPT-LICENSE.md`](../WPT-LICENSE.md).

**Upstream asserts:** `1.0` and `.1` are numbers; `1.` is not.
The same for dimensions: `1.0px` and `.1px` are dimensions, `1.px` is not.

**Here:** asserted on `tokens.txt`, which is where the distinction lives: `1.` tokenizes as a `NUMBER` followed by a `DELIM` `.`, and `1.px` as `NUMBER`, `DELIM`, `IDENT`.
Upstream's serialized forms (`1`, `0.1`) are a serializer concern and are not asserted here.
