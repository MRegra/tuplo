# Tuplo — architecture

Tuplo is three layers, each a Maven module, each usable on its own. The point of the split is that the
interesting distributed-systems code doesn't get tangled up with transport plumbing.

```
                 clients (script-client, your app)
                          │  TupleSpace API: add / read / take
        ┌─────────────────┴───────────────────────────────┐
        │ tuplo-node   RMI server · client library · script runner · delay/freeze
        └─────────────────┬───────────────────────────────┘
                          │  same TupleSpace API
        ┌─────────────────┴───────────────────────────────┐
        │ tuplo-cluster   SmrReplica  ── uses ──►  TotalOrder (sequencer)
        │                 N replicas, one agreed command history
        └─────────────────┬───────────────────────────────┘
                          │
        ┌─────────────────┴───────────────────────────────┐
        │ tuplo-core   Tuple · Schema · Field · matching · LocalTupleSpace (blocking)
        └──────────────────────────────────────────────────┘
```

## tuplo-core — the space
The heart is `LocalTupleSpace`: a multiset of tuples behind a `ReentrantLock` with one `Condition`.
`add` appends and signals; `read`/`take` loop on the condition until a match exists (so a consumer that asks
too early simply parks). Matching always picks the **oldest** matching tuple, which keeps behaviour
deterministic — essential once we replicate. `Field` is a sealed interface (strings, objects, and the wildcard
records); matching is one polymorphic method per field kind, so there's no giant `switch`.

## tuplo-cluster — making it distributed
Two ideas:

1. **`TotalOrder`** — total-order broadcast. `submit(command)` hands an operation to the ordering layer; every
   replica later gets `deliver(seq, command)` in the *same order*. `SequencerTotalOrder` is the simplest correct
   implementation: one place stamps a monotonically increasing sequence number and fans the command out. Because
   replicas depend only on the `TotalOrder` interface, swapping the sequencer for a consensus/leaderless ordering
   (the advanced "majority" mode, and the XL variant) doesn't touch them.

2. **`SmrReplica`** — a deterministic state machine. It holds the whole space and applies delivered commands in
   order. `add` appends. `take`/`read` that can't match yet are parked in an **ordered pending list** and
   re-driven whenever a later `add` arrives — in sequence order, so every replica resolves the same take with the
   same tuple. A client talks to one replica; that replica submits the op, and when the op's effect is known
   (delivered everywhere, in order) the origin replica answers the waiting client. Every replica makes the same
   state change; only the origin replies.

That's state-machine replication in one sentence: **same start, same commands, same order, same result.** The
redundancy is the fault tolerance — lose a replica and the others still hold every tuple.

## tuplo-node — making it runnable
`RemoteTupleSpace` is a Java RMI interface with `add`/`read`/`take` (tuples and schemas are `Serializable`, so
they cross the wire as-is). `TupleSpaceServer` binds a space into an RMI registry; `RemoteTupleSpaceClient`
implements the same `TupleSpace` API so app code doesn't care whether the space is local or remote.
`ScriptClient` runs `.tuplo` files. `DelayingTupleSpace` injects a random per-operation delay to make timing and
fault-tolerance corner cases show up in experiments.

## Networked mode
`ReplicaNode` runs one replica per JVM. It exports one RMI object that speaks three interfaces: `RemoteTupleSpace`
(clients), `NodeControl` (the PuppetMaster: status, crash, freeze, unfreeze) and `PeerRemote` (the other
replicas). Every replica is started with the same `--peers host:port/name,...` list; replica `i` is the `i`-th entry.

- **Membership / failure detector** (`Membership`). A peer is declared failed only when it can't be reached at all
  (a fresh registry lookup and a ping both fail): with processes on one machine or a LAN, that means it's gone,
  which is the statement's *perfect* failure detector. Any failed call or the 200 ms heartbeat triggers the check,
  and the verdict is gossiped so every survivor updates its `View`. Before a replica has reached all its peers once,
  an unreachable peer counts as "still starting", never as failed.
- **SMR over the network** (`RmiTotalOrder`, a `TotalOrder`). The lowest live replica is the sequencer: it numbers
  each command and pushes it to every live replica in turn, waiting for each. Replicas apply commands strictly in
  sequence order and ignore duplicates. If the sequencer dies halfway through a push, the next-lowest replica takes
  over: it collects what every survivor applied, fills in the gaps, re-sends the tail to whoever is behind, and only
  then orders new commands. Commands carry `(origin, reqId)`, so a client's retry after the crash is ordered at most
  once. `SmrReplica` is the same class as in-process.
- **XL over the network** (`XlNetwork` + `XlPeer`). `XlReplica` only talks to peers through these two interfaces.
  `XlCluster` implements them in-process; `ReplicaNode` implements them with RMI proxies. A proxy whose peer dies
  mid-call drops it from the view, and the view change releases the dead replica's grants.
- **PuppetMaster** (`RemoteCluster`, a `ClusterControl`). The same PuppetMaster script drives an in-process cluster or
  real processes; networked, `crash` halts the target JVM.

```
  PuppetMaster ──NodeControl──►┐        client / script-client ──RemoteTupleSpace──► any replica
                               ▼
        ┌── ReplicaNode 0 ◄──PeerRemote──► ReplicaNode 1 ◄──PeerRemote──► ReplicaNode 2 ──┐
        │   SmrReplica + RmiTotalOrder   (or)   XlReplica + RMI XlNetwork                   │
        └───────────────────── Membership: view + heartbeat + gossip ──────────────────────┘
```

RMI is deliberate: it's the JDK-native descendant of the .NET Remoting the original used, so there are zero
transport dependencies and the code stays about the *algorithms*. (Its deserialization risk is real — see
SECURITY.md and the roadmap.)

## Why the seams are where they are
`TupleSpace` (the operator API), `TotalOrder` (SMR's ordering API) and `XlNetwork` (XL's peer API) are the
interfaces everything else plugs into. A new replication scheme is a new `TotalOrder` and/or a new state machine — the client library, the server
and the scripts don't change. That's how the XL variant and the advanced fault models land without a rewrite.
