# wpt-cdc-vs-ident-tokens

Vendored from web-platform-tests, `css/css-syntax/cdc-vs-ident-tokens.html`.
License and commit: [`../WPT-LICENSE.md`](../WPT-LICENSE.md).

**Upstream asserts:** the first rule's selector is `--foo`.
The ordering of the checks in the tokenizer's HYPHEN-MINUS branch matters: get it wrong and an ident-token swallows the `-->` that precedes it.

**Here:** the top-level `-->` is discarded per section 5.4.1 and one `StyleRule` remains, with the type selector `--foo` intact.
