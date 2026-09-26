package dev.sobatista.tuplo.node;

import dev.sobatista.tuplo.core.Schema;
import dev.sobatista.tuplo.core.Tuple;
import dev.sobatista.tuplo.core.TupleSpace;

import java.rmi.registry.LocateRegistry;
import java.util.Optional;

/**
 * Client library: talks to a {@link RemoteTupleSpace} over RMI but presents the same {@link TupleSpace}
 * API as the local one, so application code doesn't care whether the space is in-process or across the
 * network. This is the "client library providing an API to contact the servers" the project asks for.
 */
public final class RemoteTupleSpaceClient implements TupleSpace {

    private final RemoteTupleSpace remote;

    public RemoteTupleSpaceClient(RemoteTupleSpace remote) { this.remote = remote; }

    /** Connect to {@code rmi://host:port/name}. */
    public static RemoteTupleSpaceClient connect(String host, int port, String name) throws Exception {
        var registry = LocateRegistry.getRegistry(host, port);
        return new RemoteTupleSpaceClient((RemoteTupleSpace) registry.lookup(name));
    }

    @Override public void add(Tuple tuple) {
        try { remote.add(tuple); } catch (Exception e) { throw new RuntimeException(e); }
    }

    @Override public Tuple read(Schema schema) throws InterruptedException {
        try { return remote.read(schema); } catch (InterruptedException e) { throw e; } catch (Exception e) { throw new RuntimeException(e); }
    }

    @Override public Tuple take(Schema schema) throws InterruptedException {
        try { return remote.take(schema); } catch (InterruptedException e) { throw e; } catch (Exception e) { throw new RuntimeException(e); }
    }

    @Override public Optional<Tuple> tryRead(Schema schema) { throw new UnsupportedOperationException("non-blocking ops are local-only"); }
    @Override public Optional<Tuple> tryTake(Schema schema) { throw new UnsupportedOperationException("non-blocking ops are local-only"); }
    @Override public int size() {
        try { return -1; } finally { /* size is server-side; use status() */ }
    }

    public String status() {
        try { return remote.status(); } catch (Exception e) { throw new RuntimeException(e); }
    }
}
