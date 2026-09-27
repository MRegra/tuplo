package dev.sobatista.tuplo.cluster;

import dev.sobatista.tuplo.core.Tuple;

/**
 * What one XL replica can ask of another: the three peer messages of the XL protocol.
 *
 * <p>In-process the peer is simply the other {@link XlReplica}; over the network (see {@code tuplo-node}) it is a thin
 * RMI proxy. {@link XlReplica} only ever talks to peers through this interface, so the algorithm is the same code in
 * both cases.
 */
public interface XlPeer {

    /** This peer's replica id. */
    int id();

    /** Store a tuple that some replica added (a late store for an already-taken tuple is ignored). */
    void receiveStore(XlReplica.TupleId tid, Tuple tuple);

    /** Remove a tuple that was taken (leaves a tombstone so a late store cannot resurrect it). */
    void receiveRemove(XlReplica.TupleId tid);

    /** Coordinator side: grant the tuple to exactly one taker; {@code false} if it is gone or reserved. */
    boolean grant(XlReplica.TupleId tid, XlReplica.ReqId req);

    /**
     * The coordinator's grant decision, replicated to every other active replica so a successor coordinator (picked
     * when this one crashes) already knows the tuple is taken and does not hand it out a second time.
     */
    void receiveGrant(XlReplica.TupleId tid, XlReplica.ReqId req);
}
