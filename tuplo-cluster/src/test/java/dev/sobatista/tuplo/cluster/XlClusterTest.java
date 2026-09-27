package dev.sobatista.tuplo.cluster;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.atomic.AtomicReference;

import static dev.sobatista.tuplo.core.Fields.parseTuple;
import static org.junit.jupiter.api.Assertions.*;

/** The {@link XlCluster} convenience wiring itself, on top of the XL guarantees covered elsewhere. */
class XlClusterTest {

    @Test void rejectsNonPositiveSize() {
        assertThrows(IllegalArgumentException.class, () -> new XlCluster(0));
    }

    @Test void replicasReturnsAllOfThemInOrder() {
        var c = new XlCluster(3);
        var replicas = c.replicas();
        assertEquals(3, replicas.size());
        for (int i = 0; i < 3; i++) assertSame(c.replica(i), replicas.get(i));
    }

    @Test void sizeMatchesReplicaCount() {
        assertEquals(4, new XlCluster(4).size());
    }

    @Test void replicaSpaceExposesTheSameReplicaAsATupleSpace() throws Exception {
        var c = new XlCluster(2);
        c.replicaSpace(0).add(parseTuple("\"a\""));
        assertEquals(1, c.replica(1).size(), "replicaSpace(0) must be the very same replica 0");
    }

    @Test void viewExposesTheClustersActiveSet() {
        var c = new XlCluster(3);
        assertEquals(3, c.view().size());
        c.crash(1);
        assertEquals(2, c.view().size());
        assertFalse(c.view().isActive(1));
    }

    @Test void statusReportsHealthyAndFailedReplicas() throws Exception {
        var c = new XlCluster(2);
        c.crash(1);                                    // fail replica 1 first: it must never receive the add below
        c.replica(0).add(parseTuple("\"a\""));
        var status = c.status();
        assertTrue(status.startsWith("XL cluster ("), status);
        assertTrue(status.contains("replica 0: 1 tuple(s)"), status);
        assertTrue(status.contains("replica 1: 0 tuple(s) [failed]"), status);
    }

    @Test @Timeout(10)
    void freezeThenUnfreezeDeliversABufferedAddOnUnfreeze() throws Exception {
        var c = new XlCluster(2);
        c.freeze(1);
        var addDone = new AtomicReference<Boolean>(Boolean.FALSE);
        var t = new Thread(() -> {
            c.replica(0).add(parseTuple("\"buffered\""));   // blocks: the multicast to replica 1 parks in awaitThawed()
            addDone.set(Boolean.TRUE);
        }, "xl-add-into-frozen-peer");
        t.start();
        Await.state(t, Thread.State.WAITING, 2000);
        assertFalse(addDone.get(), "add must still be parked delivering to the frozen replica");
        assertEquals(0, c.replica(1).size());
        c.unfreeze(1);
        t.join(2000);
        assertTrue(addDone.get());
        assertEquals(1, c.replica(1).size(), "on unfreeze the buffered store is applied");
    }
}
