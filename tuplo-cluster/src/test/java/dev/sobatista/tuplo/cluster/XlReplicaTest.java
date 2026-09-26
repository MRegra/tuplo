package dev.sobatista.tuplo.cluster;

import dev.sobatista.tuplo.core.Tuple;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static dev.sobatista.tuplo.core.Fields.parseSchema;
import static dev.sobatista.tuplo.core.Fields.parseTuple;
import static org.junit.jupiter.api.Assertions.*;

/** XL-specific guarantees: full replication, exactly-once take across replicas, and blocking across replicas. */
class XlReplicaTest {

    @Test @Timeout(10)
    void addOnOneReplicaIsReadableEverywhere() throws Exception {
        var c = new XlCluster(3);
        c.replica(0).add(parseTuple("\"k\", \"v\""));
        for (int i = 0; i < 3; i++) {
            assertEquals(parseTuple("\"k\", \"v\""), c.replica(i).read(parseSchema("\"k\", \"*\"")),
                    "replica " + i + " should hold the tuple (full replication)");
        }
    }

    @Test @Timeout(10)
    void takeRemovesFromEveryReplica() throws Exception {
        var c = new XlCluster(3);
        c.replica(0).add(parseTuple("\"once\""));
        assertEquals(parseTuple("\"once\""), c.replica(2).take(parseSchema("\"once\"")));
        for (int i = 0; i < 3; i++) assertEquals(0, c.replica(i).size(), "replica " + i + " still has it");
    }

    @Test @Timeout(20)
    void concurrentTakesAcrossReplicasNeverDoubleTake() throws Exception {
        var c = new XlCluster(3);
        int n = 60;
        var seen = new ConcurrentHashMap<String, Boolean>();
        var done = new CountDownLatch(n);
        for (int i = 0; i < n; i++) {
            int r = i % 3;
            new Thread(() -> {
                try { seen.put(c.replica(r).take(parseSchema("\"item\", \"*\"")).toString(), Boolean.TRUE); }
                catch (InterruptedException ignored) {} finally { done.countDown(); }
            }).start();
        }
        for (int i = 0; i < n; i++) c.replica(i % 3).add(parseTuple("\"item\", \"" + i + "\""));
        assertTrue(done.await(15, TimeUnit.SECONDS));
        assertEquals(n, seen.size(), "each tuple taken exactly once across the whole cluster");
        for (int i = 0; i < 3; i++) assertEquals(0, c.replica(i).size());
    }

    @Test @Timeout(10)
    void blockingTakeResolvesFromAddOnAnotherReplica() throws Exception {
        var c = new XlCluster(3);
        var got = new AtomicReference<Tuple>();
        var started = new CountDownLatch(1);
        var t = new Thread(() -> {
            try { started.countDown(); got.set(c.replica(1).take(parseSchema("\"go\""))); } catch (InterruptedException ignored) {}
        });
        t.start();
        started.await();
        Thread.sleep(150);
        assertNull(got.get());
        c.replica(0).add(parseTuple("\"go\""));
        t.join(4000);
        assertEquals(parseTuple("\"go\""), got.get());
    }
}
