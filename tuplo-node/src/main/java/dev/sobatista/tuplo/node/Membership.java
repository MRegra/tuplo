package dev.sobatista.tuplo.node;

import dev.sobatista.tuplo.cluster.View;

import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;

/**
 * Who the replicas of one networked cluster are, how to reach them over RMI, and who is still alive.
 *
 * <p>This is the project's <b>perfect failure detector</b>: a peer is declared failed only when it cannot be reached at
 * all (a fresh registry lookup plus a ping both fail) — on one machine or a LAN that means the process is gone, not
 * slow. Detection is triggered by any failed call and by a background heartbeat, and the verdict is gossiped to the
 * other replicas, so every survivor <em>eventually</em> drops the dead replica from its {@link View}. Failures are
 * permanent (a crashed replica never rejoins), which is the statement's basic fault model.
 *
 * <p>Before a replica has {@link #awaitJoined joined} (reached every peer once) an unreachable peer is assumed to be
 * still starting, never failed — otherwise starting three processes in the wrong order would look like a crash.
 */
final class Membership {

    /** {@code host:port/name} of one replica's RMI registry entry. */
    record Address(String host, int port, String name) {
        static Address parse(String s) {
            int colon = s.lastIndexOf(':'), slash = s.indexOf('/', colon + 1);
            if (colon <= 0 || slash < 0) throw new IllegalArgumentException("expected host:port/name, got: " + s);
            return new Address(s.substring(0, colon), Integer.parseInt(s.substring(colon + 1, slash)), s.substring(slash + 1));
        }
        static List<Address> parseList(String csv) {
            List<Address> out = new ArrayList<>();
            for (String part : csv.split(",")) if (!part.isBlank()) out.add(parse(part.trim()));
            return out;
        }
        @Override public String toString() { return host + ":" + port + "/" + name; }
    }

    /** A remote call to one peer. */
    interface Call<T> { T on(PeerRemote peer) throws RemoteException, InterruptedException; }

    /** Thrown by {@link #call} when the peer is (now) known to be dead. */
    static final class PeerDown extends Exception {
        PeerDown(int id) { super("replica " + id + " is down", null, false, false); }
    }

    private final int self;
    private final List<Address> addresses;
    private final View view;
    private final Map<Integer, PeerRemote> stubs = new ConcurrentHashMap<>();
    private final List<IntConsumer> failureListeners = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService background = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "tuplo-membership");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean joined = false;

    Membership(int self, List<Address> addresses) {
        if (self < 0 || self >= addresses.size()) throw new IllegalArgumentException("--id " + self + " is not in --peers");
        this.self = self;
        this.addresses = List.copyOf(addresses);
        this.view = new View(addresses.size());
    }

    int self() { return self; }
    int size() { return addresses.size(); }
    View view() { return view; }
    boolean isActive(int id) { return view.isActive(id); }
    boolean joined() { return joined; }

    /** Active replica ids, ascending. */
    List<Integer> activeIds() { return view.active().stream().sorted().toList(); }

    /** The lowest active id: the SMR sequencer, and the XL fallback coordinator. */
    int leader() { return activeIds().getFirst(); }

    void onFailure(IntConsumer listener) { failureListeners.add(listener); }

    /** Block until every peer answers a ping once, then start the heartbeat. */
    void awaitJoined(long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        for (int id = 0; id < addresses.size(); id++) {
            if (id == self) continue;
            while (!reachable(id)) {
                if (System.currentTimeMillis() > deadline) throw new IllegalStateException("peer " + addresses.get(id) + " never came up");
                Thread.sleep(100);
            }
        }
        joined = true;
        background.scheduleWithFixedDelay(this::heartbeat, 200, 200, TimeUnit.MILLISECONDS);
    }

    /**
     * Call a peer. Transient errors (the peer still answers pings) are retried; a peer that is unreachable is marked
     * failed and {@link PeerDown} is thrown, so the caller can carry on without it.
     */
    <T> T call(int id, Call<T> call) throws PeerDown, InterruptedException {
        while (true) {
            if (!view.isActive(id)) throw new PeerDown(id);
            try {
                return call.on(stub(id));
            } catch (RemoteException e) {
                stubs.remove(id);
                if (joined && !reachable(id)) {
                    markFailed(id);
                    throw new PeerDown(id);
                }
                Thread.sleep(50);                                   // still alive (or still starting): try again
            }
        }
    }

    /** Record that a peer died and tell everyone (idempotent). */
    synchronized void markFailed(int id) {
        if (id == self || !view.isActive(id)) return;
        view.fail(id);
        stubs.remove(id);
        System.out.println("[replica " + self + "] failure detector: replica " + id + " is down, view now " + activeIds());
        for (IntConsumer l : failureListeners) l.accept(id);
        background.execute(() -> {                                  // gossip, off the caller's locks
            for (int other : activeIds()) {
                if (other == self) continue;
                try { stub(other).peerFailed(id); } catch (RemoteException ignored) { /* the heartbeat will catch it */ }
            }
        });
    }

    void shutdown() { background.shutdownNow(); }

    private void heartbeat() {
        for (int id : activeIds()) {
            if (id != self && !reachable(id)) markFailed(id);
        }
    }

    /** Liveness: a cached stub answers a ping, or a fresh lookup does. */
    private boolean reachable(int id) {
        PeerRemote cached = stubs.get(id);
        if (cached != null) {
            try { cached.ping(); return true; } catch (RemoteException e) { stubs.remove(id); }
        }
        try { stub(id).ping(); return true; } catch (RemoteException e) { stubs.remove(id); return false; }
    }

    private PeerRemote stub(int id) throws RemoteException {
        PeerRemote s = stubs.get(id);
        if (s != null) return s;
        Address a = addresses.get(id);
        try {
            s = (PeerRemote) LocateRegistry.getRegistry(a.host(), a.port()).lookup(a.name());
        } catch (NotBoundException e) {
            throw new RemoteException("replica " + a + " not bound (yet)", e);
        }
        stubs.put(id, s);
        return s;
    }
}
