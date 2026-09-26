package dev.sobatista.tuplo.cluster;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

/**
 * An in-process XL cluster: {@code n} {@link XlReplica}s sharing one {@link View}. A client talks to any replica.
 *
 * <p>The cluster owns the two things the replicas need to reach each other: the current view (who is active) and the
 * rule for which replica coordinates a given tuple. Coordinator selection is the origin replica if it's alive, else the
 * lowest-numbered survivor — deterministic, so every replica computes the same coordinator without extra chatter.
 */
public final class XlCluster implements ClusterControl {

    private final List<XlReplica> replicas;
    private final View view;

    public XlCluster(int n) {
        if (n < 1) throw new IllegalArgumentException("a cluster needs at least one replica");
        this.view = new View(n);
        this.replicas = IntStream.range(0, n).mapToObj(i -> new XlReplica(i, this)).toList();
    }

    public XlReplica replica(int i) { return replicas.get(i); }

    public List<XlReplica> replicas() { return replicas; }

    public int size() { return replicas.size(); }

    @Override public dev.sobatista.tuplo.core.TupleSpace replicaSpace(int i) { return replicas.get(i); }

    @Override public String status() {
        var sb = new StringBuilder("XL cluster (" + view + "):\n");
        for (var r : replicas) sb.append(String.format("  replica %d: %d tuple(s)%s%n",
                r.id(), r.size(), view.isActive(r.id()) ? "" : " [failed]"));
        return sb.toString();
    }

    public View view() { return view; }

    /** The replicas currently in the view (add/read/take only talk to these). */
    List<XlReplica> activeReplicas() {
        List<XlReplica> out = new ArrayList<>();
        for (XlReplica r : replicas) if (view.isActive(r.id())) out.add(r);
        return out;
    }

    /** The coordinator for a tuple: its origin if alive, else the lowest active id, or null if the cluster is empty. */
    XlReplica coordinatorOf(XlReplica.TupleId tid) {
        if (view.isActive(tid.origin())) return replicas.get(tid.origin());
        return view.active().stream().min(Integer::compareTo).map(replicas::get).orElse(null);
    }

    // ---- fault injection (what the PuppetMaster drives) -------------------

    /** Crash a replica: it stops, leaves the view, and its outstanding grants are released clusterwide. */
    public void crash(int i) {
        replicas.get(i).faults.crash();
        view.fail(i);
        for (XlReplica r : replicas) r.onPeerFailed(i);
    }

    public void freeze(int i) { replicas.get(i).faults.freeze(); }

    public void unfreeze(int i) { replicas.get(i).faults.unfreeze(); }
}
