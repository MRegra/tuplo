package dev.sobatista.tuplo.node;

import dev.sobatista.tuplo.cluster.Command;
import dev.sobatista.tuplo.cluster.Faults;
import dev.sobatista.tuplo.cluster.TotalOrder;

import java.util.HashSet;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Total-order broadcast between replica <em>processes</em>, over RMI: the networked twin of
 * {@link dev.sobatista.tuplo.cluster.SequencerTotalOrder}.
 *
 * <p><b>Sequencer.</b> The lowest-numbered live replica stamps every command with the next sequence number and pushes
 * it to every live replica, one after the other, waiting for each to accept it. Because each replica receives the
 * stream in sequence order (and buffers any gap), all replicas apply the same commands in the same order — which is
 * all {@link dev.sobatista.tuplo.cluster.SmrReplica} needs.
 *
 * <p><b>Sequencer crash.</b> If the sequencer dies halfway through a fan-out, some replicas have command {@code n} and
 * some don't. The next-lowest replica takes over: it asks every survivor how far it got, fetches what it is missing,
 * re-sends the tail to whoever is behind, and only then orders anything new. Replicas ignore duplicates, and the new
 * sequencer ignores a retried command it already ordered (commands are identified by origin + request id), so a client
 * whose request was in flight simply retries and gets exactly-once ordering. This relies on the statement's fault
 * model: a perfect failure detector and one fault at a time.
 */
final class RmiTotalOrder implements TotalOrder {

    private record Key(int origin, long reqId) {}

    private final Membership members;
    private final Faults faults;
    private Deliverer local;

    // ---- delivery side (every replica) ----
    private final Object deliveryLock = new Object();
    private final TreeMap<Long, Command> log = new TreeMap<>();      // applied commands, contiguous from 0
    private final TreeMap<Long, Command> early = new TreeMap<>();    // received ahead of a gap
    private long nextDeliver = 0;

    // ---- sequencer side (only on the lowest live replica) ----
    private final Object seqLock = new Object();
    private boolean sequencing = false;
    private long nextSeq = 0;
    private final Set<Key> ordered = new HashSet<>();

    RmiTotalOrder(Membership members, Faults faults) {
        this.members = members;
        this.faults = faults;
        members.onFailure(failed -> {
            // a new sequencer takes over eagerly, so a half-finished fan-out is completed even if nobody submits again
            if (members.leader() == members.self()) {
                Thread.ofVirtual().name("tuplo-takeover").start(this::takeOverIfNeeded);
            }
        });
    }

    @Override
    public void register(Deliverer deliverer) {
        if (local != null) throw new IllegalStateException("one replica per process");
        local = deliverer;
    }

    /** Hand a command to whoever is the sequencer right now; if it dies, retry at the next one. */
    @Override
    public void submit(Command command) {
        try {
            while (true) {
                int leader = members.leader();
                if (leader == members.self()) {
                    order(command);
                    return;
                }
                try {
                    members.call(leader, p -> { p.order(command); return null; });
                    return;
                } catch (Membership.PeerDown down) {
                    // the sequencer is gone: loop, the next-lowest replica is now in charge
                } catch (IllegalStateException notLeaderYet) {
                    Thread.sleep(50);                                // its view lags ours by a heartbeat: retry
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while ordering " + command, e);
        }
    }

    /** Sequencer entry point (local or over RMI). */
    void order(Command command) throws InterruptedException {
        faults.awaitThawed();                                        // a frozen sequencer receives but doesn't order
        synchronized (seqLock) {
            confirmLeadership();
            if (!sequencing) takeOver();
            if (!ordered.add(new Key(command.originReplica(), command.reqId()))) return;   // a retry: already ordered
            long seq = nextSeq++;
            for (int id : members.activeIds()) sendTo(id, seq, command);
        }
    }

    /** Receiving side: apply commands strictly in sequence order, exactly once. */
    void receive(long seq, Command command) {
        synchronized (deliveryLock) {
            if (seq < nextDeliver || early.containsKey(seq)) return;          // duplicate (e.g. a takeover resend)
            early.put(seq, command);
            while (early.containsKey(nextDeliver)) {
                Command next = early.remove(nextDeliver);
                log.put(nextDeliver, next);
                local.deliver(nextDeliver, next);
                nextDeliver++;
            }
        }
    }

    long lastDelivered() { synchronized (deliveryLock) { return nextDeliver - 1; } }

    SortedMap<Long, Command> logFrom(long fromSeq) {
        synchronized (deliveryLock) { return new TreeMap<>(log.tailMap(fromSeq, true)); }
    }

    // ---- sequencer internals (hold seqLock) --------------------------------

    private void takeOverIfNeeded() {
        try {
            synchronized (seqLock) {
                if (!sequencing && members.leader() == members.self()) takeOver();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Refuse to order while a lower replica is still alive (the caller's view is ahead of ours). */
    private void confirmLeadership() throws InterruptedException {
        for (int id : members.activeIds()) {
            if (id >= members.self()) break;
            try {
                members.call(id, p -> { p.ping(); return null; });
                throw new IllegalStateException("replica " + id + " is alive and is the sequencer");
            } catch (Membership.PeerDown down) {
                // confirmed dead; keep checking the next lower id
            }
        }
    }

    /** Become the sequencer: gather everything any survivor applied, and bring every survivor up to the same point. */
    private void takeOver() throws InterruptedException {
        for (int id : members.activeIds()) {
            if (id == members.self()) continue;
            try {
                long mine = lastDelivered();
                if (members.call(id, PeerRemote::lastDelivered) > mine) {
                    members.call(id, p -> p.logFrom(mine + 1)).forEach(this::receive);
                }
            } catch (Membership.PeerDown ignored) { /* one fault at a time: nothing to learn from it */ }
        }
        long last = lastDelivered();
        for (int id : members.activeIds()) {
            if (id == members.self()) continue;
            try {
                long theirs = members.call(id, PeerRemote::lastDelivered);
                for (var e : logFrom(theirs + 1).entrySet()) sendTo(id, e.getKey(), e.getValue());
            } catch (Membership.PeerDown ignored) { /* dropped from the view */ }
        }
        for (Command c : logFrom(0).values()) ordered.add(new Key(c.originReplica(), c.reqId()));
        nextSeq = last + 1;
        sequencing = true;
        System.out.println("[replica " + members.self() + "] now the sequencer, next seq " + nextSeq);
    }

    private void sendTo(int id, long seq, Command command) throws InterruptedException {
        if (id == members.self()) { receive(seq, command); return; }
        try {
            members.call(id, p -> { p.deliver(seq, command); return null; });
        } catch (Membership.PeerDown ignored) {
            // it crashed: the failure detector already dropped it from the view
        }
    }
}
