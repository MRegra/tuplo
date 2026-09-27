package dev.sobatista.tuplo.cluster;

import dev.sobatista.tuplo.core.Schema;
import dev.sobatista.tuplo.core.Tuple;
import dev.sobatista.tuplo.core.TupleSpace;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One state-machine-replication replica of the tuple space.
 *
 * <p>Every replica holds the whole tuple space and applies the <em>same</em> commands in the <em>same</em>
 * order (delivered by {@link TotalOrder}), so all replicas hold identical state — that is the redundancy
 * that survives a node dying. A client talks to any one replica; that replica submits the operation to the
 * ordering layer and, once the operation is delivered back (to every replica, in order), returns the
 * result to the client.
 *
 * <p>The subtle part is <b>blocking</b> {@code read}/{@code take}. When a take arrives and nothing matches
 * yet, the replica does not busy-wait — it parks the request in an ordered {@code pending} list. A later
 * {@code add} re-drives the pending list in sequence order, so every replica resolves the same take with
 * the same tuple. Determinism (oldest tuple first, pending in sequence order) is what keeps the replicas
 * byte-for-byte identical.
 */
public final class SmrReplica implements TupleSpace, TotalOrder.Deliverer {

    private enum Op { TAKE, READ }

    private record Pending(Op op, Schema schema, int originReplica, long reqId, long seq) {}

    private final int id;
    private final TotalOrder order;
    private final AtomicLong reqCounter = new AtomicLong();

    // --- replicated state (only mutated inside deliver(), which the total-order layer serialises) ---
    private final List<Tuple> tuples = new ArrayList<>();
    private final List<Pending> pending = new ArrayList<>();

    // --- local client bookkeeping (this replica answers only the requests it originated) ---
    private final ConcurrentHashMap<Long, CompletableFuture<Tuple>> awaitingResult = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, CompletableFuture<Void>> awaitingAdd = new ConcurrentHashMap<>();

    // --- fault injection (crash / freeze), driven by Cluster / PuppetMaster ---
    private volatile boolean crashed = false;
    private boolean frozen = false;
    private final List<Object[]> frozenBuffer = new ArrayList<>();   // {Long seq, Command cmd} received while frozen

    public SmrReplica(int id, TotalOrder order) {
        this.id = id;
        this.order = order;
        order.register(this);
    }

    public int id() { return id; }

    // ---- client-facing TupleSpace API -------------------------------------

    /** Crash this replica permanently (a delivered command is dropped from now on). */
    public synchronized void crash() { crashed = true; }

    /** Freeze: keep receiving ordered commands but buffer them until {@link #unfreeze()}. */
    public synchronized void freeze() { if (!crashed) frozen = true; }

    /** Unfreeze: apply everything that arrived while frozen, in order. */
    public synchronized void unfreeze() {
        frozen = false;
        var buffered = new ArrayList<>(frozenBuffer);
        frozenBuffer.clear();
        for (Object[] item : buffered) applyDelivered((Long) item[0], (Command) item[1]);
    }

    private void requireAlive() {
        if (crashed) throw new IllegalStateException("replica " + id + " has crashed");
    }

    @Override
    public void add(Tuple tuple) {
        requireAlive();
        long req = reqCounter.incrementAndGet();
        var f = new CompletableFuture<Void>();
        awaitingAdd.put(req, f);
        order.submit(new Command.Add(id, req, tuple));
        f.join();                                    // returns once this replica has applied its own add
    }

    @Override
    public Tuple take(Schema schema) throws InterruptedException {
        requireAlive();
        return await(new Command.Take(id, reqCounter.incrementAndGet(), schema));
    }

    @Override
    public Tuple read(Schema schema) throws InterruptedException {
        requireAlive();
        return await(new Command.Read(id, reqCounter.incrementAndGet(), schema));
    }

    private Tuple await(Command cmd) throws InterruptedException {
        var f = new CompletableFuture<Tuple>();
        awaitingResult.put(cmd.reqId(), f);
        order.submit(cmd);
        try {
            return f.get();                          // parks until the op is delivered AND a tuple matches
        } catch (ExecutionException e) {
            throw new RuntimeException(e.getCause());
        }
    }

    @Override public Optional<Tuple> tryRead(Schema s) { synchronized (this) { return firstMatch(s).map(tuples::get); } }

    @Override
    public Optional<Tuple> tryTake(Schema s) {
        synchronized (this) {
            return firstMatch(s).map(i -> tuples.remove((int) i));
        }
    }

    @Override public int size() { synchronized (this) { return tuples.size(); } }

    /** A copy of the replicated state, oldest first — identical on every replica that applied the same commands. */
    public List<Tuple> snapshot() { synchronized (this) { return List.copyOf(tuples); } }

    // ---- the replicated state machine -------------------------------------

    @Override
    public synchronized void deliver(long seq, Command command) {
        if (crashed) return;                        // a crashed replica processes nothing more
        if (frozen) { frozenBuffer.add(new Object[]{seq, command}); return; }   // received, processed on unfreeze
        applyDelivered(seq, command);
    }

    private void applyDelivered(long seq, Command command) {
        switch (command) {
            case Command.Add a -> {
                tuples.add(a.tuple());
                if (a.originReplica() == id) {
                    var f = awaitingAdd.remove(a.reqId());
                    if (f != null) f.complete(null);
                }
                drivePending();
            }
            case Command.Take t -> { pending.add(new Pending(Op.TAKE, t.schema(), t.originReplica(), t.reqId(), seq)); drivePending(); }
            case Command.Read r -> { pending.add(new Pending(Op.READ, r.schema(), r.originReplica(), r.reqId(), seq)); drivePending(); }
        }
    }

    /** Resolve as many pending reads/takes as possible, in sequence order, deterministically. */
    private void drivePending() {
        boolean progress = true;
        while (progress) {
            progress = false;
            for (int p = 0; p < pending.size(); p++) {
                Pending req = pending.get(p);
                Optional<Integer> hit = firstMatch(req.schema());
                if (hit.isEmpty()) continue;
                Tuple result = req.op() == Op.TAKE ? tuples.remove((int) hit.get()) : tuples.get(hit.get());
                pending.remove(p);
                if (req.originReplica() == id) {     // only the origin replica answers the waiting client
                    var f = awaitingResult.remove(req.reqId());
                    if (f != null) f.complete(result);
                }
                progress = true;
                break;                               // state changed; re-scan from the start for stable ordering
            }
        }
    }

    /** Index of the oldest tuple matching the schema. Caller is synchronized. */
    private Optional<Integer> firstMatch(Schema schema) {
        for (int i = 0; i < tuples.size(); i++) {
            if (schema.matches(tuples.get(i))) return Optional.of(i);
        }
        return Optional.empty();
    }
}
