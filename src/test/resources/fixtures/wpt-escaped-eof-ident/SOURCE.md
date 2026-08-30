# wpt-escaped-eof-ident

Vendored from web-platform-tests, `css/css-syntax/escaped-eof.html`.
License and commit: [`../WPT-LICENSE.md`](../WPT-LICENSE.md).

One of the four stylesheets in the upstream file, each of which ends mid-escape at end of input.
They are separate fixtures because an escaped EOF can only be tested as the last thing in a file.

**Upstream asserts:** `--foo:foo\` gives the value `foo` plus U+FFFD.
Section 4.3.8 makes a backslash at end of input a valid escape, and section 4.3.7 resolves it to U+FFFD.

**Here:** an `Ident` whose value is `foo` plus U+FFFD.
