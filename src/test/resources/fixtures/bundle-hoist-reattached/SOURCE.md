# bundle-hoist-reattached

Hand-written.
An unresolved `@import` inside each kind of wrapper the bundler builds, which is the case [BUNDLING.md](../../../../../docs/BUNDLING.md) describes under prologue normalization.

`printed.css` and `themed.css` each strand an import that **can** be hoisted, because the enclosing condition maps onto one slot of the `@import` prelude: `print` and `layer(theme)`.
The golden shows the re-attached prelude at the top of the bundle and the wrapper left holding only the rules.

`anon.css` strands one that **cannot**.
`layer` in an import prelude creates a _new_ anonymous layer rather than naming the one the block made, so hoisting would put the import in a different layer from the rules it left, a change in cascade order with nothing in the output to show it.
That one stays inside its wrapper and the diagnostic says the output is invalid there.

Not asserted: the two-conditions-of-one-kind refusals, which need a chain two imports deep and are covered by `BundlerImportTest` rather than by a whole-file golden.
