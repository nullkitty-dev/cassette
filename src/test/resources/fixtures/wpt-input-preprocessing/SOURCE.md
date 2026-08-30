# wpt-input-preprocessing

Vendored from web-platform-tests, `css/css-syntax/input-preprocessing.html`.
License and commit: [`../WPT-LICENSE.md`](../WPT-LICENSE.md).

**Upstream asserts:** a U+0000 anywhere in an identifier becomes U+FFFD, whether leading, interior, trailing, alone or repeated, per section 3.3 input preprocessing.

**Here:** four rules whose type selectors carry the replacement character in each of those positions.

**Not vendored from this file:** the lone-surrogate half.
Upstream builds those with `String.fromCodePoint` in JavaScript; a fixture is a byte file, and a lone surrogate has no valid UTF-8 encoding, so the substitution would happen in the charset decoder rather than in section 3.3 and the test would be asserting a different mechanism than upstream's.
