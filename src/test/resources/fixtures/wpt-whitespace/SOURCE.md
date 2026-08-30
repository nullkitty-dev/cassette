# wpt-whitespace

Vendored from web-platform-tests, `css/css-syntax/whitespace.html`.
License and commit: [`../WPT-LICENSE.md`](../WPT-LICENSE.md).

**Upstream asserts:** CSS whitespace is exactly U+0009, U+000A, U+000C, U+000D and U+0020.
Every other Unicode space (U+00A0, U+3000, U+000B and the rest) is either a different selector or not a valid selector at all.

**Here:** the five real whitespace characters each produce the identical descendant selector `.a b`, and the three counterexamples invalidate the rule.
Upstream's assertion is deliberately disjunctive for the negative cases ("isn't valid in a selector at all" is an accepted outcome), and rejecting the rule is that branch: U+00A0 and U+3000 fall outside section 4.2's non-ASCII ident ranges, so they are delimiters between two compound selectors with no combinator, which is a syntax error.

Only a sample of upstream's non-whitespace list is here; they all reach the same two code paths.
