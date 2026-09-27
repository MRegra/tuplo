package dev.sobatista.tuplo.node;

import dev.sobatista.tuplo.cluster.Command;
import dev.sobatista.tuplo.cluster.XlReplica;
import dev.sobatista.tuplo.core.Tuple;

import java.rmi.Remote;
import java.rmi.RemoteException;
import java.util.SortedMap;

/**
 * Replica-to-replica messages, over RMI. A replica process speaks only the half that matches its variant; calling
 * the other half is a configuration error and fails with {@link IllegalStateException}.
 */
public interface PeerRemote extends Remote {

    /** Liveness probe used by the failure detector. */
    void ping() throws RemoteException;

    /** Perfect-failure-detector gossip: {@code replicaId} is dead, drop it from your view. */
    void peerFailed(int replicaId) throws RemoteException;

    // ---- SMR: the total-order layer (see RmiTotalOrder) ----

    /** Ask the sequencer to order a command. Idempotent per (origin, reqId), so a retry after a crash is safe. */
    void order(Command command) throws RemoteException, InterruptedException;

    /** The sequencer hands over command number {@code seq}. Duplicates are ignored; gaps are buffered. */
    void deliver(long seq, Command command) throws RemoteException;

    /** The highest sequence number this replica has applied, or -1. */
    long lastDelivered() throws RemoteException;

    /** Every applied command with sequence number &gt;= {@code fromSeq} (a new sequencer uses this to catch up). */
    SortedMap<Long, Command> logFrom(long fromSeq) throws RemoteException;

    // ---- XL: the three peer messages (see XlPeer) ----

    void xlStore(XlReplica.TupleId tid, Tuple tuple) throws RemoteException;

    void xlRemove(XlReplica.TupleId tid) throws RemoteException;

    boolean xlGrant(XlReplica.TupleId tid, XlReplica.ReqId req) throws RemoteException;

    /** The coordinator's grant decision, replicated so a successor coordinator does not re-grant it. */
    void xlReceiveGrant(XlReplica.TupleId tid, XlReplica.ReqId req) throws RemoteException;
}
