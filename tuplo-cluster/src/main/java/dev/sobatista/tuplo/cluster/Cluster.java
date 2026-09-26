package dev.sobatista.tuplo.cluster;

import java.util.List;
import java.util.stream.IntStream;

/**
 * A convenience bundle: one {@link TotalOrder} shared by {@code n} {@link SmrReplica}s, wired together.
 * A client may talk to any replica; they all hold the same state. This is the in-process form used by
 * tests and demos; the networked form lives in {@code tuplo-node}.
 */
public final class Cluster {

    private final List<SmrReplica> replicas;

    public Cluster(int n) {
        if (n < 1) throw new IllegalArgumentException("a cluster needs at least one replica");
        var order = new SequencerTotalOrder();
        this.replicas = IntStream.range(0, n).mapToObj(i -> new SmrReplica(i, order)).toList();
    }

    public SmrReplica replica(int i) { return replicas.get(i); }

    public List<SmrReplica> replicas() { return replicas; }

    public int size() { return replicas.size(); }
}
