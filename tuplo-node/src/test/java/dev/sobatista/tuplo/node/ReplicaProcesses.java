package dev.sobatista.tuplo.node;

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;
import java.util.concurrent.TimeUnit;

/**
 * Test fixture: forks {@code n} {@link ReplicaNode} JVMs on free loopback ports and gives back a {@link RemoteCluster}
 * to drive them. Each process logs to {@code target/replica-logs/<test>-r<i>.log} so a failure can be diagnosed.
 * {@link #close()} kills whatever is still running.
 */
final class ReplicaProcesses implements AutoCloseable {

    private final List<Process> processes = new ArrayList<>();
    private final List<Integer> ports = new ArrayList<>();
    private final String nodes;
    private final RemoteCluster cluster;

    ReplicaProcesses(ReplicaNode.Variant variant, int n, String testName) throws Exception {
        this(variant, n, testName, 0, 0);
    }

    /** Same, with every replica delaying each incoming message by a random {@code [delayMin, delayMax]} ms. */
    ReplicaProcesses(ReplicaNode.Variant variant, int n, String testName, int delayMin, int delayMax) throws Exception {
        StringJoiner addresses = new StringJoiner(",");
        for (int i = 0; i < n; i++) {
            int port = freePort();
            ports.add(port);
            addresses.add("localhost:" + port + "/r" + i);
        }
        this.nodes = addresses.toString();

        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Path logs = Files.createDirectories(Path.of("target", "replica-logs"));
        try {
            for (int i = 0; i < n; i++) {
                var pb = new ProcessBuilder(java, "-cp", classpath, "-Djava.rmi.server.hostname=localhost",
                        ReplicaNode.class.getName(),
                        "--variant", variant.name().toLowerCase(), "--id", String.valueOf(i), "--peers", nodes,
                        "--join-timeout-ms", "30000",
                        "--delay-min", String.valueOf(delayMin), "--delay-max", String.valueOf(delayMax));
                File log = logs.resolve(testName + "-r" + i + ".log").toFile();
                pb.redirectErrorStream(true).redirectOutput(log);
                processes.add(pb.start());
            }
            this.cluster = new RemoteCluster(nodes);
            cluster.awaitReady(30_000);
        } catch (Exception | Error e) {
            close();
            throw e;
        }
    }

    RemoteCluster cluster() { return cluster; }

    String nodes() { return nodes; }

    /** RMI registry port of replica {@code i} (its name is {@code r<i>}). */
    int port(int i) { return ports.get(i); }

    /** True if replica {@code i}'s process has exited within the timeout. */
    boolean exited(int i, long timeoutMs) throws InterruptedException {
        return processes.get(i).waitFor(timeoutMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() {
        for (Process p : processes) p.destroyForcibly();
        for (Process p : processes) {
            try { p.waitFor(10, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    private static int freePort() throws IOException {
        try (var s = new ServerSocket(0)) { return s.getLocalPort(); }
    }
}
