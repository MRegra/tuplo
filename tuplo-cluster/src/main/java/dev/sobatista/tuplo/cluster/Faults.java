package dev.sobatista.tuplo.cluster;

import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The fault switches the PuppetMaster can flip on a replica, from the project statement:
 *
 * <ul>
 *   <li><b>crash</b> — the replica stops for good; the rest of the system detects it and drops it from the view.</li>
 *   <li><b>freeze</b> — the replica keeps <em>receiving</em> messages but stops <em>processing</em> them; anything that
 *       arrives while frozen is processed once it is unfrozen.</li>
 *   <li><b>unfreeze</b> — back to normal; process what piled up.</li>
 * </ul>
 *
 * <p>This little state holder is what makes fault-tolerance testable instead of theoretical: a test can crash a
 * replica mid-operation and assert the others carry on, or freeze one and assert nothing is lost.
 */
public final class Faults {

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition thawed = lock.newCondition();
    private volatile boolean crashed = false;
    private boolean frozen = false;

    public boolean isCrashed() { return crashed; }

    public boolean isFrozen() {
        lock.lock();
        try { return frozen; } finally { lock.unlock(); }
    }

    /** Permanent stop. A crashed replica never processes anything again. */
    public void crash() {
        lock.lock();
        try {
            crashed = true;
            frozen = false;
            thawed.signalAll();     // release anyone parked on a freeze so they can observe the crash
        } finally {
            lock.unlock();
        }
    }

    public void freeze() {
        lock.lock();
        try { if (!crashed) frozen = true; } finally { lock.unlock(); }
    }

    public void unfreeze() {
        lock.lock();
        try {
            frozen = false;
            thawed.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /** True if a message may be handled now (not crashed). */
    public void requireAlive() {
        if (crashed) throw new ReplicaCrashedException();
    }

    /**
     * Block while frozen, so a caller "receives" the message but its processing is delayed until unfreeze — exactly
     * the semantics the statement asks for. Returns immediately when not frozen; throws if the replica has crashed.
     */
    public void awaitThawed() throws InterruptedException {
        lock.lock();
        try {
            while (frozen && !crashed) thawed.await();
            if (crashed) throw new ReplicaCrashedException();
        } finally {
            lock.unlock();
        }
    }

    /** Thrown when an operation touches a crashed replica. */
    public static final class ReplicaCrashedException extends RuntimeException {
        public ReplicaCrashedException() { super("replica crashed"); }
    }
}
