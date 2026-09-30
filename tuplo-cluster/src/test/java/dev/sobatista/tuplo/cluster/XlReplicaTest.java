package dev.sobatista.tuplo.cluster;

import dev.sobatista.tuplo.core.Tuple;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static dev.sobatista.tuplo.core.Fields.parseSchema;
import static dev.sobatista.tuplo.core.Fields.parseTuple;
import static org.junit.jupiter.api.Assertions.*;

/** XL-specific guarantees: full replication, exactly-once take across replicas, and blocking across replicas. */
class XlReplicaTest {

    @Test void faultsGetterExposesTheReplicasOwnFaultSwitches() {
        var c = new XlCluster(1);
        assertSame(c.replica(0).faults, c.replica(0).faults());
    }

    @Test void snapshotIsAnOldestFirstCopy() throws Exception {
        var c = new XlCluster(1);
        c.replica(0).add(parseTuple("\"first\""));
        c.replica(0).add(parseTuple("\"second\""));
        assertEquals(java.util.List.of(parseTuple("\"first\""), parseTuple("\"second\"")), c.replica(0).snapshot());
    }

    @Test void snapshotIsAnImmutableCopy() throws Exception {
        var c = new XlCluster(1);
        c.replica(0).add(parseTuple("\"a\""));
        var snap = c.replica(0).snapshot();
        assertThrows(UnsupportedOperationException.class, () -> snap.add(parseTuple("\"b\"")));
    }

    @Test void tryReadReturnsMatchWithoutRemoving() throws Exception {
        var c = new XlCluster(2);
        c.replica(0).add(parseTuple("\"a\""));
        assertEquals(parseTuple("\"a\""), c.replica(1).tryRead(parseSchema("\"*\"")).orElseThrow());
        assertEquals(1, c.replica(1).size(), "tryRead must not remove");
    }

    @Test void tryReadReturnsEmptyWhenNoMatchAmongExistingTuples() throws Exception {
        var c = new XlCluster(1);
        c.replica(0).add(parseTuple("\"a\""));               // a non-empty store...
        assertTrue(c.replica(0).tryRead(parseSchema("\"nomatch\"")).isEmpty(), "...that still has no match");
    }

    @Test void hasTupleReflectsWhatWasStored() throws Exception {
        var c = new XlCluster(1);
        c.replica(0).add(parseTuple("\"a\""));
        var tid = new XlReplica.TupleId(0, 0);               // replica 0's first add
        assertTrue(c.replica(0).hasTuple(tid));
        assertFalse(c.replica(0).hasTuple(new XlReplica.TupleId(0, 999)));
    }

    @Test @Timeout(10)
    void tryTakeReturnsEmptyWhenTheOnlyMatchIsAlreadyGrantedToAnotherLiveReplica() throws Exception {
        var c = new XlCluster(2);
        c.replica(0).add(parseTuple("\"x\""));
        var tid = new XlReplica.TupleId(0, 0);               // replica 0 is the coordinator (origin)
        var otherReq = new XlReplica.ReqId(1, 0);
        assertTrue(c.replica(0).grant(tid, otherReq, Set.of()), "replica 1's request reserves the tuple first");
        assertTrue(c.replica(0).tryTake(parseSchema("\"x\"")).isEmpty(),
                "already granted to a different, still-active replica: tryTake must not steal it");
    }

    @Test @Timeout(10)
    void receiveStoreOnAFrozenReplicaSilentlyDropsAfterInterrupt() throws Exception {
        var c = new XlCluster(2);
        c.freeze(1);
        var t = new Thread(() -> c.replica(0).add(parseTuple("\"a\"")), "add-into-frozen-then-interrupted");
        t.start();
        Await.state(t, Thread.State.WAITING, 2000);
        t.interrupt();
        t.join(2000);
        assertFalse(t.isAlive());
        assertEquals(0, c.replica(1).size(), "the interrupted receiveStore must have silently dropped the tuple");
    }

    @Test @Timeout(10)
    void receiveStoreOnAFrozenReplicaSilentlyDropsAfterCrash() throws Exception {
        var c = new XlCluster(2);
        c.freeze(1);
        var t = new Thread(() -> c.replica(0).add(parseTuple("\"a\"")), "add-into-frozen-then-crashed");
        t.start();
        Await.state(t, Thread.State.WAITING, 2000);
        c.replica(1).faults.crash();
        t.join(2000);
        assertFalse(t.isAlive());
        assertEquals(0, c.replica(1).size(), "a replica that crashes while frozen must drop what was pending, not apply it");
    }

    @Test @Timeout(10)
    void addOnOneReplicaIsReadableEverywhere() throws Exception {
        var c = new XlCluster(3);
        c.replica(0).add(parseTuple("\"k\", \"v\""));
        for (int i = 0; i < 3; i++) {
            assertEquals(parseTuple("\"k\", \"v\""), c.replica(i).read(parseSchema("\"k\", \"*\"")),
                    "replica " + i + " should hold the tuple (full replication)");
        }
    }

    /**
     * Regression: across processes the add multicast is not atomic, so a taker can see a tuple before its coordinator
     * has stored it. The coordinator used to refuse that grant, and the taker then waited forever for a state change
     * that never came (found by the multi-process XL test).
     */
    @Test @Timeout(10)
    void takeSucceedsWhenTheCoordinatorHasNotStoredTheTupleYet() throws Exception {
        var c = new XlCluster(3);
        var tid = new XlReplica.TupleId(0, 42);                  // replica 0 is the coordinator (origin)...
        c.replica(1).receiveStore(tid, parseTuple("\"early\"")); // ...but only replica 1 has received the store so far
        assertEquals(parseTuple("\"early\""), c.replica(1).take(parseSchema("\"early\"")));
        c.replica(0).receiveStore(tid, parseTuple("\"early\""));  // the late store must not resurrect the taken tuple
        assertEquals(0, c.replica(0).size());
        assertEquals(0, c.replica(1).size());
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

    /**
     * Regression (F-0603 adversary review): a grant refusal can mean two different things — the tuple is genuinely
     * held by another live replica, or its coordinator just crashed and the call never really landed. {@link
     * XlReplica#take} must tell them apart by re-resolving the coordinator: if it changed, retry the *same* tuple
     * with the *same* request instead of abandoning it for a different candidate. Abandoning it would strand the
     * tuple forever if the crashed coordinator had already replicated the grant to its successor (see {@link
     * XlReplica#grant}) before dying — the successor would then refuse every other taker's request for it, and the
     * original taker (having moved on) would never come back to claim it.
     */
    @Test @Timeout(10)
    void takeRetriesTheSameTupleAgainstANewCoordinatorInsteadOfAbandoningIt() throws Exception {
        var tid = new XlReplica.TupleId(9, 0);
        var tuple = parseTuple("\"x\"");
        var refusing = new StubPeer(0);          // looks exactly like a live coordinator that refuses the grant
        var granting = new StubPeer(1);          // the successor: grants it
        var network = new XlNetwork() {
            int asked = 0;
            @Override public List<XlPeer> activePeers() { return List.of(granting); }
            @Override public boolean isActive(int replicaId) { return true; }
            @Override public Set<Integer> failedIds() { return Set.of(); }
            @Override public void learnFailed(Set<Integer> ids) { }
            @Override public XlPeer coordinatorOf(XlReplica.TupleId t) { return asked++ == 0 ? refusing : granting; }
        };
        var taker = new XlReplica(2, network);
        taker.receiveStore(tid, tuple);           // the taker already has the tuple; only the take/grant path is under test

        assertEquals(tuple, taker.take(parseSchema("\"x\"")));
        assertTrue(granting.granted, "the successor coordinator must have been asked, not skipped");
    }

    /** A minimal {@link XlPeer} test double: one coordinator that either always refuses or always grants. */
    private static final class StubPeer implements XlPeer {
        final int id;
        boolean granted;
        StubPeer(int id) { this.id = id; }
        @Override public int id() { return id; }
        @Override public void receiveStore(XlReplica.TupleId tid, Tuple tuple) { }
        @Override public void receiveRemove(XlReplica.TupleId tid) { }
        @Override public void receiveGrant(XlReplica.TupleId tid, XlReplica.ReqId req, XlReplica.GrantStamp stamp) { }
        @Override public boolean grant(XlReplica.TupleId tid, XlReplica.ReqId req, Set<Integer> knownFailed) {
            if (id != 1) return false;            // a crashed coordinator's call looks like a plain refusal to take()
            granted = true;
            return true;
        }
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
