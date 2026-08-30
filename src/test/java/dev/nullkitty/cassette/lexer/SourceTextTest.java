package dev.nullkitty.cassette.lexer;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import dev.nullkitty.cassette.ast.SourceSpan;

class SourceTextTest {

    private static final byte[] BOM_UTF_8    = { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF };
    private static final byte[] BOM_UTF_16LE = { (byte) 0xFF, (byte) 0xFE };
    private static final byte[] BOM_UTF_16BE = { (byte) 0xFE, (byte) 0xFF };

    @Nested
    class CharsetDetection {

        @Test
        void defaultsToUtf8() {
            SourceText source = SourceText.decode("a{content:\"café\"}".getBytes(UTF_8));

            assertThat(source.encoding().charset()).isEqualTo(UTF_8);
            assertThat(source.toString()).contains("café");
        }

        @Test
        void honoursAProtocolSuppliedEncoding() {
            byte[] bytes = "a{content:\"café\"}".getBytes(ISO_8859_1);

            SourceText source = SourceText.decode(bytes, ISO_8859_1);

            assertThat(source.toString()).contains("café");
        }

        @Test
        void aCharsetRuleOutranksTheProtocolEncoding() {
            byte[] bytes = concat("@charset \"iso-8859-2\";".getBytes(UTF_8), new byte[] { (byte) 0xE1 });

            SourceText source = SourceText.decode(bytes, UTF_8);

            assertThat(source.encoding().name()).isEqualTo("ISO-8859-2");
            assertThat(source.toString()).endsWith("á");
        }

        @Test
        @DisplayName("a BOM outranks @charset, because decode sniffs before it reads the fallback")
        void aBomOutranksACharsetRule() {
            byte[] bytes = concat(BOM_UTF_8, "@charset \"iso-8859-2\";a{}".getBytes(UTF_8));

            SourceText source = SourceText.decode(bytes, ISO_8859_1);

            assertThat(source.encoding().charset()).isEqualTo(UTF_8);
        }

        @Test
        void stripsTheByteOrderMark() {
            SourceText source = SourceText.decode(concat(BOM_UTF_8, "a{}".getBytes(UTF_8)));

            assertThat(source.toString()).isEqualTo("a{}");
            assertThat(source.length()).isEqualTo(3);
        }

        @Test
        void readsUtf16ByBom() {
            SourceText littleEndian =
                SourceText.decode(concat(BOM_UTF_16LE, "a{}".getBytes(StandardCharsets.UTF_16LE)));
            SourceText bigEndian = SourceText.decode(concat(BOM_UTF_16BE, "a{}".getBytes(StandardCharsets.UTF_16BE)));

            assertThat(littleEndian.toString()).isEqualTo("a{}");
            assertThat(bigEndian.toString()).isEqualTo("a{}");
        }

        @Test
        @DisplayName("@charset claiming UTF-16 is self-contradictory, so §3.2 substitutes UTF-8")
        void aCharsetRuleClaimingUtf16FallsBackToUtf8() {
            SourceText source = SourceText.decode("@charset \"utf-16\";a{}".getBytes(UTF_8));

            assertThat(source.encoding().charset()).isEqualTo(UTF_8);
        }

        @Test
        void ignoresACharsetRuleThatIsNotAtTheVeryStart() {
            byte[] bytes = " @charset \"iso-8859-2\";".getBytes(UTF_8);

            assertThat(SourceText.decode(bytes).encoding().charset()).isEqualTo(UTF_8);
        }

        @Test
        void ignoresAMalformedCharsetRule() {
            assertThat(SourceText.decode("@charset 'iso-8859-2';".getBytes(UTF_8)).encoding()
                                 .charset()).isEqualTo(UTF_8);

            assertThat(SourceText.decode("@charset \"iso-8859-2\"".getBytes(UTF_8)).encoding()
                                 .charset()).isEqualTo(UTF_8);

            assertThat(SourceText.decode("@charset  \"iso-8859-2\";".getBytes(UTF_8)).encoding()
                                 .charset()).isEqualTo(UTF_8);
        }

        @Test
        void ignoresACharsetRuleNamingAnUnknownEncoding() {
            assertThat(SourceText.decode("@charset \"not-an-encoding\";".getBytes(UTF_8)).encoding()
                                 .charset()).isEqualTo(UTF_8);
        }

        @Test
        void replacesUndecodableBytesRatherThanFailing() {
            SourceText source = SourceText.decode(new byte[] { 'a', (byte) 0xFF, 'b' });

            assertThat(source.toString()).isEqualTo("a�b");
        }

        @Test
        void readsFromAStream() throws IOException {
            byte[] bytes = concat(BOM_UTF_8, "a{}".getBytes(UTF_8));

            SourceText source = SourceText.decode(new ByteArrayInputStream(bytes), null);

            assertThat(source.toString()).isEqualTo("a{}");
        }
    }

    @Nested
    class Preprocessing {

        @Test
        void normalisesEveryNewlineFormToLineFeed() {
            assertThat(SourceText.of("a\r\nb\rc\fd").toString()).isEqualTo("a\nb\nc\nd");
        }

        @Test
        void crlfShortensTheBuffer() {
            SourceText source = SourceText.of("a\r\nb");

            assertThat(source.length()).isEqualTo(3);
            assertThat(source.charAt(1)).isEqualTo('\n');
        }

        @Test
        void replacesNullWithU00FFFD() {
            assertThat(SourceText.of("a\0b").toString()).isEqualTo("a�b");
        }

        @Test
        void replacesUnpairedSurrogates() {
            assertThat(SourceText.of("a\ud800b").toString()).isEqualTo("a�b");
            assertThat(SourceText.of("a\udc00b").toString()).isEqualTo("a�b");
        }

        @Test
        void keepsValidSurrogatePairsIntact() {
            String astral = new String(Character.toChars(0x1D54F));

            assertThat(SourceText.of(astral).toString()).isEqualTo(astral);
        }

        @Test
        void appliesToAlreadyDecodedTextToo() {
            assertThat(SourceText.of("a\r\nb").toString()).isEqualTo("a\nb");
        }
    }

    @Nested
    class Labels {

        @Test
        void resolvesEncodingStandardLabels() {
            assertThat(CssEncoding.forLabel("utf8").charset()).isEqualTo(UTF_8);
            assertThat(CssEncoding.forLabel("  UTF-8  ").charset()).isEqualTo(UTF_8);
        }

        @Test
        @DisplayName("the Encoding Standard maps ascii and latin1 to windows-1252")
        void mapsLatin1LabelsToWindows1252() {
            assertThat(CssEncoding.forLabel("iso-8859-1").charset()).isEqualTo(Charset.forName("windows-1252"));
            assertThat(CssEncoding.forLabel("us-ascii").charset()).isEqualTo(Charset.forName("windows-1252"));
        }

        @Test
        void returnsNullForAnUnknownLabel() {
            assertThat(CssEncoding.forLabel("definitely-not-an-encoding")).isNull();
            assertThat(CssEncoding.forLabel("")).isNull();
            assertThat(CssEncoding.forLabel(null)).isNull();
        }

        /**
         * The guard on the whole lazy-resolution scheme, and on a native image built without
         * {@code -H:+AddAllCharsets}: nothing else asserts the label table is populated at
         * all, and a build that silently lost these decodes legacy CSS to mojibake.
         *
         * <p>The labels come from {@code charset-labels.txt} rather than from a list here, because
         * the {@code nativeCharsetCheck} Gradle task asserts the same of the native binary against
         * the same file. One file is what makes "the same test" literally true instead of a claim
         * two copies have to keep agreeing on.
         */
        @Test
        @DisplayName("every catalogued label resolves on this runtime")
        void cataloguedLabelsResolve() throws Exception {
            List<String> labels = cataloguedLabels();

            // A file that failed to load would make every assertion below vacuous.
            assertThat(labels).hasSizeGreaterThan(30);

            for (String label : labels) {
                assertThat(CssEncoding.catalogues(label)).describedAs("catalogued: %s", label).isTrue();
                assertThat(CssEncoding.forLabel(label)).describedAs("resolvable: %s", label).isNotNull();
            }
        }

        /**
         * The shared list, comments and blank lines stripped.
         */
        private static List<String> cataloguedLabels() throws Exception {
            try (var in = SourceTextTest.class.getResourceAsStream("/charset-labels.txt")) {
                assertThat(in).describedAs("charset-labels.txt on the test classpath").isNotNull();
                return new String(in.readAllBytes(),
                                  java.nio.charset.StandardCharsets.UTF_8).lines().map(String::trim)
                                                                          .filter(line -> !line.isEmpty()
                                                                                          && !line.startsWith("#"))
                                                                          .toList();
            }
        }

        @Test
        @DisplayName("catalogues separates a real encoding from a nonsense one")
        void cataloguesDistinguishesKnownLabelsFromUnknownOnes() {
            assertThat(CssEncoding.catalogues("shift_jis")).isTrue();
            assertThat(CssEncoding.catalogues("  SHIFT_JIS  ")).isTrue();
            assertThat(CssEncoding.catalogues("definitely-not-an-encoding")).isFalse();
            assertThat(CssEncoding.catalogues(null)).isFalse();
        }

        @Test
        @DisplayName("a label resolves to the same instance every time it is asked for")
        void resolutionIsCached() {
            assertThat(CssEncoding.forLabel("shift_jis")).isSameAs(CssEncoding.forLabel("windows-31j"));
        }

        @Test
        @DisplayName("an unresolvable @charset is recorded, a resolvable one is not")
        void recordsAnUnresolvableCharset() {
            SourceText fallback = SourceText.decode("@charset \"nonsense\";a{}".getBytes(UTF_8));

            assertThat(fallback.unresolvedCharset()).isEqualTo("nonsense");
            assertThat(fallback.encoding().charset()).isEqualTo(UTF_8);
            assertThat(fallback.toString()).isEqualTo("@charset \"nonsense\";a{}");

            // The span covers the rule, so a caller can point at it.
            assertThat(fallback.unresolvedCharsetSpan()).isEqualTo(new SourceSpan(0,
                                                                                  "@charset \"nonsense\";".length()));

            assertThat(SourceText.decode("@charset \"utf-8\";a{}".getBytes(UTF_8)).unresolvedCharset()).isNull();
            assertThat(SourceText.decode("a{}".getBytes(UTF_8)).unresolvedCharset()).isNull();
        }

        @Test
        @DisplayName("a malformed @charset is no rule at all, not an unresolvable one")
        void aMalformedRuleIsNotReported() {
            // No terminating `;`: the sniff rejects the shape before any label exists.
            assertThat(SourceText.decode("@charset \"utf-8\"\na{}".getBytes(UTF_8)).unresolvedCharset()).isNull();
        }

        @Test
        @DisplayName("the replacement encoding discards its input, by design")
        void replacementLabelsDecodeToASingleReplacementCharacter() {
            CssEncoding encoding = CssEncoding.forLabel("iso-2022-kr");

            assertThat(encoding.kind()).isEqualTo(CssEncoding.Kind.REPLACEMENT);

            SourceText source = SourceText.decode("@charset \"iso-2022-kr\";a{}".getBytes(UTF_8));

            assertThat(source.toString()).isEqualTo("�");
        }

        @Test
        void xUserDefinedMapsHighBytesToThePrivateUseArea() {
            CssEncoding encoding = CssEncoding.forLabel("x-user-defined");

            assertThat(encoding.kind()).isEqualTo(CssEncoding.Kind.X_USER_DEFINED);

            SourceText source =
                SourceText.decode(concat("@charset \"x-user-defined\";".getBytes(UTF_8), new byte[] { (byte) 0x80 }));

            assertThat(source.toString()).endsWith("");
        }
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] joined = new byte[first.length + second.length];
        System.arraycopy(first, 0, joined, 0, first.length);
        System.arraycopy(second, 0, joined, first.length, second.length);
        return joined;
    }
}
