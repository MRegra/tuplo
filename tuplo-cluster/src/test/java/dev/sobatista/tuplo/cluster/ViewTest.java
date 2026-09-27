package dev.sobatista.tuplo.cluster;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Direct unit tests of {@link View}, the failure-detector's view of who is currently active. */
class ViewTest {

    @Test void startsWithAllReplicasActive() {
        var v = new View(3);
        assertEquals(3, v.size());
        for (int i = 0; i < 3; i++) assertTrue(v.isActive(i));
    }

    @Test void failRemovesAReplica() {
        var v = new View(3);
        v.fail(1);
        assertFalse(v.isActive(1));
        assertEquals(2, v.size());
    }

    @Test void failIsIdempotent() {
        var v = new View(3);
        v.fail(1);
        v.fail(1);
        assertEquals(2, v.size());
    }

    @Test void recoverBringsAReplicaBack() {
        var v = new View(3);
        v.fail(1);
        v.recover(1);
        assertTrue(v.isActive(1));
        assertEquals(3, v.size());
    }

    @Test void recoverIsIdempotent() {
        var v = new View(3);
        v.recover(0);       // already active
        assertEquals(3, v.size());
    }

    @Test void activeReturnsAnUnmodifiableSnapshot() {
        var v = new View(2);
        var snapshot = v.active();
        assertEquals(2, snapshot.size());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.add(99));
        v.fail(0);
        assertEquals(2, snapshot.size(), "the earlier snapshot must not reflect a later change");
    }

    @Test void toStringMentionsTheActiveIds() {
        var v = new View(2);
        v.fail(1);
        var s = v.toString();
        assertTrue(s.contains("0"), s);
        assertFalse(s.contains("1"), s);
    }
}
