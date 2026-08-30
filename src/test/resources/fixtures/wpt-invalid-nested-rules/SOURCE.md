# wpt-invalid-nested-rules

Vendored from web-platform-tests, `css/css-syntax/invalid-nested-rules.html`.
License and commit: [`../WPT-LICENSE.md`](../WPT-LICENSE.md).

**Upstream asserts:** the stylesheet has one rule, that rule has exactly one child rule, and that child's selector is `& .c`.
An invalid nested rule is dropped, but its block is still consumed, so parsing resumes in the right place instead of reading the block's contents as top-level rules.

**Here:** the same, structurally: one `StyleRule` for `.a`, one nested `StyleRule` for `& .c`, and one error naming the `<` that the selector grammar tripped on.
