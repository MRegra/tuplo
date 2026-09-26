package dev.sobatista.tuplo.node;

import dev.sobatista.tuplo.cluster.ClusterControl;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The experiment console from the project statement: one place to drive a running system and inject faults, so you can
 * reproduce a scenario ("freeze replica 2 for 100ms, then take") on demand.
 *
 * <p>This runs against a {@link ClusterControl}, so the exact same command script drives either variant (SMR or XL) —
 * which is how you compare them fairly. Commands (one per line; {@code #} comments and blanks ignored):
 *
 * <pre>
 *   status                       # every node prints how many tuples it holds
 *   crash &lt;replica&gt;              # force a replica to crash
 *   freeze &lt;replica&gt;             # stop a replica processing messages (it still receives them)
 *   unfreeze &lt;replica&gt;           # resume; process what piled up
 *   client &lt;replica&gt; &lt;file&gt;      # run a .tuplo client script against that replica
 *   wait &lt;ms&gt;                    # sleep before the next command
 * </pre>
 *
 * <p>The statement's networked form launches real processes through a per-machine PCS over RMI; here the "nodes" are the
 * replicas of an in-process cluster, addressed by index. The command semantics are identical — see the ROADMAP for the
 * networked PCS.
 */
public final class PuppetMaster {

    private final ClusterControl cluster;

    public PuppetMaster(ClusterControl cluster) { this.cluster = cluster; }

    /** Run a command script line by line, synchronously (only {@code wait} sleeps). */
    public void run(List<String> lines) throws Exception {
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            exec(line);
        }
    }

    public void runFile(Path file) throws Exception { run(Files.readAllLines(file)); }

    private void exec(String line) throws Exception {
        String[] p = line.split("\\s+", 3);
        String cmd = p[0].toLowerCase();
        switch (cmd) {
            case "status" -> System.out.print(cluster.status());
            case "crash" -> { cluster.crash(idx(p, 1)); System.out.println("crashed replica " + p[1]); }
            case "freeze" -> { cluster.freeze(idx(p, 1)); System.out.println("froze replica " + p[1]); }
            case "unfreeze" -> { cluster.unfreeze(idx(p, 1)); System.out.println("unfroze replica " + p[1]); }
            case "wait" -> Thread.sleep(Long.parseLong(p[1].trim()));
            case "client" -> {
                int replica = idx(p, 1);
                if (p.length < 3) throw new IllegalArgumentException("client needs a script file: " + line);
                System.out.println("client on replica " + replica + " running " + p[2]);
                Script.fromFile(resolveScript(p[2].trim())).run(cluster.replicaSpace(replica));
            }
            default -> throw new IllegalArgumentException("unknown puppetmaster command: " + line);
        }
    }

    private int idx(String[] p, int i) {
        int v = Integer.parseInt(p[i].trim());
        if (v < 0 || v >= cluster.size()) throw new IllegalArgumentException("no replica " + v + " (size " + cluster.size() + ")");
        return v;
    }

    private static Path resolveScript(String s) throws IOException {
        Path p = Path.of(s);
        if (!Files.exists(p)) throw new IOException("script not found: " + s);
        return p;
    }
}
