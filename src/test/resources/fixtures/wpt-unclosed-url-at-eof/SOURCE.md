# wpt-unclosed-url-at-eof

Vendored from web-platform-tests, `css/css-syntax/unclosed-url-at-eof.html`.
License and commit: [`../WPT-LICENSE.md`](../WPT-LICENSE.md).

**Upstream asserts:** `url(foo` and `url(foo)` produce the same value.
An unclosed url token at end of input is valid, not an error that discards the declaration.

**Here:** both rules hold a `Url` valued `foo`; the second is additionally marked unterminated, and carries the section 4.3.6 parse-error diagnostic.
The value, the part upstream compares, is the same.

The empty form is in [`../wpt-unclosed-url-at-eof-empty`](../wpt-unclosed-url-at-eof-empty), since only one construct per file can run to end of input.
