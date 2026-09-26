# Roadmap

Tuplo is built in layers so each milestone is a self-contained thing to learn and to write about.

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
- **Networked SMR** — run the total-order layer over RMI so replicas are real separate processes/hosts (0.1.0 proves the algorithm in-process; this makes it a real cluster). *(post: "Total order over a real network")*
- **Networked PuppetMaster + PCS** — launch real server/client processes across machines over RMI from a config (the command language already exists in-process). *(post: "A puppet master for chaos testing")*
- **Benchmarks** — the workloads where SMR wins and where XL wins, with numbers.
- **Advanced fault model** — make progress with only a majority responding (drop the perfect-failure-detector assumption). *(post: "Majority rules: living without a perfect failure detector")*
- **Transport hardening** — the security angle: RMI deserialization risk, a safer wire format, auth/TLS. *(post: "Your RMI endpoint is a deserialization gadget waiting to happen")*
- **CI/pipeline polish** — release automation, coverage, spotless/checkstyle. *(post: "Shipping a Java library like you mean it")*
