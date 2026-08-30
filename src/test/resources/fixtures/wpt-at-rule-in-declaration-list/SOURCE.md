# wpt-at-rule-in-declaration-list

Vendored from web-platform-tests, `css/css-syntax/at-rule-in-declaration-list.html`.
License and commit: [`../WPT-LICENSE.md`](../WPT-LICENSE.md).

**Upstream asserts:** an unknown at-rule inside a declaration list, in either form (`@at {}` with a block or `@at at;` ended by a semicolon), does not stop the declaration written after it from applying.
Tested inside a style rule, a `@page` rule and a `@font-face` rule.

**Here:** the two style-rule cases assert structurally: an `AtRule` followed by a `Declaration` for `color`, with the at-rule consumed whole in both its block and statement forms.

**Out of scope, `@page` and `@font-face`:** neither is a conditional group rule, so their blocks stay opaque component values, which is CSS Syntax's own position on them, and what the architecture means by "most at-rules stay opaque".
The fixture records that the block was consumed intact; "margin-top still applies" is a CSSOM claim this library does not make.
