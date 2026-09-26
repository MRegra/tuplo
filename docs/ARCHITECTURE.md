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

RMI is deliberate: it's the JDK-native descendant of the .NET Remoting the original used, so there are zero
transport dependencies and the code stays about the *algorithms*. (Its deserialization risk is real — see
SECURITY.md and the roadmap.)

## Why the seams are where they are
`TupleSpace` (the operator API) and `TotalOrder` (the ordering API) are the two interfaces everything else plugs
into. A new replication scheme is a new `TotalOrder` and/or a new state machine — the client library, the server
and the scripts don't change. That's how the XL variant and the advanced fault models land without a rewrite.
