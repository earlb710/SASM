import java.nio.file.Files;
import java.nio.file.Paths;
import com.sasm.SasmTranslator;

public class Translator {
    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: java Translator <file.sasm>");
            System.exit(1);
        }
        String content = new String(Files.readAllBytes(Paths.get(args[0])));
        SasmTranslator translator = new SasmTranslator();
        String result = translator.translate(content);
        System.out.println(result);
    }
}
