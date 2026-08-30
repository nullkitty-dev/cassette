import dev.nullkitty.cassette.ast.Stylesheet;
import dev.nullkitty.cassette.parser.CssParser;
import dev.nullkitty.cassette.serializer.CssSerializer;
import dev.nullkitty.cassette.serializer.Formatting;
import dev.nullkitty.cassette.serializer.NestingExpansion;
import dev.nullkitty.cassette.serializer.NestingMode;
import dev.nullkitty.cassette.serializer.Optimizations;
import dev.nullkitty.cassette.serializer.SerializerOptions;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;

/** The optimize path of SerializerPropertiesTest.optimizedOutputIsStillIdempotent. */
public class FuzzOpt {

    static final SerializerOptions OPTIONS = SerializerOptions.builder() //
                                                              .nesting(NestingMode.FLATTEN) //
                                                              .nestingExpansion(NestingExpansion.IS_WRAP) //
                                                              .formatting(Formatting.MINIFIED) //
                                                              .build();

    static Method optimizer;

    static {
        try {
            optimizer = Class.forName("dev.nullkitty.cassette.serializer.Optimizer")
                             .getMethod("optimize", Stylesheet.class, List.class);
        }
        catch (ReflectiveOperationException e) {
            try {
                optimizer = Class.forName("dev.nullkitty.cassette.serializer.Minifier")
                                 .getMethod("minify", Stylesheet.class, List.class);
            }
            catch (ReflectiveOperationException e2) {
                throw new ExceptionInInitializerError(e2);
            }
        }
    }

    static String optimize(String css) throws Exception {
        Stylesheet ast = (Stylesheet) optimizer.invoke(null, CssParser.parse(css).ast(), Optimizations.all());
        return CssSerializer.serialize(ast, OPTIONS);
    }

    public static void main(String[] args) throws Exception {
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        Random random = new Random(Long.parseLong(args[0]));
        int count = Integer.parseInt(args[1]);
        for (int i = 0; i < count; i++) {
            StringBuilder b = new StringBuilder();
            int parts = 1 + random.nextInt(6);
            for (int j = 0; j < parts; j++) {
                b.append(Fuzz.PIECES[random.nextInt(Fuzz.PIECES.length)]);
            }

            String input = b.toString();
            String once;
            String twice;

            try {
                once = optimize(input);
                twice = optimize(once);
            }
            catch (Exception e) {
                once = "THREW " + e.getCause();
                twice = once;
            }

            out.println(Fuzz.esc(input) + "\t" + Fuzz.esc(once) + "\t" + (once.equals(twice) ? "fix" : "NOTFIX"));
        }
    }
}
