package dev.sobatista.tuplo.cluster;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Direct unit tests of the {@link Faults} state holder, independent of any replica variant. */
class FaultsTest {

    @Test void startsNeitherCrashedNorFrozen() {
        var f = new Faults();
        assertFalse(f.isCrashed());
        assertFalse(f.isFrozen());
    }

    @Test void freezeThenUnfreezeTogglesIsFrozen() {
        var f = new Faults();
        f.freeze();
        assertTrue(f.isFrozen());
        f.unfreeze();
        assertFalse(f.isFrozen());
    }

    @Test void freezeIsANoOpOnceCrashed() {
        var f = new Faults();
        f.crash();
        f.freeze();
        assertFalse(f.isFrozen(), "a crashed replica can never be (re-)frozen");
    }

    @Test void crashClearsAnExistingFreeze() {
        var f = new Faults();
        f.freeze();
        assertTrue(f.isFrozen());
        f.crash();
        assertTrue(f.isCrashed());
        assertFalse(f.isFrozen(), "crash always wins over freeze");
    }

    @Test void requireAliveThrowsOnceCrashed() {
        var f = new Faults();
        f.requireAlive();               // does not throw while alive
        f.crash();
        assertThrows(Faults.ReplicaCrashedException.class, f::requireAlive);
    }

    @Test @Timeout(10)
    void awaitThawedReturnsImmediatelyWhenNotFrozen() throws Exception {
        var f = new Faults();
        f.awaitThawed();                // must not block
    }

    @Test @Timeout(10)
    void awaitThawedBlocksWhileFrozenThenReturnsOnUnfreeze() throws Exception {
        var f = new Faults();
        f.freeze();
        var returned = new AtomicReference<Boolean>(Boolean.FALSE);
        var t = new Thread(() -> {
            try {
                f.awaitThawed();
                returned.set(Boolean.TRUE);
            } catch (InterruptedException ignored) {
            }
        }, "awaitThawed-blocks");
        t.start();
        Await.state(t, Thread.State.WAITING, 2000);
        assertFalse(returned.get(), "still frozen: must still be parked");
        f.unfreeze();
        t.join(2000);
        assertTrue(returned.get(), "unfreeze must release the waiter");
    }

    @Test @Timeout(10)
    void awaitThawedThrowsOnceCrashedWhileParked() throws Exception {
        var f = new Faults();
        f.freeze();
        var thrown = new AtomicReference<Throwable>();
        var t = new Thread(() -> {
            try {
                f.awaitThawed();
            } catch (Throwable e) {
                thrown.set(e);
            }
        }, "awaitThawed-crashed");
        t.start();
        Await.state(t, Thread.State.WAITING, 2000);
        f.crash();
        t.join(2000);
        assertInstanceOf(Faults.ReplicaCrashedException.class, thrown.get(),
                "a replica that crashes while a caller waits on it must wake that caller with the crash exception");
    }
}
