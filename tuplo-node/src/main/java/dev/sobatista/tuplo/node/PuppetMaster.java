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
 * <p>Two modes, same commands. In-process, the "nodes" are the replicas of a {@link dev.sobatista.tuplo.cluster.Cluster}
 * or {@link dev.sobatista.tuplo.cluster.XlCluster}. Networked ({@link RemoteCluster}, or {@link #main}), they are
 * {@link ReplicaNode} processes reached over RMI, addressed by their index in the {@code --nodes} list, and
 * {@code crash} really kills the process. Launching processes remotely through a per-machine PCS is on the ROADMAP.
 *
 * <pre>{@code
 *   java -cp ... dev.sobatista.tuplo.node.PuppetMaster \
 *        --nodes localhost:11000/r0,localhost:11001/r1,localhost:11002/r2 [--script examples/experiment.pm]
 * }</pre>
 * Without {@code --script} it reads commands from the console, one per line.
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

    /** Networked mode: drive running {@link ReplicaNode} processes from a script file or the console. */
    public static void main(String[] argv) throws Exception {
        var a = Args.parse(argv);
        var cluster = new RemoteCluster(a.require("nodes"));
        var pm = new PuppetMaster(cluster);
        String script = a.get("script", null);
        if (script != null) {
            pm.runFile(resolveScript(script));
            return;
        }
        var in = new java.io.BufferedReader(new java.io.InputStreamReader(System.in, java.nio.charset.StandardCharsets.UTF_8));
        System.out.println("puppetmaster> commands: status | crash i | freeze i | unfreeze i | client i file | wait ms");
        for (String line; (line = in.readLine()) != null; ) {
            try {
                pm.run(List.of(line));
            } catch (Exception e) {
                System.out.println("error: " + e.getMessage());
            }
        }
    }

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
