package dev.sobatista.tuplo.node;

import dev.sobatista.tuplo.core.Schema;
import dev.sobatista.tuplo.core.Tuple;

import java.rmi.Remote;
import java.rmi.RemoteException;

/**
 * The network face of a tuple-space server, over Java RMI.
 *
 * <p>RMI is the JDK-native descendant of the .NET Remoting the original project used, which keeps the wire
 * plumbing out of the way so the interesting code stays the algorithms. {@link Tuple}, {@link Schema} and
 * their {@code Field}s are {@code Serializable}, so they travel across the wire unchanged.
 */
public interface RemoteTupleSpace extends Remote {

    void add(Tuple tuple) throws RemoteException;

    /** Blocks on the server until a tuple matches. */
    Tuple read(Schema schema) throws RemoteException, InterruptedException;

    /** Blocks on the server until a tuple matches, then removes it. */
    Tuple take(Schema schema) throws RemoteException, InterruptedException;

    /** Brief human-readable status (who I am, how many tuples I hold). */
    String status() throws RemoteException;
}
