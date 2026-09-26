package dev.sobatista.tuplo.cluster;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static dev.sobatista.tuplo.core.Fields.parseSchema;
import static dev.sobatista.tuplo.core.Fields.parseTuple;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The point of replicating the tuple space: a replica can die and the data survives. These are the tests that make
 * "fault-tolerant" more than a word in the README. Fault model from the statement: a perfect failure detector, one
 * fault at a time, with time to recover.
 */
class FaultToleranceTest {

    // ---- SMR ----

    @Test @Timeout(10)
    void smrSurvivesAReplicaCrash() throws Exception {
        var c = new Cluster(3);
        c.replica(0).add(parseTuple("\"survive\", \"me\""));
        c.crash(1);                                              // a replica dies after the write
        // the tuple is still there and still takeable via a surviving replica
        assertEquals(parseTuple("\"survive\", \"me\""), c.replica(2).take(parseSchema("\"survive\", \"*\"")));
        assertEquals(0, c.replica(0).size());
        assertEquals(0, c.replica(2).size());
    }

    @Test @Timeout(10)
    void smrFreezeThenUnfreezeLosesNothing() throws Exception {
        var c = new Cluster(3);
        c.freeze(2);                                             // replica 2 receives but defers processing
        c.replica(0).add(parseTuple("\"a\""));
        c.replica(0).add(parseTuple("\"b\""));
        assertEquals(0, c.replica(2).size(), "frozen replica has not applied anything yet");
        c.unfreeze(2);
        assertEquals(2, c.replica(2).size(), "on unfreeze it applied both, in order");
    }

    // ---- XL ----

    @Test @Timeout(10)
    void xlSurvivesCrashOfTheTupleCoordinator() throws Exception {
        var c = new XlCluster(3);
        c.replica(1).add(parseTuple("\"owned\", \"by1\""));      // origin (coordinator) is replica 1
        c.crash(1);                                              // the coordinator dies
        // a survivor takes over coordination; the tuple is still takeable exactly once
        assertEquals(parseTuple("\"owned\", \"by1\""), c.replica(0).take(parseSchema("\"owned\", \"*\"")));
        assertTrue(c.replica(2).tryTake(parseSchema("\"owned\", \"*\"")).isEmpty(), "not takeable twice");
        assertEquals(0, c.replica(0).size());
        assertEquals(0, c.replica(2).size());
    }

    @Test @Timeout(10)
    void xlCrashReleasesAGrantHeldByTheDeadReplica() throws Exception {
        var c = new XlCluster(3);
        c.replica(0).add(parseTuple("\"x\""));
        // replica 2 crashing must not strand the tuple even if it had been mid-take; a survivor can still take it
        c.crash(2);
        assertEquals(parseTuple("\"x\""), c.replica(0).take(parseSchema("\"x\"")));
    }
}
