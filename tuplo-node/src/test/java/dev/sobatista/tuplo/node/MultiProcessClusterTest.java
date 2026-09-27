package dev.sobatista.tuplo.node;

import dev.sobatista.tuplo.core.Tuple;
import dev.sobatista.tuplo.core.TupleSpace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static dev.sobatista.tuplo.core.Fields.parseSchema;
import static dev.sobatista.tuplo.core.Fields.parseTuple;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The real thing: three replica <em>processes</em> (separate JVMs, talking RMI over loopback), for both variants.
 * Covers the statement's core claims end to end: replicas converge, a crashed replica (including the SMR sequencer /
 * the XL coordinator) loses no tuples, and a frozen replica receives but defers messages, then catches up.
 * Faults are injected through the networked PuppetMaster, the way an experiment would.
 */
class MultiProcessClusterTest {

    private static final long CONVERGE_MS = 15_000;

    @ParameterizedTest @EnumSource(ReplicaNode.Variant.class) @Timeout(120)
    void replicasInSeparateProcessesConverge(ReplicaNode.Variant variant) throws Exception {
        try (var procs = new ReplicaProcesses(variant, 3, "converge-" + variant)) {
            var c = procs.cluster();
            TupleSpace r0 = c.replicaSpace(0), r1 = c.replicaSpace(1), r2 = c.replicaSpace(2);

            // a blocking take parked on replica 0 is released by an add on replica 2
            var parked = CompletableFuture.supplyAsync(() -> uncheckedTake(r0, "\"ready\""));
            Thread.sleep(300);
            assertFalse(parked.isDone(), "take must block until a match exists");
            r2.add(parseTuple("\"ready\""));
            assertEquals(parseTuple("\"ready\""), parked.get(20, TimeUnit.SECONDS));

            // concurrent writers on every replica
            List<CompletableFuture<Void>> writers = new ArrayList<>();
            for (int w = 0; w < 3; w++) {
                TupleSpace target = c.replicaSpace(w);
                int writer = w;
                writers.add(CompletableFuture.runAsync(() -> {
                    for (int k = 0; k < 5; k++) target.add(parseTuple("\"job\", \"w" + writer + "\", \"k" + k + "\""));
                }));
            }
            CompletableFuture.allOf(writers.toArray(CompletableFuture[]::new)).get(30, TimeUnit.SECONDS);

            // read doesn't remove; takes from different replicas remove exactly once
            assertNotNull(r1.read(parseSchema("\"job\", \"w1\", \"*\"")));
            Tuple a = r1.take(parseSchema("\"job\", \"*\", \"k0\""));
            Tuple b = r2.take(parseSchema("\"job\", \"*\", \"k0\""));
            assertNotEquals(a, b, "two takes must not get the same copy");

            awaitConverged(variant, c, List.of(0, 1, 2), 13);
        }
    }

    @ParameterizedTest @EnumSource(ReplicaNode.Variant.class) @Timeout(120)
    void crashOfAPlainReplicaLosesNothing(ReplicaNode.Variant variant) throws Exception {
        try (var procs = new ReplicaProcesses(variant, 3, "crash2-" + variant)) {
            var c = procs.cluster();
            c.replicaSpace(2).add(parseTuple("\"from\", \"two\""));
            c.replicaSpace(0).add(parseTuple("\"from\", \"zero\""));

            new PuppetMaster(c).run(List.of("crash 2", "status"));
            assertTrue(procs.exited(2, 10_000), "crash must really stop the process");

            // survivors keep serving: the victim's tuple is still there and takeable exactly once
            assertEquals(parseTuple("\"from\", \"two\""), c.replicaSpace(1).take(parseSchema("\"from\", \"two\"")));
            c.replicaSpace(1).add(parseTuple("\"after\", \"crash\""));
            awaitConverged(variant, c, List.of(0, 1), 2);
            assertTrue(c.status().contains("unreachable"), c.status());
        }
    }

    @ParameterizedTest @EnumSource(ReplicaNode.Variant.class) @Timeout(120)
    void crashOfTheSequencerOrCoordinatorLosesNothing(ReplicaNode.Variant variant) throws Exception {
        try (var procs = new ReplicaProcesses(variant, 3, "crash0-" + variant)) {
            var c = procs.cluster();
            // replica 0 is the SMR sequencer and the XL coordinator of the tuples it creates
            c.replicaSpace(0).add(parseTuple("\"owned\", \"by0\""));
            c.replicaSpace(1).add(parseTuple("\"owned\", \"by1\""));

            c.crash(0);
            assertTrue(procs.exited(0, 10_000), "crash must really stop the process");

            // replica 1 takes over (sequencer / coordinator); the orphaned tuple is taken exactly once
            assertEquals(parseTuple("\"owned\", \"by0\""), c.replicaSpace(1).take(parseSchema("\"owned\", \"by0\"")));
            c.replicaSpace(2).add(parseTuple("\"new\", \"era\""));
            assertEquals(parseTuple("\"new\", \"era\""), c.replicaSpace(1).read(parseSchema("\"new\", \"*\"")));
            awaitConverged(variant, c, List.of(1, 2), 2);
            assertTrue(c.control(1).status().contains("presumed failed [0]"), c.control(1).status());
        }
    }

    /**
     * Regression (F-0603 adversary review): the XL coordinator's grant record used to live only on the coordinator
     * itself. If it crashed right after granting a tuple but before the taker's removal had reached every replica,
     * gossip about the crash could beat the (delayed) removal, promote a successor coordinator with no record of the
     * grant, and that successor would hand the same tuple to a second taker. Concurrent takers plus message delay
     * make the window routine: reproduced in 4 of 10 runs before the fix with these exact parameters. The fix
     * replicates the coordinator's grant decision to every active replica before it answers, so a successor already
     * knows the tuple is taken and refuses to grant it again.
     */
    @Test @Timeout(90)
    void xlCoordinatorCrashDuringConcurrentTakesNeverDoubleGrants() throws Exception {
        int n = 20;
        try (var procs = new ReplicaProcesses(ReplicaNode.Variant.XL, 3, "xl-grant-race", 20, 60)) {
            var c = procs.cluster();
            for (int i = 0; i < n; i++) c.replicaSpace(0).add(parseTuple("\"t\", \"k" + i + "\""));
            Thread.sleep(2000);                                       // let every replica settle on all n tuples

            List<CompletableFuture<Tuple>> takers = new ArrayList<>();
            var pool = Executors.newVirtualThreadPerTaskExecutor();
            for (int t = 0; t < n; t++) {
                TupleSpace s = c.replicaSpace(1 + t % 2);               // alternate replicas 1 and 2; never replica 0
                takers.add(CompletableFuture.supplyAsync(() -> uncheckedTake(s, "\"t\", \"*\""), pool));
            }
            Thread.sleep(250);
            c.crash(0);
            assertTrue(procs.exited(0, 10_000), "crash must really stop the process");

            List<Tuple> got = new ArrayList<>();
            try {
                for (var f : takers) got.add(f.get(60, TimeUnit.SECONDS));
            } catch (TimeoutException stuck) {
                fail("a taker never completed (a tuple is likely stranded, granted but never removed): " + got);
            }
            assertEquals(n, new HashSet<>(got).size(), "a tuple was taken twice: " + got);
        }
    }

    @ParameterizedTest @EnumSource(ReplicaNode.Variant.class) @Timeout(120)
    void frozenReplicaDefersThenCatchesUp(ReplicaNode.Variant variant) throws Exception {
        try (var procs = new ReplicaProcesses(variant, 3, "freeze-" + variant)) {
            var c = procs.cluster();
            new PuppetMaster(c).run(List.of("freeze 2"));
            assertTrue(c.control(2).status().contains("FROZEN"));

            // SMR: the write completes (the frozen replica buffers it). XL: add waits for every active replica's
            // reply (Xu-Liskov), so it completes only once replica 2 thaws. Either way replica 2 applies nothing yet.
            var write = CompletableFuture.runAsync(() -> c.replicaSpace(0).add(parseTuple("\"while\", \"frozen\"")));
            if (variant == ReplicaNode.Variant.SMR) write.get(20, TimeUnit.SECONDS);
            else assertThrows(TimeoutException.class, () -> write.get(1, TimeUnit.SECONDS));
            Thread.sleep(300);
            assertEquals(List.of(), c.snapshot(2), "a frozen replica receives but does not process");
            assertTrue(c.snapshot(1).contains(parseTuple("\"while\", \"frozen\"")));

            new PuppetMaster(c).run(List.of("wait 100", "unfreeze 2"));
            write.get(20, TimeUnit.SECONDS);
            awaitConverged(variant, c, List.of(0, 1, 2), 1);
        }
    }

    @ParameterizedTest @EnumSource(ReplicaNode.Variant.class) @Timeout(120)
    void delayedMessagesAndTheScriptClientStillConverge(ReplicaNode.Variant variant) throws Exception {
        try (var procs = new ReplicaProcesses(variant, 3, "delay-" + variant, 1, 15)) {
            var c = procs.cluster();
            var script = Files.createTempFile("client", ".tuplo");
            Files.writeString(script, """
                    add <"job", "a">
                    add <"job", "b">
                    begin-repeat 2
                    take <"job", "*">
                    end-repeat
                    add <"done">
                    """);
            try {
                // meanwhile another client writes through replica 2, so delayed messages from two origins interleave
                var other = CompletableFuture.runAsync(() -> {
                    for (int k = 0; k < 5; k++) c.replicaSpace(2).add(parseTuple("\"bg\", \"k" + k + "\""));
                });
                ScriptClient.main(new String[]{"--host", "localhost", "--port", String.valueOf(procs.port(1)),
                        "--name", "r1", "--script", script.toString()});
                other.get(30, TimeUnit.SECONDS);
            } finally {
                Files.deleteIfExists(script);
            }
            awaitConverged(variant, c, List.of(0, 1, 2), 6);
            assertTrue(c.snapshot(0).contains(parseTuple("\"done\"")));
        }
    }

    @Test @Timeout(120)
    void puppetMasterMainDrivesANetworkedClusterFromAScript() throws Exception {
        try (var procs = new ReplicaProcesses(ReplicaNode.Variant.SMR, 3, "pm-main")) {
            var client = Files.createTempFile("client", ".tuplo");
            var experiment = Files.createTempFile("experiment", ".pm");
            Files.writeString(client, "add <\"hello\", \"net\">\nread <\"hello\", \"*\">\n");
            Files.writeString(experiment, "client 1 " + client + "\nfreeze 2\nwait 50\nunfreeze 2\nstatus\n");
            PrintStream original = System.out;
            var captured = new ByteArrayOutputStream();
            try {
                System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
                PuppetMaster.main(new String[]{"--nodes", procs.nodes(), "--script", experiment.toString()});
            } finally {
                System.setOut(original);
                Files.deleteIfExists(client);
                Files.deleteIfExists(experiment);
            }
            String out = captured.toString(StandardCharsets.UTF_8);
            assertTrue(out.contains("replica 2 (SMR): 1 tuple(s)"), out);
            awaitConverged(ReplicaNode.Variant.SMR, procs.cluster(), List.of(0, 1, 2), 1);
        }
    }

    // ---- helpers ------------------------------------------------------------

    private static Tuple uncheckedTake(TupleSpace space, String schema) {
        try { return space.take(parseSchema(schema)); } catch (InterruptedException e) { throw new IllegalStateException(e); }
    }

    /**
     * Wait until the given replicas hold the same tuples. SMR promises the same <em>sequence</em> (one agreed order);
     * XL only the same <em>multiset</em> (adds aren't ordered), so XL snapshots are compared order-insensitively.
     */
    private static void awaitConverged(ReplicaNode.Variant variant, RemoteCluster c, List<Integer> replicas, int expectedSize)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + CONVERGE_MS;
        List<List<Tuple>> snaps;
        while (true) {
            snaps = new ArrayList<>();
            for (int r : replicas) snaps.add(normalise(variant, c.snapshot(r)));
            boolean same = snaps.stream().distinct().count() == 1 && snaps.getFirst().size() == expectedSize;
            if (same) return;
            if (System.currentTimeMillis() > deadline) break;
            Thread.sleep(100);
        }
        fail("replicas " + replicas + " did not converge to " + expectedSize + " tuple(s): " + snaps);
    }

    private static List<Tuple> normalise(ReplicaNode.Variant variant, List<Tuple> snapshot) {
        if (variant == ReplicaNode.Variant.SMR) return snapshot;
        return snapshot.stream().sorted(Comparator.comparing(Tuple::toString)).toList();
    }
}
