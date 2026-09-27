package dev.sobatista.tuplo.cluster;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * How an {@link XlReplica} reaches the rest of its cluster: who is in the view, and which peer coordinates a tuple.
 *
 * <p>{@link XlCluster} implements this in-process; {@code tuplo-node} implements it over RMI with replicas in separate
 * JVMs. The replica itself doesn't know which one it runs on.
 */
public interface XlNetwork {

    /** Every replica currently in the view, including the caller, in ascending id order. */
    List<XlPeer> activePeers();

    /** The coordinator for a tuple (see {@link #coordinatorId}), or {@code null} if nobody is left. */
    XlPeer coordinatorOf(XlReplica.TupleId tid);

    /** Whether a replica is still in the view (perfect failure detector). */
    boolean isActive(int replicaId);

    /**
     * The coordinator rule, shared by every transport so all replicas compute the same answer without extra chatter:
     * the tuple's origin if it is alive, else the lowest-numbered survivor.
     */
    static Optional<Integer> coordinatorId(XlReplica.TupleId tid, Collection<Integer> active) {
        if (active.contains(tid.origin())) return Optional.of(tid.origin());
        return active.stream().min(Integer::compareTo);
    }
}
