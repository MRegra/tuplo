package dev.sobatista.tuplo.cluster;

import dev.sobatista.tuplo.core.Schema;
import dev.sobatista.tuplo.core.Tuple;
import dev.sobatista.tuplo.core.TupleSpace;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A replica of the <b>XL</b> variant, in the spirit of Xu &amp; Liskov: the tuple space is fully replicated, and there
 * is <em>no total order</em>. That's the whole contrast with SMR — the win and the cost both live here.
 *
 * <ul>
 *   <li><b>add</b> is cheap and parallel: multicast the tuple to every active replica, done. No sequencer, no waiting
 *       in line behind unrelated operations.</li>
 *   <li><b>read</b> is local: every replica holds every tuple, so a match is found without talking to anyone.</li>
 *   <li><b>take</b> is the hard one, because two replicas must not both hand out the same tuple. Each tuple has a
 *       single <b>coordinator</b> (the replica that created it, or a deterministic survivor if that one crashed). To
 *       take a tuple you ask its coordinator to <em>grant</em> it; the coordinator grants each tuple to exactly one
 *       taker, then the taker multicasts the removal. That single grant point is what makes "removed exactly once"
 *       true without ordering every operation.</li>
 * </ul>
 *
 * <p>Fault tolerance (perfect failure detector, one fault at a time): when a replica crashes it leaves the {@link View},
 * its coordinator role passes to the lowest surviving id, and any grants it was holding as a taker are released so its
 * half-finished takes don't strand a tuple. See {@link XlCluster} for the wiring.
 */
public final class XlReplica implements TupleSpace {

    /** Cluster-wide unique tuple identity: which replica created it, and its local sequence number. */
    public record TupleId(int origin, long seq) implements Serializable {}

    /** A take request's identity: which replica is asking, and its local counter. */
    private record ReqId(int replica, long n) {}

    private final int id;
    private final XlCluster cluster;
    final Faults faults = new Faults();

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition changed = lock.newCondition();
    private final LinkedHashMap<TupleId, Tuple> store = new LinkedHashMap<>();   // insertion order → oldest-first
    private final Map<TupleId, ReqId> granted = new LinkedHashMap<>();           // coordinator-side reservations
    private final Set<TupleId> tombstones = new HashSet<>();                     // removed ids: drop a late store for them
    private long version = 0;                                                    // bumps on every state change; kills lost wakeups
    private final AtomicLong addSeq = new AtomicLong();
    private final AtomicLong reqSeq = new AtomicLong();

    XlReplica(int id, XlCluster cluster) {
        this.id = id;
        this.cluster = cluster;
    }

    public int id() { return id; }

    // ---- client-facing TupleSpace API -------------------------------------

    @Override
    public void add(Tuple tuple) {
        faults.requireAlive();
        TupleId tid = new TupleId(id, addSeq.getAndIncrement());
        for (XlReplica r : cluster.activeReplicas()) {
            r.receiveStore(tid, tuple);         // multicast to every active replica (parallel in spirit; no ordering)
        }
    }

    @Override
    public Tuple read(Schema schema) throws InterruptedException {
        lock.lock();
        try {
            Optional<Tuple> hit;
            while ((hit = firstMatch(schema)).isEmpty()) changed.await();
            return hit.get();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Tuple take(Schema schema) throws InterruptedException {
        faults.requireAlive();
        ReqId req = new ReqId(id, reqSeq.getAndIncrement());
        while (true) {
            List<Map.Entry<TupleId, Tuple>> candidates;
            long seenVersion;
            lock.lock();
            try {
                while ((candidates = matches(schema)).isEmpty()) changed.await();  // nothing yet: park until an add
                seenVersion = version;                                             // remember the state we're acting on
            } finally {
                lock.unlock();
            }
            for (var cand : candidates) {
                XlReplica coord = cluster.coordinatorOf(cand.getKey());
                if (coord == null) continue;                        // its coordinator crashed and none survives: skip
                if (coord.grant(cand.getKey(), req)) {              // exactly one taker wins this tuple
                    for (XlReplica r : cluster.activeReplicas()) r.receiveRemove(cand.getKey());
                    return cand.getValue();
                }
            }
            // every current match is reserved by someone else — wait until the state actually changes, then retry.
            // Comparing versions (not just await) closes the lost-wakeup window between granting above and awaiting here.
            lock.lock();
            try { while (version == seenVersion) changed.await(); } finally { lock.unlock(); }
        }
    }

    @Override public Optional<Tuple> tryRead(Schema s) { lock.lock(); try { return firstMatch(s); } finally { lock.unlock(); } }

    @Override
    public Optional<Tuple> tryTake(Schema schema) {
        ReqId req = new ReqId(id, reqSeq.getAndIncrement());
        for (var cand : snapshotMatches(schema)) {
            XlReplica coord = cluster.coordinatorOf(cand.getKey());
            if (coord != null && coord.grant(cand.getKey(), req)) {
                for (XlReplica r : cluster.activeReplicas()) r.receiveRemove(cand.getKey());
                return Optional.of(cand.getValue());
            }
        }
        return Optional.empty();
    }

    @Override public int size() { lock.lock(); try { return store.size(); } finally { lock.unlock(); } }

    // ---- peer-facing message handlers (in-process "network") --------------

    void receiveStore(TupleId tid, Tuple tuple) {
        try {
            faults.awaitThawed();               // frozen: received but processed only after unfreeze
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        } catch (Faults.ReplicaCrashedException crashed) {
            return;                             // crashed replica silently drops (the view will exclude it)
        }
        lock.lock();
        try {
            if (tombstones.contains(tid)) return;   // this tuple was already taken elsewhere; a late store must not resurrect it
            store.put(tid, tuple);
            version++;
            changed.signalAll();
        } finally {
            lock.unlock();
        }
    }

    void receiveRemove(TupleId tid) {
        if (faults.isCrashed()) return;
        lock.lock();
        try {
            store.remove(tid);
            granted.remove(tid);
            tombstones.add(tid);                     // remember it's gone, so a store that arrives out of order is ignored
            version++;
            changed.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /** Coordinator side: grant this tuple to exactly one taker. */
    boolean grant(TupleId tid, ReqId req) {
        lock.lock();
        try {
            if (!store.containsKey(tid)) return false;                // already taken (removed) — this taker missed it
            ReqId holder = granted.get(tid);
            if (holder != null && !holder.equals(req) && cluster.view().isActive(holder.replica())) {
                return false;                                         // reserved by another live taker
            }
            granted.put(tid, req);
            return true;
        } finally {
            lock.unlock();
        }
    }

    // ---- fault hooks (driven by XlCluster / PuppetMaster) -----------------

    void onPeerFailed(int failedReplica) {
        lock.lock();
        try {
            granted.entrySet().removeIf(e -> e.getValue().replica() == failedReplica);  // release a dead taker's grants
            version++;
            changed.signalAll();                                                        // let blocked takes retry
        } finally {
            lock.unlock();
        }
    }

    // ---- helpers (hold the lock) ------------------------------------------

    private Optional<Tuple> firstMatch(Schema schema) {
        for (var e : store.entrySet()) if (schema.matches(e.getValue())) return Optional.of(e.getValue());
        return Optional.empty();
    }

    private List<Map.Entry<TupleId, Tuple>> matches(Schema schema) {
        List<Map.Entry<TupleId, Tuple>> out = new ArrayList<>();
        for (var e : store.entrySet()) if (schema.matches(e.getValue())) out.add(Map.entry(e.getKey(), e.getValue()));
        return out;
    }

    private List<Map.Entry<TupleId, Tuple>> snapshotMatches(Schema schema) {
        lock.lock();
        try { return matches(schema); } finally { lock.unlock(); }
    }

    boolean hasTuple(TupleId tid) { lock.lock(); try { return store.containsKey(tid); } finally { lock.unlock(); } }
}
