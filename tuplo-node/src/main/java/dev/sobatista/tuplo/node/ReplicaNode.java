package dev.sobatista.tuplo.node;

import dev.sobatista.tuplo.cluster.Command;
import dev.sobatista.tuplo.cluster.Faults;
import dev.sobatista.tuplo.cluster.SmrReplica;
import dev.sobatista.tuplo.cluster.XlNetwork;
import dev.sobatista.tuplo.cluster.XlPeer;
import dev.sobatista.tuplo.cluster.XlReplica;
import dev.sobatista.tuplo.core.Schema;
import dev.sobatista.tuplo.core.Tuple;
import dev.sobatista.tuplo.core.TupleSpace;

import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.SortedMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * One replica of a networked cluster, as its own process: the SMR or XL state machine from {@code tuplo-cluster},
 * wired to its peers over RMI.
 *
 * <p>One exported object speaks three protocols, each its own interface: {@link RemoteTupleSpace} for clients,
 * {@link NodeControl} for the PuppetMaster, and {@link PeerRemote} for the other replicas. Start one per replica, all
 * with the same {@code --peers} list (the view: replica {@code i} is the {@code i}-th address):
 *
 * <pre>{@code
 *   java -cp ... dev.sobatista.tuplo.node.ReplicaNode --variant smr --id 0 \
 *        --peers localhost:11000/r0,localhost:11001/r1,localhost:11002/r2 [--delay-min 0 --delay-max 0]
 * }</pre>
 *
 * <p>Message delays ({@code --delay-min/--delay-max}) apply to every incoming client and replica message, as the
 * statement asks; they never reorder a sender's messages because every sender waits for each call to return.
 */
public final class ReplicaNode extends UnicastRemoteObject implements RemoteTupleSpace, NodeControl, PeerRemote {

    /** Which replication scheme this process runs. */
    public enum Variant { SMR, XL }

    private final Variant variant;
    private final Membership members;
    private final int delayMin, delayMax;
    private final Faults faults = new Faults();          // SMR node-level freeze (XL replicas carry their own)
    private final SmrReplica smr;
    private final RmiTotalOrder order;
    private final XlReplica xl;
    private final TupleSpace space;                      // whichever replica the variant uses

    ReplicaNode(Variant variant, Membership members, int delayMin, int delayMax) throws RemoteException {
        if (delayMin < 0 || delayMax < delayMin) throw new IllegalArgumentException("bad delay interval [" + delayMin + ", " + delayMax + "]");
        this.variant = variant;
        this.members = members;
        this.delayMin = delayMin;
        this.delayMax = delayMax;
        if (variant == Variant.SMR) {
            this.order = new RmiTotalOrder(members, faults);
            this.smr = new SmrReplica(members.self(), order);
            this.xl = null;
            this.space = smr;
        } else {
            this.order = null;
            this.smr = null;
            this.xl = new XlReplica(members.self(), new Network());
            members.onFailure(xl::onPeerFailed);
            this.space = xl;
        }
    }

    // ---- RemoteTupleSpace: clients -----------------------------------------

    @Override public void add(Tuple tuple) { requireReady(); delay(); space.add(tuple); }

    @Override public Tuple read(Schema schema) throws InterruptedException { requireReady(); delay(); return space.read(schema); }

    @Override public Tuple take(Schema schema) throws InterruptedException { requireReady(); delay(); return space.take(schema); }

    // ---- NodeControl: the PuppetMaster -------------------------------------

    @Override public boolean isReady() { return members.joined(); }

    @Override
    public String status() {
        List<Integer> failed = new ArrayList<>();
        for (int i = 0; i < members.size(); i++) if (!members.isActive(i)) failed.add(i);
        boolean frozen = variant == Variant.SMR ? faults.isFrozen() : xl.faults().isFrozen();
        return String.format("replica %d (%s): %d tuple(s), view %s, presumed failed %s%s",
                members.self(), variant, space.size(), members.activeIds(), failed, frozen ? ", FROZEN" : "");
    }

    @Override public List<Tuple> snapshot() { return variant == Variant.SMR ? smr.snapshot() : xl.snapshot(); }

    @Override
    public void crash() {
        System.out.println("[replica " + members.self() + "] crash requested: halting");
        // halt after this call has returned, so the PuppetMaster isn't left with a broken connection
        Thread.ofPlatform().start(() -> {
            try { Thread.sleep(50); } catch (InterruptedException ignored) { /* halting anyway */ }
            Runtime.getRuntime().halt(137);
        });
    }

    @Override
    public void freeze() {
        if (variant == Variant.SMR) { faults.freeze(); smr.freeze(); } else xl.faults().freeze();
        System.out.println("[replica " + members.self() + "] frozen");
    }

    @Override
    public void unfreeze() {
        if (variant == Variant.SMR) { faults.unfreeze(); smr.unfreeze(); } else xl.faults().unfreeze();
        System.out.println("[replica " + members.self() + "] unfrozen");
    }

    // ---- PeerRemote: the other replicas ------------------------------------

    @Override public void ping() { }

    @Override public void peerFailed(int replicaId) { members.markFailed(replicaId); }

    @Override public void order(Command command) throws InterruptedException { delay(); smrOnly().order(command); }

    @Override public void deliver(long seq, Command command) { delay(); smrOnly().receive(seq, command); }

    @Override public long lastDelivered() { return smrOnly().lastDelivered(); }

    @Override public SortedMap<Long, Command> logFrom(long fromSeq) { return smrOnly().logFrom(fromSeq); }

    @Override public void xlStore(XlReplica.TupleId tid, Tuple tuple) { delay(); xlOnly().receiveStore(tid, tuple); }

    @Override public void xlRemove(XlReplica.TupleId tid) { delay(); xlOnly().receiveRemove(tid); }

    @Override public boolean xlGrant(XlReplica.TupleId tid, XlReplica.ReqId req, Set<Integer> knownFailed) { delay(); return xlOnly().grant(tid, req, knownFailed); }

    @Override public void xlReceiveGrant(XlReplica.TupleId tid, XlReplica.ReqId req, XlReplica.GrantStamp stamp) { delay(); xlOnly().receiveGrant(tid, req, stamp); }

    // ---- helpers ------------------------------------------------------------

    private RmiTotalOrder smrOnly() {
        if (order == null) throw new IllegalStateException("replica " + members.self() + " runs " + variant + ", not SMR");
        return order;
    }

    private XlReplica xlOnly() {
        if (xl == null) throw new IllegalStateException("replica " + members.self() + " runs " + variant + ", not XL");
        return xl;
    }

    private void requireReady() {
        if (!members.joined()) throw new IllegalStateException("replica " + members.self() + " is still joining its peers");
    }

    private void delay() {
        if (delayMax == 0) return;
        int ms = ThreadLocalRandom.current().nextInt(delayMin, delayMax + 1);
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** XL's view of the cluster: this replica directly, every other one through an RMI proxy. */
    private final class Network implements XlNetwork {
        @Override public List<XlPeer> activePeers() {
            List<XlPeer> out = new ArrayList<>();
            for (int id : members.activeIds()) out.add(peer(id));
            return out;
        }

        @Override public XlPeer coordinatorOf(XlReplica.TupleId tid) {
            return XlNetwork.coordinatorId(tid, members.activeIds()).map(this::peer).orElse(null);
        }

        @Override public boolean isActive(int replicaId) { return members.isActive(replicaId); }

        @Override public Set<Integer> failedIds() {
            Set<Integer> out = new LinkedHashSet<>();
            for (int i = 0; i < members.size(); i++) if (!members.isActive(i)) out.add(i);
            return out;
        }

        @Override public void learnFailed(Set<Integer> ids) {
            for (int failedId : ids) if (failedId != members.self()) members.markFailed(failedId);
        }

        private XlPeer peer(int id) { return id == members.self() ? xl : new RemoteXlPeer(id); }
    }

    /** One remote XL replica. A peer that dies mid-call is dropped from the view and the message is skipped. */
    private final class RemoteXlPeer implements XlPeer {
        private final int id;

        RemoteXlPeer(int id) { this.id = id; }

        @Override public int id() { return id; }

        @Override public void receiveStore(XlReplica.TupleId tid, Tuple tuple) {
            send(() -> members.call(id, p -> { p.xlStore(tid, tuple); return null; }));
        }

        @Override public void receiveRemove(XlReplica.TupleId tid) {
            send(() -> members.call(id, p -> { p.xlRemove(tid); return null; }));
        }

        @Override public boolean grant(XlReplica.TupleId tid, XlReplica.ReqId req, Set<Integer> knownFailed) {
            Boolean granted = send(() -> members.call(id, p -> p.xlGrant(tid, req, knownFailed)));
            return Boolean.TRUE.equals(granted);
        }

        @Override public void receiveGrant(XlReplica.TupleId tid, XlReplica.ReqId req, XlReplica.GrantStamp stamp) {
            send(() -> members.call(id, p -> { p.xlReceiveGrant(tid, req, stamp); return null; }));
        }

        private <T> T send(PeerCall<T> call) {
            try {
                return call.run();
            } catch (Membership.PeerDown down) {
                return null;                                 // it crashed; the view (and grants) already moved on
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
    }

    private interface PeerCall<T> { T run() throws Membership.PeerDown, InterruptedException; }

    // ---- main ---------------------------------------------------------------

    @SuppressWarnings("unused")
    private static volatile ReplicaNode running;

    public static void main(String[] argv) throws Exception {
        var a = Args.parse(argv);
        Variant variant = Variant.valueOf(a.require("variant").toUpperCase());
        int id = Integer.parseInt(a.require("id"));
        var peers = Membership.Address.parseList(a.require("peers"));
        int dmin = Integer.parseInt(a.get("delay-min", "0"));
        int dmax = Integer.parseInt(a.get("delay-max", "0"));

        var members = new Membership(id, peers);
        var node = new ReplicaNode(variant, members, dmin, dmax);
        var me = peers.get(id);
        Registry registry;
        try {
            registry = LocateRegistry.createRegistry(me.port());
        } catch (RemoteException e) {
            registry = LocateRegistry.getRegistry(me.port());           // a registry is already up on this port
        }
        registry.rebind(me.name(), node);
        running = node;                                                  // keep the exported object strongly reachable
        System.out.printf("replica %d (%s) bound at %s, joining %d peer(s)...%n", id, variant, me, peers.size() - 1);
        members.awaitJoined(Long.parseLong(a.get("join-timeout-ms", "60000")));
        System.out.printf("replica %d (%s) READY%n", id, variant);
    }
}
