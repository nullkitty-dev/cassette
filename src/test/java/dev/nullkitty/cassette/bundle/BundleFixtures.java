package dev.nullkitty.cassette.bundle;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import dev.nullkitty.cassette.fixtures.Fixture;
import dev.nullkitty.cassette.parser.AstDump;

/**
 * Turns a bundle fixture into a bundle, and a bundle into the text a golden file holds.
 *
 * <p>Shared by {@code BundleFixtureTest} and {@code SerializerFixtureTest}, which assert
 * different things about the same assembled tree.
 */
public final class BundleFixtures {

    /**
     * Bundles a fixture's entry sources, in the order {@code entry.txt} lists them.
     *
     * <p>An importer over {@code sources/} is installed automatically, so a specifier resolves
     * the way a filesystem importer would with nothing stubbed, and a specifier naming nothing
     * in {@code sources/} is unresolved without anything having to arrange it.
     *
     * @param fixture a bundle fixture
     * @param options what to bundle with; its importer is replaced by the one over
     *                {@code sources/}
     * @return the result
     */
    public static BundleResult bundle(Fixture fixture, BundleOptions options) {
        List<Source> sources = new ArrayList<>();
        Map<String, byte[]> files = fixture.sources();

        for (String id : fixture.entry()) {
            byte[] content = files.get(id);
            if (content == null) {
                throw new IllegalStateException("fixture '"
                                                + fixture.name()
                                                + "' lists '"
                                                + id
                                                + "' in entry.txt but has no sources/"
                                                + id);
            }

            sources.add(new Source(id, content));
        }

        Importer importer = (specifier, from) -> Optional.ofNullable(files.get(specifier))
                                                         .map(content -> new Source(specifier, content));

        return Bundler.bundle(sources,
                              BundleOptions.builder().importer(importer).banners(options.banners())
                                           .maxImportDepth(options.maxImportDepth()).build());
    }

    /**
     * The {@code bundle.txt} golden: the tree, the diagnostics, and the segment table.
     *
     * <p>The segment table is there because tree order and span order diverge. A tree is in cascade
     * order and the coordinate space is in decode order, so a dump showing only spans would read as
     * though the tree were shuffled. Printing both means a reader is never guessing which one they
     * are looking at.
     *
     * @param result what to render
     * @return the dump
     */
    public static String dump(BundleResult result) {
        StringBuilder text = new StringBuilder(AstDump.withDiagnostics(result.ast(), result.diagnostics()));
        text.append("\nsegments\n");

        for (SourceIndex.Segment segment : result.sourceIndex().segments()) {
            text.append("  ") //
                .append(segment.base()) //
                .append("..") //
                .append(segment.base() + segment.length()) //
                .append(' ') //
                .append(segment.sourceId());

            if (segment.importedFrom() != null) {
                text.append(" imported from ").append(segment.importedFrom());
            }

            text.append('\n');
        }

        return text.toString();
    }

    private BundleFixtures() {
        // utility class
    }

}
