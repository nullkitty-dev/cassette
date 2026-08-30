import dev.nullkitty.cassette.ast.Stylesheet;
import dev.nullkitty.cassette.parser.CssParser;
import dev.nullkitty.cassette.serializer.CssSerializer;
import dev.nullkitty.cassette.serializer.Formatting;
import dev.nullkitty.cassette.serializer.NestingExpansion;
import dev.nullkitty.cassette.serializer.NestingMode;
import dev.nullkitty.cassette.serializer.SerializerOptions;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Emits `input<TAB>once` for each generated sample, so two builds can be diffed.
 * Also flags inputs where `once` is not a fixed point on this build.
 */
public class Fuzz {

    static final SerializerOptions PRETTY = SerializerOptions.DEFAULTS;

    static final SerializerOptions MINIFIED = SerializerOptions.builder().formatting(Formatting.MINIFIED).build();

    static final SerializerOptions FLATTENED =
        SerializerOptions.builder().nesting(NestingMode.FLATTEN).formatting(Formatting.MINIFIED).build();

    static final SerializerOptions LEGACY = SerializerOptions.builder().legacyCompatible().build();

    static final List<SerializerOptions> ALL = List.of(PRETTY, MINIFIED, FLATTENED, LEGACY);

    public static final String[] PIECES = { "a{color:red}",
                                            ".c , #i { margin : 0 1px }",
                                            "@media print{a{top:0}}",
                                            "a{b:c\\",
                                            "url(has spaces and \" quote)",
                                            "@font-face{font-family:\"X\";src:url(a.woff2)}",
                                            "a{--x:{}}",
                                            "@layer base, components;",
                                            "@charset \"utf-16\";",
                                            "\"unterminated;",
                                            "a{b:c:root{--tok: 1px solid rgb(0 0 0 / 50%)}",
                                            "@a{b:c",
                                            "}",
                                            "{",
                                            "(",
                                            ")",
                                            ";",
                                            ".x{&:hover{top:0}}",
                                            "a[href^=\"http\"]:not(.x)::before{content:\"\"}",
                                            "\\",
                                            "/*x*/",
                                            "/* unterminated",
                                            "\n",
                                            " ",
                                            "\t",
                                            "@supports (display:grid){.g{display:grid}}",
                                            "a{color:}",
                                            ":is(:is(:is(",
                                            "1e5",
                                            ".caf\\e9 .x{top:0}",
                                            "﻿",
                                            "@a{b:c;;;;}",
                                            "url(",
                                            "'",
                                            "\"",
                                            "&",
                                            "|",
                                            "*|div{top:0}",
                                            "a{width:0.0px}",
                                            "#336699",
                                            "@a",
                                            "@a ",
                                            "@a;",
                                            "@media",
                                            "@a\\",
                                            "@a \\",
                                            "@a url(",
                                            "@a \"x",
                                            "@a/*c*/",
                                            "@import",
                                            "@import ",
                                            "@layer",
                                            "@a(",
                                            "@a[",
                                            "@a{",
                                            ":",
                                            "!important",
                                            "!",
                                            "a{b:}",
                                            "a{:c}",
                                            "a{b:c!important}",
                                            "@a \"unterminated;",
                                            "@a\n",
                                            "url(x",
                                            "a{b:url(}",
                                            "@a )",
                                            "@a ,",
                                            "[",
                                            "]",
                                            "@a \\\n",
                                            "\\\n",
                                            "c\\\n",

                                            // Seams: something that writes text, then whitespace, then something that writes
                                            // nothing. Everything above is an atom, and concatenating atoms reaches a seam only
                                            // by accident — see the README. These are one fragment each on purpose.
                                            "a{b:c url(\"",
                                            "a{b:c \"",
                                            "a{b:c \\",
                                            "@a c url(\"",
                                            "@a c \"",
                                            "@a c \\",
                                            "x url(\"",
                                            "a{b:c url(x y)}",
                                            "a{b:c \"x\n}",
                                            "a{b:c \\\n}",
                                            "@a c url(x y);",
                                            "@a c \"x\n;",
                                            "@a{b:c url(x y)}",
                                            "@media print{a{b:c url(x y)}}",
                                            "a{b:c url(x y) d}",
                                            "a{b:c \\\n d}",
                                            "a{b:f(c url(x y))}",
                                            "a{b:f(url(x y) )}",
                                            "a{b:[c \\\n]}",
                                            "@a url(\"\n)",
                                            "url(\"x\" y)",
                                            "a{b:c url(x y)!important}",
                                            "a{b:c url(x y)/*c*/}",
                                            "a{b:c url(x y);d:e}",
                                            "a url(x y){top:0}", };

    public static void main(String[] args) throws Exception {
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        Random random = new Random(Long.parseLong(args[0]));
        int count = Integer.parseInt(args[1]);
        for (int i = 0; i < count; i++) {
            StringBuilder b = new StringBuilder();

            int parts = 1 + random.nextInt(6);
            for (int j = 0; j < parts; j++) {
                b.append(PIECES[random.nextInt(PIECES.length)]);
            }

            List<String> row = new ArrayList<>();
            String input = b.toString();
            row.add(esc(input));
            for (SerializerOptions o : ALL) {
                String once;
                String twice;
                try {
                    once = CssSerializer.serialize(CssParser.parse(input).ast(), o);
                    twice = CssSerializer.serialize(CssParser.parse(once).ast(), o);
                }
                catch (RuntimeException e) {
                    once = "THREW " + e.getClass().getSimpleName();
                    twice = once;
                }
                row.add(esc(once));
                row.add(once.equals(twice) ? "fix" : "NOTFIX");
            }

            out.println(String.join("\t", row));
        }
    }

    public static String esc(String s) {
        StringBuilder b = new StringBuilder();

        for (char c : s.toCharArray()) {
            if (c == '\n') {
                b.append("\\n");
            }
            else if (c == '\t') {
                b.append("\\t");
            }
            else if (c < 0x20 || c > 0x7e) {
                b.append(String.format("\\u%04x", (int) c));
            }
            else {
                b.append(c);
            }
        }

        return b.toString();
    }
}
