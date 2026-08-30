# wpt-custom-property-rule-ambiguity

Vendored from web-platform-tests, `css/css-syntax/custom-property-rule-ambiguity.html`.
License and commit: [`../WPT-LICENSE.md`](../WPT-LICENSE.md).

Stylesheets 1 and 2 of the upstream file, which test the top level; the nested halves are in [`../wpt-custom-property-rule-ambiguity-nested`](../wpt-custom-property-rule-ambiguity-nested).

**Upstream asserts:** `--x:hover { }` at the top level is _not_ a rule.
Only `.a` and `.b` survive.
The same holds when the block contains a mismatched `]`.

**Here:** the same, with `.a`, `.b` and `.c` surviving and each `--x:`/`--y:` prelude is dropped with a diagnostic.

**Found two bugs.**
This fixture is why CSS Syntax section 5.5.3's custom-property prelude check exists in `Parser` at all: without it `--x:hover` parsed happily as an identifier followed by a pseudo-class.
And the `--y:hover { ] }` case caught `findMatchingClose` counting _any_ closer as matching, so a stray `]` ended the block early and the real `}` became a stray.
