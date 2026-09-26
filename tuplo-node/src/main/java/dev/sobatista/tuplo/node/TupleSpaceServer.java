package dev.sobatista.tuplo.node;

import dev.sobatista.tuplo.core.LocalTupleSpace;
import dev.sobatista.tuplo.core.Schema;
import dev.sobatista.tuplo.core.Tuple;
import dev.sobatista.tuplo.core.TupleSpace;

import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;

/**
 * A single tuple-space server exposed over RMI.
 *
 * <p>Run it, point clients at {@code rmi://host:port/name}, and you have a working (single-node) tuple
 * space. Replication across servers is layered on top via {@code tuplo-cluster}'s total-order broadcast;
 * see the ROADMAP for the networked SMR wiring. Message delays model a slow/lossy network for experiments.
 *
 * <pre>{@code
 *   mvn -q -pl tuplo-node exec:java -Dexec.mainClass=dev.sobatista.tuplo.node.TupleSpaceServer \
 *       -Dexec.args="--name s1 --port 1099 --delay-min 0 --delay-max 0"
 * }</pre>
 */
public final class TupleSpaceServer extends UnicastRemoteObject implements RemoteTupleSpace {

    private final String name;
    private final TupleSpace space;

    private TupleSpaceServer(String name, TupleSpace space) throws RemoteException {
        this.name = name;
        this.space = space;
    }

    @Override public void add(Tuple tuple) { space.add(tuple); System.out.println("[" + name + "] add " + tuple); }

    @Override public Tuple read(Schema schema) throws InterruptedException {
        Tuple t = space.read(schema);
        System.out.println("[" + name + "] read " + schema + " -> " + t);
        return t;
    }

    @Override public Tuple take(Schema schema) throws InterruptedException {
        Tuple t = space.take(schema);
        System.out.println("[" + name + "] take " + schema + " -> " + t);
        return t;
    }

    @Override public String status() {
        return "server " + name + ": " + space.size() + " tuple(s)";
    }

    public static void main(String[] args) throws Exception {
        var a = Args.parse(args);
        String name = a.get("name", "s1");
        int port = Integer.parseInt(a.get("port", "1099"));
        int dmin = Integer.parseInt(a.get("delay-min", "0"));
        int dmax = Integer.parseInt(a.get("delay-max", "0"));

        TupleSpace space = new DelayingTupleSpace(new LocalTupleSpace(), dmin, dmax);
        var server = new TupleSpaceServer(name, space);

        Registry registry;
        try {
            registry = LocateRegistry.createRegistry(port);
        } catch (RemoteException e) {
            registry = LocateRegistry.getRegistry(port);   // a registry is already up on this port
        }
        registry.rebind(name, server);
        System.out.printf("Tuplo server '%s' bound at rmi://localhost:%d/%s (delay %d-%dms). Ctrl-C to stop.%n",
                name, port, name, dmin, dmax);
    }
}
