package dev.sobatista.tuplo.node;

import dev.sobatista.tuplo.core.Tuple;

import java.rmi.Remote;
import java.rmi.RemoteException;
import java.util.List;

/** The PuppetMaster's handle on one replica process: status, fault injection and a state snapshot for checks. */
public interface NodeControl extends Remote {

    /** True once the replica has reached every peer and is serving. */
    boolean isReady() throws RemoteException;

    /** Who I am, my view (who is alive / presumed failed), how many tuples I hold, frozen or not. */
    String status() throws RemoteException;

    /** The tuples this replica holds, oldest first. */
    List<Tuple> snapshot() throws RemoteException;

    /** Crash the process for real (it halts right after answering). */
    void crash() throws RemoteException;

    /** Keep receiving messages but stop processing them until {@link #unfreeze()}. */
    void freeze() throws RemoteException;

    /** Process everything that piled up while frozen, in order. */
    void unfreeze() throws RemoteException;
}
