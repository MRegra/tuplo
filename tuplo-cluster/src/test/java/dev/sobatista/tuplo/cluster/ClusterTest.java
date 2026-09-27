package dev.sobatista.tuplo.cluster;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static dev.sobatista.tuplo.core.Fields.parseTuple;
import static org.junit.jupiter.api.Assertions.*;

/** The {@link Cluster} convenience wiring itself, on top of the SMR guarantees covered elsewhere. */
class ClusterTest {

    @Test void rejectsNonPositiveSize() {
        assertThrows(IllegalArgumentException.class, () -> new Cluster(0));
    }

    @Test void replicasReturnsAllOfThemInOrder() {
        var c = new Cluster(3);
        var replicas = c.replicas();
        assertEquals(3, replicas.size());
        for (int i = 0; i < 3; i++) assertSame(c.replica(i), replicas.get(i));
    }

    @Test void sizeMatchesReplicaCount() {
        assertEquals(4, new Cluster(4).size());
    }

    @Test void replicaSpaceExposesTheSameReplicaAsATupleSpace() throws Exception {
        var c = new Cluster(2);
        c.replicaSpace(0).add(parseTuple("\"a\""));
        assertEquals(1, c.replica(1).size(), "replicaSpace(0) must be the very same replica 0");
    }

    @Test @Timeout(10)
    void statusReportsEachReplicasTupleCount() throws Exception {
        var c = new Cluster(2);
        c.replica(0).add(parseTuple("\"a\""));
        var status = c.status();
        assertTrue(status.startsWith("SMR cluster:"), status);
        assertTrue(status.contains("replica 0: 1 tuple(s)"), status);
        assertTrue(status.contains("replica 1: 1 tuple(s)"), status);
    }
}
