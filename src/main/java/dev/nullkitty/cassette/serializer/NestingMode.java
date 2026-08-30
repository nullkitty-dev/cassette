package dev.nullkitty.cassette.serializer;

/**
 * Whether nesting written in the source survives serialization.
 *
 * <p>The two are not different formatters of the same tree: {@link #FLATTEN} rewrites the
 * tree first, absolutizing every nested selector against its parent, and only then writes
 * it out.
 */
public enum NestingMode {

    /**
     * Emit nested rules as nested rules, {@code &} and all.
     */
    PRESERVE,

    /**
     * Rewrite nested rules into flat, absolute ones an engine that predates CSS Nesting can
     * read.
     *
     * <p>How {@code &} is expanded is a separate choice; see {@link NestingExpansion}.
     */
    FLATTEN
}
