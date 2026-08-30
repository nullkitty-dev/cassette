package dev.nullkitty.cassette.lexer;

import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import dev.nullkitty.cassette.text.Ascii;

/**
 * An encoding named by a label, resolved per the WHATWG Encoding Standard's label table.
 *
 * <p>Two of the Encoding Standard's encodings have no JVM {@link Charset} and are modelled
 * as their own kinds instead:
 *
 * <ul>
 *   <li>{@code replacement}, the labels for a handful of encodings (ISO-2022-KR, HZ-GB-2312
 *       and friends) the standard refuses to implement, because honouring them lets an
 *       attacker smuggle syntax past a filter that read the bytes as ASCII. Decoding one
 *       yields a single U+FFFD, discarding the input.
 *   <li>{@code x-user-defined}, a byte-preserving encoding mapping the high half to the
 *       private-use area.
 * </ul>
 *
 * <p>Labels the table doesn't know are handed to {@link Charset#forName(String)} as a last
 * resort, so a JVM-supported encoding still works even where cassette hasn't catalogued its
 * label. That is a superset of the Encoding Standard, not a subset: it accepts a few names a
 * browser would reject rather than rejecting names a browser would accept.
 *
 * <p>Only the label tables are eager. The charsets behind them are not. UTF-8 and the UTF-16 pair
 * come from {@link java.nio.charset.StandardCharsets} and cost nothing to hold, and UTF-8 is the
 * overwhelming majority of real input, so the common path is one hash lookup and no charset
 * resolution at all. Every other label resolves through {@link Charset#forName(String)} on first
 * use and is cached from then on, which keeps some 30 charset lookups out of class initialization
 * and out of a native image's build-time heap.
 *
 * <p>A label this build cannot decode resolves to {@code null} rather than throwing, so a
 * stylesheet naming an encoding the runtime lacks falls back instead of failing. That alone is
 * indistinguishable from a UTF-8 file, so {@link #catalogues} tells the two apart and the parser
 * reports the difference.
 *
 * @see <a href="https://encoding.spec.whatwg.org/#names-and-labels">WHATWG Encoding Standard §4.2 Names
 *      and labels</a>
 */
public final class CssEncoding {

    /**
     * How the bytes are to be turned into characters.
     */
    public enum Kind {

        /**
         * Decode with {@link CssEncoding#charset()}.
         */
        CHARSET,

        /**
         * Discard the input, yielding a single U+FFFD.
         */
        REPLACEMENT,

        /**
         * Map {@code 0x00-0x7F} to itself and {@code 0x80-0xFF} to {@code U+F780-U+F7FF}.
         */
        X_USER_DEFINED
    }

    /**
     * Labels whose encoding needs no {@link Charset#forName} call to produce.
     */
    private static final Map<String, CssEncoding> BY_LABEL = HashMap.newHashMap(32);

    /**
     * Every other label, mapped to the JVM charset name it resolves through on first use.
     */
    private static final Map<String, String> CHARSET_NAMES = HashMap.newHashMap(256);

    /**
     * Resolved (or known-unresolvable) charsets, keyed by JVM charset name.
     */
    private static final ConcurrentMap<String, CssEncoding> RESOLVED = new ConcurrentHashMap<>(16);

    /**
     * UTF-8, the fallback when nothing else determines an encoding.
     */
    public static final CssEncoding UTF_8 = of(StandardCharsets.UTF_8);

    public static final CssEncoding UTF_16BE = of(StandardCharsets.UTF_16BE);

    public static final CssEncoding UTF_16LE = of(StandardCharsets.UTF_16LE);

    private static final CssEncoding REPLACEMENT = new CssEncoding(Kind.REPLACEMENT, null);

    private static final CssEncoding X_USER_DEFINED = new CssEncoding(Kind.X_USER_DEFINED, null);

    /**
     * Cached "this runtime does not have that charset", so a label naming a charset the build
     * lacks costs one failed lookup rather than one per stylesheet. Never escapes
     * {@link #resolve}; compared by identity.
     */
    private static final CssEncoding UNAVAILABLE = new CssEncoding(Kind.CHARSET, null);

    static {

        register(UTF_8, //
                 "unicode-1-1-utf-8",
                 "unicode11utf8",
                 "unicode20utf8",
                 "utf-8",
                 "utf8",
                 "x-unicode20utf8");

        register(UTF_16BE, //
                 "unicodefffe",
                 "utf-16be");

        register(UTF_16LE, //
                 "csunicode",
                 "iso-10646-ucs-2",
                 "ucs-2",
                 "unicode",
                 "unicodefeff",
                 "utf-16",
                 "utf-16le");

        // windows-1252 absorbs the ASCII and Latin-1 labels: the Encoding Standard maps them
        // all here, because that is what the installed base of documents actually means.
        registerCharset("windows-1252",
                        "ansi_x3.4-1968",
                        "ascii",
                        "cp1252",
                        "cp819",
                        "csisolatin1",
                        "ibm819",
                        "iso-8859-1",
                        "iso-ir-100",
                        "iso8859-1",
                        "iso88591",
                        "iso_8859-1",
                        "iso_8859-1:1987",
                        "l1",
                        "latin1",
                        "us-ascii",
                        "windows-1252",
                        "x-cp1252");

        registerCharset("windows-1250", //
                        "cp1250",
                        "windows-1250",
                        "x-cp1250");

        registerCharset("windows-1251", //
                        "cp1251",
                        "windows-1251",
                        "x-cp1251");

        registerCharset("windows-1253", //
                        "cp1253",
                        "windows-1253",
                        "x-cp1253");

        registerCharset("windows-1254", //
                        "cp1254",
                        "csisolatin5",
                        "iso-8859-9",
                        "iso-ir-148",
                        "iso8859-9",
                        "iso88599",
                        "iso_8859-9",
                        "iso_8859-9:1989",
                        "l5",
                        "latin5",
                        "windows-1254",
                        "x-cp1254");

        registerCharset("windows-1255", //
                        "cp1255",
                        "windows-1255",
                        "x-cp1255");

        registerCharset("windows-1256", //
                        "cp1256",
                        "windows-1256",
                        "x-cp1256");

        registerCharset("windows-1257", //
                        "cp1257",
                        "windows-1257",
                        "x-cp1257");

        registerCharset("windows-1258", //
                        "cp1258",
                        "windows-1258",
                        "x-cp1258");

        registerCharset("windows-874", //
                        "dos-874",
                        "iso-8859-11",
                        "iso8859-11",
                        "iso885911",
                        "tis-620",
                        "windows-874");

        registerCharset("ISO-8859-2",
                        "csisolatin2",
                        "iso-8859-2",
                        "iso-ir-101",
                        "iso8859-2",
                        "iso88592",
                        "iso_8859-2",
                        "iso_8859-2:1987",
                        "l2",
                        "latin2");

        registerCharset("ISO-8859-3",
                        "csisolatin3",
                        "iso-8859-3",
                        "iso-ir-109",
                        "iso8859-3",
                        "iso88593",
                        "iso_8859-3",
                        "iso_8859-3:1988",
                        "l3",
                        "latin3");

        registerCharset("ISO-8859-4",
                        "csisolatin4",
                        "iso-8859-4",
                        "iso-ir-110",
                        "iso8859-4",
                        "iso88594",
                        "iso_8859-4",
                        "iso_8859-4:1988",
                        "l4",
                        "latin4");

        registerCharset("ISO-8859-5",
                        "csisolatincyrillic",
                        "cyrillic",
                        "iso-8859-5",
                        "iso-ir-144",
                        "iso8859-5",
                        "iso88595",
                        "iso_8859-5",
                        "iso_8859-5:1988");

        registerCharset("ISO-8859-6",
                        "arabic",
                        "asmo-708",
                        "csiso88596e",
                        "csiso88596i",
                        "csisolatinarabic",
                        "ecma-114",
                        "iso-8859-6",
                        "iso-8859-6-e",
                        "iso-8859-6-i",
                        "iso-ir-127",
                        "iso8859-6",
                        "iso88596",
                        "iso_8859-6",
                        "iso_8859-6:1987");

        registerCharset("ISO-8859-7",
                        "csisolatingreek",
                        "ecma-118",
                        "elot_928",
                        "greek",
                        "greek8",
                        "iso-8859-7",
                        "iso-ir-126",
                        "iso8859-7",
                        "iso88597",
                        "iso_8859-7",
                        "iso_8859-7:1987",
                        "sun_eu_greek");

        registerCharset("ISO-8859-8",
                        "csiso88598e",
                        "csisolatinhebrew",
                        "hebrew",
                        "iso-8859-8",
                        "iso-8859-8-e",
                        "iso-ir-138",
                        "iso8859-8",
                        "iso88598",
                        "iso_8859-8",
                        "iso_8859-8:1988",
                        "visual");

        registerCharset("ISO-8859-13", //
                        "iso-8859-13",
                        "iso8859-13",
                        "iso885913");

        registerCharset("ISO-8859-15", //
                        "csisolatin9",
                        "iso-8859-15",
                        "iso8859-15",
                        "iso885915",
                        "iso_8859-15",
                        "l9");

        registerCharset("KOI8-R", //
                        "cskoi8r",
                        "koi",
                        "koi8",
                        "koi8-r",
                        "koi8_r");

        registerCharset("KOI8-U", //
                        "koi8-ru",
                        "koi8-u");

        registerCharset("x-MacRoman", //
                        "csmacintosh",
                        "mac",
                        "macintosh",
                        "x-mac-roman");

        registerCharset("x-MacCyrillic", //
                        "x-mac-cyrillic",
                        "x-mac-ukrainian");

        registerCharset("IBM866", //
                        "866",
                        "cp866",
                        "csibm866",
                        "ibm866");

        registerCharset("GBK",
                        "chinese",
                        "csgb2312",
                        "csiso58gb231280",
                        "gb2312",
                        "gb_2312",
                        "gb_2312-80",
                        "gbk",
                        "iso-ir-58",
                        "x-gbk");

        registerCharset("GB18030", //
                        "gb18030");

        registerCharset("Big5", //
                        "big5",
                        "big5-hkscs",
                        "cn-big5",
                        "csbig5",
                        "x-x-big5");

        registerCharset("EUC-JP", //
                        "cseucpkdfmtjapanese",
                        "euc-jp",
                        "x-euc-jp");

        registerCharset("ISO-2022-JP", //
                        "csiso2022jp",
                        "iso-2022-jp");

        registerCharset("Shift_JIS",
                        "csshiftjis",
                        "ms932",
                        "ms_kanji",
                        "shift-jis",
                        "shift_jis",
                        "sjis",
                        "windows-31j",
                        "x-sjis");

        registerCharset("EUC-KR",
                        "cseuckr",
                        "csksc56011987",
                        "euc-kr",
                        "iso-ir-149",
                        "korean",
                        "ks_c_5601-1987",
                        "ks_c_5601-1989",
                        "ksc5601",
                        "ksc_5601",
                        "windows-949");

        register(REPLACEMENT,
                 "csiso2022kr",
                 "hz-gb-2312",
                 "iso-2022-cn",
                 "iso-2022-cn-ext",
                 "iso-2022-kr",
                 "replacement");

        register(X_USER_DEFINED, //
                 "x-user-defined");
    }

    private final Kind kind;

    private final Charset charset;

    private CssEncoding(Kind kind, Charset charset) {
        this.kind = kind;
        this.charset = charset;
    }

    /**
     * Wraps a JVM charset directly, bypassing label lookup.
     */
    public static CssEncoding of(Charset charset) {
        return new CssEncoding(Kind.CHARSET, charset);
    }

    /**
     * Resolves an encoding label, as {@code @charset} and protocol headers supply it.
     *
     * <p>Returns {@code null} for a label naming nothing, and for one naming a charset this
     * runtime cannot supply. {@link #catalogues} separates the two, which is the difference
     * between a stylesheet being wrong and a build being incomplete.
     *
     * @param label the raw label; leading and trailing ASCII whitespace and case are ignored
     * @return the encoding, or {@code null} if the label names nothing this JVM can decode
     */
    public static CssEncoding forLabel(String label) {
        if (label == null) {
            return null;
        }

        String normalized = normalize(label);
        if (normalized.isEmpty()) {
            return null;
        }

        CssEncoding known = BY_LABEL.get(normalized);
        if (known != null) {
            return known;
        }

        String charsetName = CHARSET_NAMES.get(normalized);
        CssEncoding resolved = resolve(charsetName != null ? charsetName : normalized);

        return resolved == UNAVAILABLE ? null : resolved;
    }

    /**
     * Whether this label is one cassette knows a charset name for, whether or not the runtime
     * can supply it.
     *
     * <p>Only useful alongside a {@code null} from {@link #forLabel}: true there means the
     * label is a real encoding that this build cannot decode, a JVM without it, or a native
     * image built without {@code -H:+AddAllCharsets}, while false means the label names no
     * encoding at all.
     *
     * @param label the raw label, normalized as {@link #forLabel} normalizes it
     * @return whether the label appears in the Encoding Standard table cassette carries
     * @see <a href="https://encoding.spec.whatwg.org/#names-and-labels">WHATWG Encoding Standard §4.2
     *      Names and labels</a>
     */
    public static boolean catalogues(String label) {
        if (label == null) {
            return false;
        }

        String normalized = normalize(label);

        return BY_LABEL.containsKey(normalized) || CHARSET_NAMES.containsKey(normalized);
    }

    /**
     * Resolves a JVM charset name once, caching the outcome either way.
     */
    private static CssEncoding resolve(String charsetName) {
        CssEncoding cached = RESOLVED.get(charsetName);
        if (cached != null) {
            return cached;
        }

        CssEncoding encoding;
        try {
            encoding = of(Charset.forName(charsetName));
        }
        catch (IllegalCharsetNameException | UnsupportedCharsetException e) {
            // A runtime without this charset cannot offer it. The label stays unresolved and
            // the caller falls back, loudly, because `catalogues` can say it was a real one.
            encoding = UNAVAILABLE;
        }

        RESOLVED.put(charsetName, encoding);

        return encoding;
    }

    /**
     * Strips the ASCII whitespace the Encoding Standard ignores, then lowercases.
     *
     * @see <a href="https://encoding.spec.whatwg.org/#concept-encoding-get">WHATWG Encoding Standard
     *      §4.2 Names and labels, get an encoding</a>
     */
    private static String normalize(String label) {
        int from = 0;
        int to = label.length();

        while (from < to && isAsciiWhitespace(label.charAt(from))) {
            from++;
        }

        while (to > from && isAsciiWhitespace(label.charAt(to - 1))) {
            to--;
        }

        return Ascii.lower(label.substring(from, to));
    }

    private static boolean isAsciiWhitespace(char c) {
        return c == '\t' || c == '\n' || c == '\f' || c == '\r' || c == ' ';
    }

    public Kind kind() {
        return this.kind;
    }

    /**
     * The JVM charset to decode with.
     *
     * @return the charset, or {@code null} for {@link Kind#REPLACEMENT} and
     *         {@link Kind#X_USER_DEFINED}, which have no JVM equivalent
     */
    public Charset charset() {
        return this.charset;
    }

    /**
     * The name reported for this encoding.
     *
     * @return the charset name, or the kind's Encoding Standard name
     */
    public String name() {
        return switch (this.kind) {
            case CHARSET -> this.charset.name();
            case REPLACEMENT -> "replacement";
            case X_USER_DEFINED -> "x-user-defined";
        };
    }

    @Override
    public String toString() {
        return name();
    }

    /**
     * Records the charset name a set of labels resolves through, without resolving it.
     *
     * <p>Nothing here calls {@link Charset#forName}: whether the runtime has the
     * charset is asked on first use, by {@link #resolve}. That keeps roughly thirty lookups
     * out of class initialization, and it is what lets a missing charset be reported against
     * the stylesheet that wanted it rather than vanishing silently at startup.
     */
    private static void registerCharset(String charsetName, //
                                        String... labels) {
        for (String label : labels) {
            CHARSET_NAMES.put(label, charsetName);
        }
    }

    private static void register(CssEncoding encoding, //
                                 String... labels) {
        for (String label : labels) {
            BY_LABEL.put(label, encoding);
        }
    }
}
