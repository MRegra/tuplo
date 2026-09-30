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
 *       true without ordering every operation. The coordinator also replicates its decision to every other active
 *       replica before answering ({@link #receiveGrant}), so if it then crashes before the taker's removal has landed,
 *       a successor coordinator still knows the tuple is taken instead of handing it out again.</li>
 * </ul>
 *
 * <p>Fault tolerance (perfect failure detector, one fault at a time): when a replica crashes it leaves the {@link View},
 * its coordinator role passes to the lowest surviving id, and any grants it was holding as a taker are released so its
 * half-finished takes don't strand a tuple. See {@link XlCluster} for the wiring.
 */
public final class XlReplica implements TupleSpace, XlPeer {

    /** Cluster-wide unique tuple identity: which replica created it, and its local sequence number. */
    public record TupleId(int origin, long seq) implements Serializable {}

    /** A take request's identity: which replica is asking, and its local counter. */
    public record ReqId(int replica, long n) implements Serializable {}

    /**
     * A coordinator-side reservation, stamped with the coordinator's <b>epoch</b>: the number of replicas it knew had
     * failed at the moment it granted. The epoch only grows (crash-stop, no recovery), so it orders successive
     * coordinators for the same tuple without any extra consensus round. See {@link #receiveGrant}.
     */
    record Grant(ReqId req, long epoch) {}

    /**
     * A grant decision replicated to a peer, together with the failed-replica set the granting coordinator knew about.
     * Piggybacking the set lets a peer that hasn't yet noticed a crash learn it from the copy itself instead of waiting
     * for its own failure detector.
     */
    public record GrantStamp(long epoch, Set<Integer> failed) implements Serializable {}

    private final int id;
    private final XlNetwork cluster;
    final Faults faults = new Faults();

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition changed = lock.newCondition();
    private final LinkedHashMap<TupleId, Tuple> store = new LinkedHashMap<>();   // insertion order → oldest-first
    private final Map<TupleId, Grant> granted = new LinkedHashMap<>();           // coordinator-side reservations
    private final Set<TupleId> tombstones = new HashSet<>();                     // removed ids: drop a late store for them
    private long version = 0;                                                    // bumps on every state change; kills lost wakeups
    private final AtomicLong addSeq = new AtomicLong();
    private final AtomicLong reqSeq = new AtomicLong();

    /** A replica that reaches its peers through {@code network} (in-process {@link XlCluster}, or RMI in tuplo-node). */
    public XlReplica(int id, XlNetwork network) {
        this.id = id;
        this.cluster = network;
    }

    public int id() { return id; }

    /** The crash/freeze switches of this replica (what a PuppetMaster flips). */
    public Faults faults() { return faults; }

    /** A copy of the tuples this replica holds, oldest first. */
    public List<Tuple> snapshot() { lock.lock(); try { return List.copyOf(store.values()); } finally { lock.unlock(); } }

    // ---- client-facing TupleSpace API -------------------------------------

    @Override
    public void add(Tuple tuple) {
        faults.requireAlive();
        TupleId tid = new TupleId(id, addSeq.getAndIncrement());
        for (XlPeer r : cluster.activePeers()) {
            r.receiveStore(tid, tuple);        // multicast to every active replica (parallel in spirit; no ordering)
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
                TupleId tid = cand.getKey();
                if (tryGrant(tid, req)) {                            // exactly one taker wins this tuple
                    for (XlPeer r : cluster.activePeers()) r.receiveRemove(tid);
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
            if (tryGrant(cand.getKey(), req)) {
                for (XlPeer r : cluster.activePeers()) r.receiveRemove(cand.getKey());
                return Optional.of(cand.getValue());
            }
        }
        return Optional.empty();
    }

    /**
     * Ask successive coordinators to grant {@code tid} to {@code req}, shared by {@link #take} and {@link #tryTake}.
     *
     * <p>A refusal can mean two different things — the tuple is genuinely held by another live replica, or its
     * coordinator just crashed and the call never really landed. Tell them apart by re-resolving the coordinator: if
     * it changed, retry the *same* tuple with the *same* request against the successor instead of abandoning it —
     * otherwise a phantom reservation under our own (abandoned) request could strand the tuple forever. If the
     * coordinator is unchanged, we were genuinely refused by a live holder.
     */
    private boolean tryGrant(TupleId tid, ReqId req) {
        XlPeer coord = cluster.coordinatorOf(tid);
        while (coord != null) {
            if (coord.grant(tid, req, cluster.failedIds())) return true;
            XlPeer retry = cluster.coordinatorOf(tid);
            if (retry == null || retry.id() == coord.id()) return false;
            coord = retry;
        }
        return false;
    }

    @Override public int size() { lock.lock(); try { return store.size(); } finally { lock.unlock(); } }

    // ---- peer-facing message handlers (XlPeer: called in-process or over RMI) ----

    @Override
    public void receiveStore(TupleId tid, Tuple tuple) {
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

    @Override
    public void receiveRemove(TupleId tid) {
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

    /**
     * Coordinator side: grant this tuple to exactly one taker.
     *
     * <p>Before answering, the decision is replicated to every other active replica ({@link #receiveGrant}), stamped
     * with this coordinator's <b>epoch</b> (how many replicas it knows have failed). That closes the window where this
     * coordinator crashes right after granting: without it, the grant lived only here, so a successor coordinator (the
     * next-lowest survivor) would see no record of it and could hand the same tuple to a second taker while the first
     * taker's remove was still in flight. With the grant already known to every survivor, the successor sees an
     * existing, still-active holder and refuses instead.
     *
     * <p>The epoch also protects against the mirror failure: a copy of <em>this</em> coordinator's own grant, still in
     * flight when it crashes, must never be allowed to overwrite a later decision made by its successor. Since the
     * successor only starts granting after it knows this coordinator is gone, its grants carry a strictly higher
     * epoch — see {@link #receiveGrant}.
     *
     * <p>{@code knownFailed} is the taker's own view of who has failed; it is merged into this replica's view before
     * anything else, so a coordinator that has not yet noticed the taker's failure evidence still makes the right
     * call. The merge (and the {@code coordinatorOf} check right after) must happen without holding {@link #lock}: the
     * merge can itself call back into this replica (see {@link #onPeerFailed}), which takes {@link #lock}.
     */
    @Override
    public boolean grant(TupleId tid, ReqId req, Set<Integer> knownFailed) {
        cluster.learnFailed(knownFailed);
        XlPeer coordinatorNow = cluster.coordinatorOf(tid);
        if (coordinatorNow == null || coordinatorNow.id() != id) return false;   // not (or no longer) this tuple's coordinator
        Set<Integer> failed = cluster.failedIds();
        long epoch = failed.size();
        lock.lock();
        try {
            if (tombstones.contains(tid)) return false;               // already taken (removed) — this taker missed it
            // Not stored here *yet* is fine: the add's multicast isn't atomic, so a taker may see the tuple before its
            // coordinator does. The grant is what makes the take exclusive; the tombstone the remove leaves here stops
            // the late store from resurrecting it. (Refusing used to strand the taker waiting for a change forever.)
            Grant holder = granted.get(tid);
            if (holder != null && !holder.req().equals(req) && cluster.isActive(holder.req().replica())) {
                return false;                                         // reserved by another live taker
            }
            granted.put(tid, new Grant(req, epoch));
        } finally {
            lock.unlock();
        }
        GrantStamp stamp = new GrantStamp(epoch, failed);
        for (XlPeer r : cluster.activePeers()) if (r != this) r.receiveGrant(tid, req, stamp);
        return true;
    }

    /**
     * A coordinator's grant decision, replicated here so this replica can take over coordination without amnesia.
     *
     * <p>Copies are not simply "last writer wins": a copy sent by a coordinator just before it crashed can arrive
     * <em>after</em> its successor has already granted the same tuple to a different, live taker. Such a copy always
     * carries a lower or equal epoch than the successor's own grant (the successor only grants once it knows the
     * sender is gone, so its epoch is strictly higher), which is what lets this method tell a stale copy from the
     * current truth without any extra round trip.
     */
    @Override
    public void receiveGrant(TupleId tid, ReqId req, GrantStamp stamp) {
        cluster.learnFailed(stamp.failed());
        lock.lock();
        try {
            if (tombstones.contains(tid)) return;    // already removed; a late/duplicate replication is a no-op
            Grant record = granted.get(tid);
            if (record != null && stamp.epoch() < record.epoch()) return;   // a deposed coordinator's stale copy
            if (record != null && stamp.epoch() == record.epoch()
                    && !record.req().equals(req) && cluster.isActive(record.req().replica())) {
                return;                                                     // same-epoch tie: first writer wins
            }
            granted.put(tid, new Grant(req, stamp.epoch()));
            version++;
            changed.signalAll();
        } finally {
            lock.unlock();
        }
    }

    // ---- fault hooks (driven by XlCluster / PuppetMaster) -----------------

    /** The failure detector reported {@code failedReplica} dead: release its grants and let blocked takes retry. */
    public void onPeerFailed(int failedReplica) {
        lock.lock();
        try {
            granted.entrySet().removeIf(e -> e.getValue().req().replica() == failedReplica);  // release a dead taker's grants
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
