# wpt-url-whitespace-consumption

Vendored from web-platform-tests, `css/css-syntax/url-whitespace-consumption.html`.
License and commit: [`../WPT-LICENSE.md`](../WPT-LICENSE.md).

**Upstream asserts:** `url("foo")`, `url( "foo")` and `url("foo" )` are all the same value, since whitespace is optional between the `url(` token and the string inside it.

**Here:** asserted on `tokens.txt`.
`url(` followed by a string is a `FUNCTION` token, not a `URL` token (only the unquoted form is a `URL`), so the whitespace is ordinary intra-function whitespace and the three forms differ only in where it sits.
