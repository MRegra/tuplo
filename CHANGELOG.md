# Changelog

All notable changes to Tuplo are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and Tuplo uses
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.3.0] - 2026-09-27
### Added
- **Networked replication**: `ReplicaNode` runs one SMR or XL replica per JVM; replicas talk over RMI
  (`PeerRemote`), clients use the existing `RemoteTupleSpace`, the PuppetMaster uses `NodeControl`.
- **Total order over RMI** (`RmiTotalOrder`): the lowest live replica sequences; if it crashes the next one collects
  what the survivors applied, fills the gaps and takes over. Retried commands are ordered at most once.
- **Failure detector** (`Membership`): unreachable = failed (perfect-detector model), triggered by failed calls and a
  heartbeat, gossiped to every survivor's `View`.
- `XlNetwork` / `XlPeer`: the seam that lets the same `XlReplica` run in-process or over RMI.
- **Networked PuppetMaster**: `PuppetMaster --nodes host:port/name,...` (script or console) via `RemoteCluster`;
  `crash` halts the target process; `status` shows each replica's view and presumed-failed peers.
- Message delays (`--delay-min/--delay-max`) on every incoming client and replica message.
- `MultiProcessClusterTest`: forks three replica JVMs per test, for SMR and XL: convergence, crash of a plain replica
  and of the sequencer/coordinator, freeze/unfreeze, delays + script-client, PuppetMaster main.
- `docs/SPEC.md` traceability matrix (statement requirement → test) and a JaCoCo gate: 100% line coverage for
  `tuplo-core`, 99% for `tuplo-cluster` (everything but one documented, unreachable defensive catch).
- 141 tests total (was 55).

### Fixed
- XL: a take could hang forever when the taker saw a tuple before its coordinator had stored it (the coordinator
  refused the grant and nothing woke the taker again). The coordinator now grants any tuple that isn't tombstoned.
- XL: a coordinator crash during concurrent takes could hand the same tuple to two clients (found by the F-0603
  adversary review: the crashed coordinator's grant lived only on that process, so a successor coordinator with no
  record of it could grant the same tuple again while the first taker's remove was still in flight). The coordinator
  now replicates its grant decision to every active replica before answering, and `take` retries a tuple against its
  new coordinator instead of abandoning it when the old one crashes mid-attempt.

## [0.2.0] - 2026-09-26
### Added
- **XL variant** (`XlReplica`/`XlCluster`) in the spirit of Xu & Liskov: full replication, parallel adds (no total
  order), and take coordinated per-tuple with view-based failover — the deliberate contrast to SMR. Tombstones make
  out-of-order store/remove safe; a version counter closes the take lost-wakeup window.
- **Fault tolerance**: `crash`, `freeze`/`unfreeze` on both variants; a shared `View` (perfect failure detector).
- **PuppetMaster** command interpreter (status/crash/freeze/unfreeze/client/wait) driving either variant via `ClusterControl`.
- A shared `TupleSpaceContract` test run against local, SMR and XL; XL and fault-tolerance integration tests.
- 55 tests total (was 25).

## [0.1.0] - 2026-09-26
### Added
- `tuplo-core`: `Tuple`, `Schema`, `Field` (strings, objects, and the wildcard family), a field parser,
  and a thread-safe blocking `LocalTupleSpace` with deterministic oldest-first matching.
- `tuplo-cluster`: sequencer-based total-order broadcast and an `SmrReplica` (state-machine replication)
  so N replicas apply the same ordered commands and converge to identical state; a `Cluster` helper.
- `tuplo-node`: an RMI `TupleSpaceServer`, a client library, a `ScriptClient` that runs `.tuplo` scripts,
  a message-delay wrapper, and an in-process `SmrDemo`.
- 25 tests covering matching, blocking semantics, SMR convergence and the RMI round-trip.

[Unreleased]: https://github.com/MRegra/tuplo/compare/v0.3.0...HEAD
[0.3.0]: https://github.com/MRegra/tuplo/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/MRegra/tuplo/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/MRegra/tuplo/releases/tag/v0.1.0
