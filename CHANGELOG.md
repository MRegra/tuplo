# Changelog

All notable changes to Tuplo are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and Tuplo uses
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]
### Added
- Security CI: CodeQL (Java), a gitleaks secret scan over the full history, and dependency review on PRs.
- Dependabot for Maven and GitHub Actions; all workflow actions pinned by commit SHA with least-privilege permissions.
- Spotless style check in `mvn verify` (unused imports, trailing whitespace, final newline); fix with `mvn spotless:apply`.

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

[Unreleased]: https://github.com/MRegra/tuplo/compare/v0.2.0...HEAD
[0.2.0]: https://github.com/MRegra/tuplo/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/MRegra/tuplo/releases/tag/v0.1.0
