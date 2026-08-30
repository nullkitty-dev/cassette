# wpt-ident-three-code-points

Vendored from web-platform-tests, `css/css-syntax/ident-three-code-points.html`.
License and commit: [`../WPT-LICENSE.md`](../WPT-LICENSE.md).

**Upstream asserts:** `#1` and `#-2` do not select anything, while `#--3`, `#---4`, `#a`, `#-b`, `#--c` and `#---d` all do.
Whether a hash is an id depends on the first three code points after the `#`, which is section 4.3.9's "would start an identifier".

**Here:** six surviving `StyleRule`s with `Id` selectors, and two errors for the hashes that are not ids.
The dropped rules are the assertion.
