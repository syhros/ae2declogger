import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/**
 * Builds one Minecraft version's sources from the shared tree: copies every source directory in order (later ones
 * replace files of earlier ones, so a version folder can override a whole file) and resolves the version blocks in
 * text files.
 *
 * <pre>
 * //#if MC >= 1.21.1 &amp;&amp; NEOFORGE
 * //$$ code for these versions, kept commented in the shared tree
 * //#elseif MC &lt; 1.19
 * //$$ ...
 * //#else
 * code for the version the shared tree is written for (Forge 1.20.1)
 * //#endif
 * </pre>
 *
 * Lines of the chosen branch lose their {@code //$$} prefix; lines of the other branches become empty, so line numbers
 * in errors and stack traces match the shared file. Blocks can be nested. Conditions use {@code MC} with
 * {@code == != < <= > >=} and a version, the loader names {@code FORGE} and {@code NEOFORGE}, {@code !}, {@code &&},
 * {@code ||} and parentheses.
 */
public abstract class PreprocessTask extends DefaultTask {

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getSources();

    @OutputDirectory
    public abstract DirectoryProperty getOutput();

    /** {@code MC} (a version like 1.20.1) and {@code LOADER} (forge or neoforge). */
    @Input
    public abstract MapProperty<String, String> getVars();

    @TaskAction
    public void run() throws IOException {
        Path out = getOutput().get().getAsFile().toPath();
        deleteTree(out);
        Files.createDirectories(out);
        Map<String, String> vars = getVars().get();
        for (File root : getSources().getFiles()) {
            if (!root.isDirectory()) {
                continue;
            }
            Path base = root.toPath();
            try (Stream<Path> walk = Files.walk(base)) {
                for (Path file : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
                    Path target = out.resolve(base.relativize(file).toString());
                    Files.createDirectories(target.getParent());
                    String name = file.getFileName().toString();
                    if (name.endsWith(".java") || name.endsWith(".json") || name.endsWith(".toml")) {
                        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                        Files.write(target, process(lines, vars, base.relativize(file).toString()),
                                StandardCharsets.UTF_8);
                    } else {
                        Files.copy(file, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        }
    }

    /** One block level: whether any branch was taken yet, and whether the current one is. */
    private record Level(boolean parentActive, boolean taken, boolean active) {
    }

    static List<String> process(List<String> lines, Map<String, String> vars, String where) {
        List<String> out = new ArrayList<>(lines.size());
        Deque<Level> stack = new ArrayDeque<>();
        boolean active = true;
        int n = 0;
        for (String line : lines) {
            n++;
            String t = line.trim();
            if (t.startsWith("//#")) {
                String directive = t.substring(3);
                String cond = "";
                int sp = directive.indexOf(' ');
                String word = sp < 0 ? directive : directive.substring(0, sp);
                if (sp >= 0) {
                    cond = directive.substring(sp + 1).trim();
                }
                switch (word) {
                    case "if" -> {
                        boolean c = active && eval(cond, vars, where, n);
                        stack.push(new Level(active, c, c));
                        active = c;
                    }
                    case "elseif" -> {
                        Level l = pop(stack, where, n, word);
                        boolean c = l.parentActive() && !l.taken() && eval(cond, vars, where, n);
                        stack.push(new Level(l.parentActive(), l.taken() || c, c));
                        active = c;
                    }
                    case "else" -> {
                        Level l = pop(stack, where, n, word);
                        boolean c = l.parentActive() && !l.taken();
                        stack.push(new Level(l.parentActive(), true, c));
                        active = c;
                    }
                    case "endif" -> active = pop(stack, where, n, word).parentActive();
                    default -> throw new GradleException(where + ":" + n + ": unknown directive //#" + word);
                }
                out.add("");
                continue;
            }
            if (!active) {
                out.add("");
            } else if (t.startsWith("//$$")) {
                int i = line.indexOf("//$$");
                String rest = line.substring(i + 4);
                out.add(line.substring(0, i) + (rest.startsWith(" ") ? rest.substring(1) : rest));
            } else {
                out.add(line);
            }
        }
        if (!stack.isEmpty()) {
            throw new GradleException(where + ": //#if without //#endif");
        }
        return out;
    }

    private static Level pop(Deque<Level> stack, String where, int n, String word) {
        if (stack.isEmpty()) {
            throw new GradleException(where + ":" + n + ": //#" + word + " without //#if");
        }
        return stack.pop();
    }

    // ---------------------------------------------------------------- conditions

    static boolean eval(String cond, Map<String, String> vars, String where, int line) {
        Parser p = new Parser(cond, vars, where + ":" + line);
        boolean v = p.or();
        p.skip();
        if (p.pos != cond.length()) {
            throw new GradleException(p.where + ": cannot read condition '" + cond + "'");
        }
        return v;
    }

    private static final class Parser {
        final String s;
        final Map<String, String> vars;
        final String where;
        int pos;

        Parser(String s, Map<String, String> vars, String where) {
            this.s = s;
            this.vars = vars;
            this.where = where;
        }

        void skip() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) {
                pos++;
            }
        }

        boolean eat(String token) {
            skip();
            if (s.startsWith(token, pos)) {
                pos += token.length();
                return true;
            }
            return false;
        }

        boolean or() {
            boolean v = and();
            while (eat("||")) {
                v |= and();
            }
            return v;
        }

        boolean and() {
            boolean v = unary();
            while (eat("&&")) {
                v &= unary();
            }
            return v;
        }

        boolean unary() {
            if (eat("!")) {
                return !unary();
            }
            if (eat("(")) {
                boolean v = or();
                if (!eat(")")) {
                    throw new GradleException(where + ": missing ) in '" + s + "'");
                }
                return v;
            }
            String word = word();
            if (word.equals("MC")) {
                String op = null;
                for (String o : new String[] { ">=", "<=", "==", "!=", ">", "<" }) {
                    if (eat(o)) {
                        op = o;
                        break;
                    }
                }
                if (op == null) {
                    throw new GradleException(where + ": MC needs a comparison in '" + s + "'");
                }
                skip();
                int start = pos;
                while (pos < s.length() && (Character.isDigit(s.charAt(pos)) || s.charAt(pos) == '.')) {
                    pos++;
                }
                int c = compareVersions(vars.get("MC"), s.substring(start, pos));
                return switch (op) {
                    case ">=" -> c >= 0;
                    case "<=" -> c <= 0;
                    case "==" -> c == 0;
                    case "!=" -> c != 0;
                    case ">" -> c > 0;
                    default -> c < 0;
                };
            }
            if (word.equals("FORGE") || word.equals("NEOFORGE")) {
                return word.equalsIgnoreCase(vars.get("LOADER"));
            }
            throw new GradleException(where + ": unknown name '" + word + "' in '" + s + "'");
        }

        String word() {
            skip();
            int start = pos;
            while (pos < s.length() && (Character.isLetterOrDigit(s.charAt(pos)) || s.charAt(pos) == '_')) {
                pos++;
            }
            if (start == pos) {
                throw new GradleException(where + ": cannot read condition '" + s + "'");
            }
            return s.substring(start, pos);
        }
    }

    /** 1.20.1 vs 26.1.2, part by part; missing parts count as 0. */
    static int compareVersions(String a, String b) {
        String[] x = a.split("\\.");
        String[] y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? Integer.parseInt(x[i]) : 0;
            int q = i < y.length ? Integer.parseInt(y[i]) : 0;
            if (p != q) {
                return Integer.compare(p, q);
            }
        }
        return 0;
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted((p, q) -> q.getNameCount() - p.getNameCount()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
    }
}
