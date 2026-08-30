# wpt-unclosed-url-at-eof-empty

Vendored from web-platform-tests, `css/css-syntax/unclosed-url-at-eof.html`.
License and commit: [`../WPT-LICENSE.md`](../WPT-LICENSE.md).

The second half of the upstream file: `url(` versus `url()`.

**Upstream asserts:** an unclosed _empty_ url token at EOF is valid too.

**Here:** both rules hold a `Url` with an empty value.
