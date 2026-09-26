# Changelog

All notable changes to Tuplo are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and Tuplo uses
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.1.0] - 2026-09-26
### Added
- `tuplo-core`: `Tuple`, `Schema`, `Field` (strings, objects, and the wildcard family), a field parser,
  and a thread-safe blocking `LocalTupleSpace` with deterministic oldest-first matching.
- `tuplo-cluster`: sequencer-based total-order broadcast and an `SmrReplica` (state-machine replication)
  so N replicas apply the same ordered commands and converge to identical state; a `Cluster` helper.
- `tuplo-node`: an RMI `TupleSpaceServer`, a client library, a `ScriptClient` that runs `.tuplo` scripts,
  a message-delay wrapper, and an in-process `SmrDemo`.
- 25 tests covering matching, blocking semantics, SMR convergence and the RMI round-trip.

[Unreleased]: https://github.com/MRegra/tuplo/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/MRegra/tuplo/releases/tag/v0.1.0
