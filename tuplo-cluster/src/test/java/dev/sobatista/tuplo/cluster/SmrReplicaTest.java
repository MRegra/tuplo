package dev.sobatista.tuplo.cluster;

import dev.sobatista.tuplo.core.Tuple;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static dev.sobatista.tuplo.core.Fields.parseSchema;
import static dev.sobatista.tuplo.core.Fields.parseTuple;
import static org.junit.jupiter.api.Assertions.*;

class SmrReplicaTest {

    @Test @Timeout(10)
    void writeOnOneReplicaIsVisibleOnAnother() throws Exception {
        var c = new Cluster(3);
        c.replica(0).add(parseTuple("\"hello\", \"world\""));
        // every replica applied the same add, so any of them can serve it
        assertEquals(parseTuple("\"hello\", \"world\""), c.replica(2).read(parseSchema("\"hello\", \"*\"")));
        assertEquals(1, c.replica(0).size());
        assertEquals(1, c.replica(1).size());
        assertEquals(1, c.replica(2).size());
    }

    @Test @Timeout(10)
    void takeRemovesEverywhereAndOnlyOnce() throws Exception {
        var c = new Cluster(3);
        c.replica(0).add(parseTuple("\"job\", \"x\""));
        assertEquals(parseTuple("\"job\", \"x\""), c.replica(1).take(parseSchema("\"job\", \"*\"")));
        // the take was ordered and applied on all three replicas
        assertEquals(0, c.replica(0).size());
        assertEquals(0, c.replica(1).size());
        assertEquals(0, c.replica(2).size());
    }

    @Test @Timeout(10)
    void blockingTakeResolvesAfterAddOnAnotherReplica() throws Exception {
        var c = new Cluster(3);
        var got = new AtomicReference<Tuple>();
        var started = new CountDownLatch(1);
        var t = new Thread(() -> {
            try {
                started.countDown();
                got.set(c.replica(2).take(parseSchema("\"ready\"")));
            } catch (InterruptedException ignored) {}
        });
        t.start();
        started.await();
        Thread.sleep(150);
        assertNull(got.get(), "take should be parked: nothing matches yet");
        c.replica(0).add(parseTuple("\"ready\""));       // add on a DIFFERENT replica
        t.join(3000);
        assertEquals(parseTuple("\"ready\""), got.get());
        for (int i = 0; i < 3; i++) assertEquals(0, c.replica(i).size());
    }

    @Test @Timeout(20)
    void concurrentTakesAcrossReplicasEachGetADistinctTuple() throws Exception {
        var c = new Cluster(3);
        int n = 60;
        var wins = new java.util.concurrent.ConcurrentHashMap<String, Boolean>();
        var done = new CountDownLatch(n);
        for (int i = 0; i < n; i++) {
            int r = i % 3;
            new Thread(() -> {
                try {
                    Tuple got = c.replica(r).take(parseSchema("\"item\", \"*\""));
                    wins.put(got.toString(), Boolean.TRUE);   // distinct payloads -> distinct keys
                } catch (InterruptedException ignored) {
                } finally {
                    done.countDown();
                }
            }).start();
        }
        for (int i = 0; i < n; i++) c.replica(i % 3).add(parseTuple("\"item\", \"" + i + "\""));
        assertTrue(done.await(15, TimeUnit.SECONDS));
        assertEquals(n, wins.size(), "no tuple was taken twice across the cluster");
        for (int i = 0; i < 3; i++) assertEquals(0, c.replica(i).size(), "replica " + i + " converged to empty");
    }

    @Test @Timeout(10)
    void allReplicasConvergeToIdenticalState() throws Exception {
        var c = new Cluster(4);
        c.replica(0).add(parseTuple("\"a\""));
        c.replica(1).add(parseTuple("\"b\""));
        c.replica(2).add(parseTuple("\"a\""));
        c.replica(3).take(parseSchema("\"a\""));         // removes the oldest "a" on every replica
        int s0 = c.replica(0).size();
        for (int i = 1; i < 4; i++) assertEquals(s0, c.replica(i).size(), "replica " + i + " size diverged");
        assertEquals(2, s0);                             // one "a" and one "b" remain
    }
}
