/**
 * Several stylesheets as one: the coordinate space that makes it possible, and the map back out
 * of it.
 *
 * <p>The unit of work everywhere else in this library is one stylesheet. Here it is an ordered
 * list of them, concatenated in cascade order, and eventually with each {@code @import}
 * replaced by the sheet it names. What comes out is an ordinary {@code Stylesheet}, so
 * {@code Flattener}, {@code Optimizer} and {@code CssSerializer} take it unchanged.
 *
 * <p>The mechanism both halves share is one space of character offsets. Each source is given a
 * base and every span it produces is born shifted by it, so no tree is ever rebased and
 * assembling N sources costs N parses and nothing more. {@link
 * dev.nullkitty.cassette.bundle.SourceIndex} is the table that turns an offset in that space
 * back into a source and a position inside it.
 *
 * <p>{@code SourceSpan.text} does not work here. It slices whatever text it is handed, no single
 * text is right for every span in such a tree, and the wrong one returns the wrong characters
 * instead of failing, because the offsets are still in range. {@code SourceIndex.textOf} knows
 * which source a span belongs to.
 *
 * <p>This is not a module bundler. There is no tree-shaking, no code splitting and no dependency
 * graph output, and {@code url()} contents are not rewritten when a file moves, which would need a
 * base-URL model this library does not have. Nor is it a resolver: cassette never touches a
 * filesystem, a classpath or a network. It hands a specifier to a caller-supplied importer and
 * takes bytes back.
 */
package dev.nullkitty.cassette.bundle;
