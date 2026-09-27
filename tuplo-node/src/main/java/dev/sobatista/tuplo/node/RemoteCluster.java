package dev.sobatista.tuplo.node;

import dev.sobatista.tuplo.cluster.ClusterControl;
import dev.sobatista.tuplo.core.Tuple;
import dev.sobatista.tuplo.core.TupleSpace;

import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.util.List;

/**
 * The PuppetMaster's networked mode: a {@link ClusterControl} whose replicas are {@link ReplicaNode} processes reached
 * over RMI (possibly on other machines), instead of objects in this JVM. The same PuppetMaster script therefore drives
 * an in-process cluster or a real one — {@code crash} here really kills a process.
 */
public final class RemoteCluster implements ClusterControl {

    private final List<Membership.Address> nodes;

    /** {@code nodes} is the same {@code host:port/name} list the replicas were started with. */
    public RemoteCluster(String nodes) {
        this.nodes = Membership.Address.parseList(nodes);
        if (this.nodes.isEmpty()) throw new IllegalArgumentException("no replica addresses given");
    }

    @Override public int size() { return nodes.size(); }

    @Override public TupleSpace replicaSpace(int i) { return new RemoteTupleSpaceClient(lookup(i, RemoteTupleSpace.class)); }

    /** The control handle of replica {@code i}. */
    public NodeControl control(int i) { return lookup(i, NodeControl.class); }

    @Override public void crash(int i) { run(() -> control(i).crash()); }

    @Override public void freeze(int i) { run(() -> control(i).freeze()); }

    @Override public void unfreeze(int i) { run(() -> control(i).unfreeze()); }

    @Override
    public String status() {
        var sb = new StringBuilder("networked cluster:\n");
        for (int i = 0; i < nodes.size(); i++) {
            String line;
            try {
                line = control(i).status();
            } catch (RemoteException | RuntimeException e) {
                line = "replica " + i + " at " + nodes.get(i) + ": unreachable (crashed)";
            }
            sb.append("  ").append(line).append('\n');
        }
        return sb.toString();
    }

    /** The tuples replica {@code i} holds (for convergence checks). */
    public List<Tuple> snapshot(int i) {
        try { return control(i).snapshot(); } catch (RemoteException e) { throw new IllegalStateException(e); }
    }

    /** Block until every replica has joined its peers. */
    public void awaitReady(long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        for (int i = 0; i < nodes.size(); i++) {
            while (true) {
                try {
                    if (control(i).isReady()) break;
                } catch (RemoteException | RuntimeException notYet) {
                    // not bound yet
                }
                if (System.currentTimeMillis() > deadline) throw new IllegalStateException("replica " + nodes.get(i) + " not ready");
                Thread.sleep(100);
            }
        }
    }

    private <T> T lookup(int i, Class<T> type) {
        if (i < 0 || i >= nodes.size()) throw new IllegalArgumentException("no replica " + i + " (size " + nodes.size() + ")");
        var a = nodes.get(i);
        try {
            Registry registry = LocateRegistry.getRegistry(a.host(), a.port());
            return type.cast(registry.lookup(a.name()));
        } catch (RemoteException | NotBoundException e) {
            throw new IllegalStateException("cannot reach replica " + i + " at " + a, e);
        }
    }

    private interface RemoteAction { void run() throws RemoteException; }

    private static void run(RemoteAction action) {
        try { action.run(); } catch (RemoteException e) { throw new IllegalStateException(e); }
    }
}
