# wpt-escaped-eof-string

Vendored from web-platform-tests, `css/css-syntax/escaped-eof.html`.
License and commit: [`../WPT-LICENSE.md`](../WPT-LICENSE.md).

One of the four stylesheets in the upstream file, each of which ends mid-escape at end of input.
They are separate fixtures because an escaped EOF can only be tested as the last thing in a file.

**Upstream asserts:** `--foo:"foo\` gives `"foo"`.
Strings are the exception: section 4.3.5 says a backslash at end of input does _nothing_, where every other token type resolves it to U+FFFD through section 4.3.7.

**Found a bug.**
cassette produced `foo` plus U+FFFD here, because the tokenizer left the trailing backslash inside the value span and `Escapes` then applied section 4.3.7 to it.
`consumeString` now ends the value span before the backslash, so the two rules stay distinct.
The raw text still includes it.
