# wpt-custom-property-rule-ambiguity-nested

Vendored from web-platform-tests, `css/css-syntax/custom-property-rule-ambiguity.html`.
License and commit: [`../WPT-LICENSE.md`](../WPT-LICENSE.md).

Stylesheets 3 and 4 of the upstream file, which test the same ambiguity nested inside a style rule, where the answer is the opposite of the top-level one.

**Upstream asserts:** nested, `--x:hover { }` _is_ a declaration, whose value swallows the rest of the block (`hover { }\n    .b { }`), so the `.b` written after it never becomes a rule.
With a mismatched `]` in the block, `--x` is invalid and `.b` is still not a rule.

**Here:** the `div` case matches exactly, value included.

**Deliberate difference, `section` case:** upstream drops the `--x` declaration entirely, because a custom property's value may not contain a mismatched `]`.
cassette keeps it.
That check is value-semantics validation, which the architecture lists as a non-goal: custom property values are captured per grammar and never evaluated.
The part that matters for recovery, that `.b` is not a rule, is the same either way.
