# wpt-escaped-eof-url

Vendored from web-platform-tests, `css/css-syntax/escaped-eof.html`.
License and commit: [`../WPT-LICENSE.md`](../WPT-LICENSE.md).

One of the four stylesheets in the upstream file, each of which ends mid-escape at end of input.
They are separate fixtures because an escaped EOF can only be tested as the last thing in a file.

**Upstream asserts:** `--foo:url(foo\` gives a url valued `foo` plus U+FFFD, since section 4.3.6 goes through the same escaped-code-point rule, and an unclosed url at EOF is still a url token.

**Here:** a `Url` valued `foo` plus U+FFFD and marked unterminated, plus the diagnostic for the end-of-input closure.
