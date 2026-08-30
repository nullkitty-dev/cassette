# wpt-trailing-braces

Vendored from web-platform-tests, `css/css-syntax/trailing-braces.html`.
License and commit: [`../WPT-LICENSE.md`](../WPT-LICENSE.md).

**Upstream asserts:** one rule, whose `color` is `green`.
`color:red{}` and `color:red {}` are not declarations, since under CSS Nesting they are nested style rules with the selector `color:red`, so neither overrides the `color:green` written before them.

**Here:** one `StyleRule`, one `Declaration` for `color`, and two nested style rules whose selector is a type selector `color` plus a pseudo-class `red`.
Which is exactly why the browser still renders green.

Cascade resolution is out of scope, so "the element is green" is asserted here as "the only declaration is `color: green`".
