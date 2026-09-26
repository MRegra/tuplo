package dev.sobatista.tuplo.node;

import dev.sobatista.tuplo.core.Fields;
import dev.sobatista.tuplo.core.TupleSpace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses and runs a client script against a {@link TupleSpace}. Commands (one per line):
 *
 * <pre>
 *   add &lt;field1, ..., fieldn&gt;
 *   read &lt;field1, ..., fieldn&gt;
 *   take &lt;field1, ..., fieldn&gt;
 *   wait x                 # sleep x milliseconds
 *   begin-repeat x         # repeat the block up to end-repeat x times (no nesting)
 *   end-repeat
 * </pre>
 *
 * Lines are executed top to bottom, synchronously, as the project requires. Blank lines and {@code #}
 * comments are ignored.
 */
public final class Script {

    /** One parsed line. */
    sealed interface Cmd permits Add, Read, Take, Wait, BeginRepeat, EndRepeat {}
    record Add(String body) implements Cmd {}
    record Read(String body) implements Cmd {}
    record Take(String body) implements Cmd {}
    record Wait(long ms) implements Cmd {}
    record BeginRepeat(int times) implements Cmd {}
    record EndRepeat() implements Cmd {}

    private final List<Cmd> commands;

    private Script(List<Cmd> commands) { this.commands = commands; }

    public static Script parse(List<String> lines) {
        List<Cmd> out = new ArrayList<>();
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            out.add(parseLine(line));
        }
        return new Script(out);
    }

    public static Script fromFile(Path file) throws IOException {
        return parse(Files.readAllLines(file));
    }

    private static Cmd parseLine(String line) {
        String lower = line.toLowerCase();
        if (lower.startsWith("add ")) return new Add(inside(line));
        if (lower.startsWith("read ")) return new Read(inside(line));
        if (lower.startsWith("take ")) return new Take(inside(line));
        if (lower.startsWith("wait ")) return new Wait(Long.parseLong(line.substring(5).trim()));
        if (lower.startsWith("begin-repeat ")) return new BeginRepeat(Integer.parseInt(line.substring(13).trim()));
        if (lower.equals("end-repeat")) return new EndRepeat();
        throw new IllegalArgumentException("unknown script command: " + line);
    }

    /** Strip the surrounding {@code < ... >} from an add/read/take line. */
    private static String inside(String line) {
        int lt = line.indexOf('<'), gt = line.lastIndexOf('>');
        if (lt < 0 || gt < lt) throw new IllegalArgumentException("expected <fields> in: " + line);
        return line.substring(lt + 1, gt);
    }

    /** Run the whole script against a space. */
    public void run(TupleSpace space) throws InterruptedException {
        int i = 0;
        while (i < commands.size()) {
            Cmd c = commands.get(i);
            if (c instanceof BeginRepeat br) {
                int end = findEndRepeat(i);
                List<Cmd> block = commands.subList(i + 1, end);
                for (int r = 0; r < br.times(); r++) runBlock(block, space);
                i = end + 1;
            } else {
                exec(c, space);
                i++;
            }
        }
    }

    private void runBlock(List<Cmd> block, TupleSpace space) throws InterruptedException {
        for (Cmd c : block) {
            if (c instanceof BeginRepeat) throw new IllegalStateException("nested begin-repeat is not allowed");
            exec(c, space);
        }
    }

    private int findEndRepeat(int from) {
        for (int j = from + 1; j < commands.size(); j++) {
            if (commands.get(j) instanceof EndRepeat) return j;
            if (commands.get(j) instanceof BeginRepeat) throw new IllegalStateException("nested begin-repeat is not allowed");
        }
        throw new IllegalStateException("begin-repeat without matching end-repeat");
    }

    private void exec(Cmd c, TupleSpace space) throws InterruptedException {
        switch (c) {
            case Add a -> space.add(Fields.parseTuple(a.body()));
            case Read r -> System.out.println("read  " + Fields.parseSchema(r.body()) + " -> " + space.read(Fields.parseSchema(r.body())));
            case Take t -> System.out.println("take  " + Fields.parseSchema(t.body()) + " -> " + space.take(Fields.parseSchema(t.body())));
            case Wait w -> Thread.sleep(w.ms());
            case EndRepeat ignored -> {}
            case BeginRepeat ignored -> throw new IllegalStateException("begin-repeat handled by run()");
        }
    }

    int size() { return commands.size(); }
}
