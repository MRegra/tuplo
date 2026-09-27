# Roadmap

Tuplo is built in layers so each milestone is a self-contained thing to learn and to write about.

## Shipped in 0.3.0
- **Networked SMR and XL**: every replica is its own process (`ReplicaNode`), peers talk RMI. SMR's total order runs
  over the network with sequencer failover; XL's peer messages go through the same `XlReplica` code.
- **Perfect failure detector** over the network: failed calls + heartbeat + gossip keep every survivor's view in sync.
- **Networked PuppetMaster**: the same command language drives real processes; `crash` kills the JVM.
- **Multi-process integration tests**: three forked JVMs per test, both variants: convergence, crash (including the
  sequencer / coordinator), freeze, message delays, script-client, PuppetMaster.
- **Spec traceability** (`docs/SPEC.md`) and a **JaCoCo gate** (core 100% lines; cluster 100% minus a documented exclusion). 139 tests.

## Shipped in 0.2.0
- **The XL variant** (Xu–Liskov spirit): full replication, parallel adds, per-tuple coordinated take with view-based failover.
- **Fault tolerance**: crash + freeze/unfreeze on both variants, a shared view (perfect failure detector), tested (a replica dies mid-flight, data survives).
- **PuppetMaster** command interpreter (status/crash/freeze/unfreeze/client/wait) driving either variant.
- Shared contract tests across local/SMR/XL. 55 tests.

## Shipped in 0.1.0
- The tuple space: tuples, schemas, the full wildcard family, blocking `read`/`take`, deterministic matching.
- State-machine replication over sequencer-based total-order broadcast; N replicas converge.
- RMI server, client library, script-client, message-delay injection. 25 tests.

## Next
- **PCS + process launching** — a Process Creation Service per machine (port 10000) so the PuppetMaster's `server`/`client` commands start processes remotely from a config; asynchronous command execution and step-by-step mode. *(post: "A puppet master for chaos testing")*
- **Replicated XL grants** — today a take granted by a coordinator that crashes before the remove lands could be granted again by its successor; replicate the reservation (or make the successor ask the survivors) to close that window.
- **Benchmarks** — the workloads where SMR wins and where XL wins, with numbers.
- **Advanced fault model** — make progress with only a majority responding (drop the perfect-failure-detector assumption). *(post: "Majority rules: living without a perfect failure detector")*
- **Transport hardening** — the security angle: RMI deserialization risk, a safer wire format, auth/TLS. *(post: "Your RMI endpoint is a deserialization gadget waiting to happen")*
- **CI/pipeline polish** — release automation, coverage, spotless/checkstyle. *(post: "Shipping a Java library like you mean it")*
