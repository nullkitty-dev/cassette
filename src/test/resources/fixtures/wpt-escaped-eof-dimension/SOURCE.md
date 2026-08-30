# wpt-escaped-eof-dimension

Vendored from web-platform-tests, `css/css-syntax/escaped-eof.html`.
License and commit: [`../WPT-LICENSE.md`](../WPT-LICENSE.md).

One of the four stylesheets in the upstream file, each of which ends mid-escape at end of input.
They are separate fixtures because an escaped EOF can only be tested as the last thing in a file.

**Upstream asserts:** `--foo:1foo\` gives `1foo` plus U+FFFD, so the escape resolves inside the unit, not just inside a bare identifier.

**Here:** a `Dimension` with numeric value 1 and unit `foo` plus U+FFFD.
