# Tuplo

**A distributed, fault-tolerant tuple space — in Java, built to be read.**

[![CI](https://github.com/MRegra/tuplo/actions/workflows/ci.yml/badge.svg)](https://github.com/MRegra/tuplo/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue)](LICENSE)
![status](https://img.shields.io/badge/status-0.2.0%20(pre--1.0)-orange)

A tuple space is a shared bag of tuples that any process can write to and read from. You `add` a tuple, and
someone else `read`s or `take`s one that *matches a pattern* — like leaving a note on a shared board and letting
whoever needs it pick it up. [Linda](https://en.wikipedia.org/wiki/Linda_(coordination_language)) introduced the
idea in the 80s; it's still one of the cleanest ways to coordinate processes that don't know about each other.

Tuplo makes that bag **distributed and fault-tolerant**: the tuples live on several servers, so if one dies the
data is still there. It ships **two replication variants** — state-machine replication and a Xu–Liskov-style design — so you can see the trade-off, not just read about it. It's a teaching-first codebase — the goal is that you can read it and *learn how* replication
and coordination actually work, not just import a jar.

## 30-second demo

Requires JDK 21 and Maven.

```bash
mvn install                                   # build the modules
mvn -q -pl tuplo-node exec:java -Dexec.mainClass=dev.sobatista.tuplo.node.SmrDemo
```

You'll see three replicas agree on every operation — a write on one shows up on the others, a `take` removes the
tuple everywhere exactly once, and a *blocking* take parks until some other replica adds a match:

```
added two tasks on replicas 0 and 1
replica 2 reads: <"task", "build">
replica 2 takes a task: <"task", "build">
sizes after take -> r0=1 r1=1 r2=1 (all equal = replicas agree)
replica 0 take(<"ready">) is blocking, waiting for a match...
replica 2 adds <"ready"> -> unblocks the take on replica 0
```

## The three operators

```java
TupleSpace space = new LocalTupleSpace();          // or a replicated SmrReplica from a Cluster
space.add(Fields.parseTuple("\"task\", \"build\", 3"));

Tuple t = space.read(Fields.parseSchema("\"task\", \"*\", null"));  // matches, doesn't remove; blocks until one exists
Tuple u = space.take(Fields.parseSchema("\"task\", \"build\", null")); // matches and removes; also blocks
```

Fields are strings or small objects, and schemas add wildcards:

| Field in a schema | Matches |
|---|---|
| `"build"` | exactly the string `build` |
| `"*"` | any string |
| `"job*"` / `"*.log"` | strings with that prefix / suffix |
| `Point(1, 2)` | that exact object |
| `Point` | any object of type `Point` |
| `null` | any object |

## Modules

| Module | What's inside |
|---|---|
| **tuplo-core** | The tuple space itself — `Tuple`, `Schema`, `Field`, wildcard matching, the blocking `LocalTupleSpace`. Start here. |
| **tuplo-cluster** | The distributed part — **two** replication variants: **SMR** (total-order broadcast, one agreed history) and **XL** (Xu–Liskov spirit: full replication, parallel adds, per-tuple coordinated take). Plus crash / freeze / unfreeze and a shared view. |
| **tuplo-node** | The runnable part — an RMI server, a client library, a script-client that runs `.tuplo` files, and a **PuppetMaster** that drives either variant and injects faults. |

## Run a server and a script

```bash
# terminal 1 — a server
mvn -q -pl tuplo-node exec:java -Dexec.mainClass=dev.sobatista.tuplo.node.TupleSpaceServer \
    -Dexec.args="--name s1 --port 1099"

# terminal 2 — a client running a script
mvn -q -pl tuplo-node exec:java -Dexec.mainClass=dev.sobatista.tuplo.node.ScriptClient \
    -Dexec.args="--host localhost --port 1099 --name s1 --script examples/producer.tuplo"
```

## How it works

Read [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) for the design, [`docs/SPEC.md`](docs/SPEC.md) for the exact
semantics, and [`ROADMAP.md`](ROADMAP.md) for what's next (the XL variant, networked SMR, crash/freeze injection,
benchmarks). Write-ups that explain the algorithms live in [`docs/posts/`](docs/posts).

## Status & scope

Pre-1.0: the API can still move. This is a **teaching implementation** — not hardened for untrusted networks
(see [SECURITY.md](SECURITY.md)). Contributions and questions welcome — see [CONTRIBUTING.md](CONTRIBUTING.md).

## Provenance

Tuplo grew out of a university distributed-systems assignment (IST, "DIDA-TUPLE"). It is a clean-room Java
reimplementation and expansion — the original C# is not included, and this repo is its own project under the
Apache-2.0 license.

## License

[Apache-2.0](LICENSE).
