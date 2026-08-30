package dev.nullkitty.cassette.cli;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

import dev.nullkitty.cassette.serializer.NodeTransform;
import dev.nullkitty.cassette.serializer.Optimizations;
import dev.nullkitty.cassette.serializer.Optimizer;

/**
 * The {@code -O} names, one per {@link Optimizations} method.
 *
 * <p>Kebab-cased by the same rule every other enum flag follows, so {@code SHORTEN_COLORS} is
 * {@code shorten-colors} as {@code IS_WRAP} is {@code is-wrap}. {@code -O} therefore validates
 * through the same path as {@code --expand} and names its accepted values the same way.
 *
 * <p>Declaration order must stay {@link Optimizations#all()}'s order. A subset reaches
 * {@link Optimizer#optimize} in this order rather than in the order it was written on the command
 * line, because the pass fuses every enabled transform into one walk and re-dispatches when one
 * changes a node's type, so command-line order controls nothing. {@code Arguments} collects into
 * an {@code EnumSet}, which makes the ordering and the de-duping properties of the data structure
 * rather than of a sort somebody has to remember.
 *
 * <p>Not every transform here is in {@code all()}, and the {@link #inAll} flag is what says so. The
 * two dropping transforms remove an assertion the input made about itself, and whether that
 * assertion has gone false depends on what is done with the output, so the library keeps them out of
 * {@code all()} and a bare {@code -O} must not quietly turn them on. They are reachable only by
 * name, as {@code -O=drop-charset}.
 *
 * <p>Both halves of the agreement with the library are pinned by tests: the {@code all()}
 * subset must equal {@code Optimizations.all()} in order and membership, and every public
 * transform the library offers must be named here, so one added and not listed fails a test
 * rather than being quietly unavailable.
 */
enum Transform {

    /**
     * {@link Optimizations#lowercaseNames()}.
     */
    LOWERCASE_NAMES(Optimizations::lowercaseNames, true),

    /**
     * {@link Optimizations#shortenColors()}.
     */
    SHORTEN_COLORS(Optimizations::shortenColors, true),

    /**
     * {@link Optimizations#dropZeroUnits()}.
     */
    DROP_ZERO_UNITS(Optimizations::dropZeroUnits, true),

    /**
     * {@link Optimizations#compactNumbers()}.
     */
    COMPACT_NUMBERS(Optimizations::compactNumbers, true),

    /**
     * {@link Optimizations#dropCharset()}: outside {@code all()}, see the class comment.
     */
    DROP_CHARSET(Optimizations::dropCharset, false),

    /**
     * {@link Optimizations#dropSourceMappingUrl()}: outside {@code all()}.
     */
    DROP_SOURCE_MAP_URL(Optimizations::dropSourceMappingUrl, false);

    private final Supplier<NodeTransform<?>> factory;

    /**
     * Whether a bare {@code -O} turns this on.
     */
    private final boolean inAll;

    Transform(Supplier<NodeTransform<?>> factory, boolean inAll) {
        this.factory = factory;
        this.inAll = inAll;
    }

    NodeTransform<?> transform() {
        return this.factory.get();
    }

    /**
     * What a bare {@code -O} means, which is {@link Optimizations#all()} and not every constant.
     *
     * @return the transforms in {@code all()}, in this enum's order
     */
    static List<Transform> inAll() {
        return Stream.of(values()).filter(transform -> transform.inAll).toList();
    }

    /**
     * @param chosen the transforms to run, already in this enum's order
     * @return them as the library's own type, ready for {@link Optimizer#optimize}
     */
    static List<NodeTransform<?>> resolve(Collection<Transform> chosen) {
        List<NodeTransform<?>> transforms = new ArrayList<>(chosen.size());
        for (Transform transform : chosen) {
            transforms.add(transform.transform());
        }

        return transforms;
    }
}
