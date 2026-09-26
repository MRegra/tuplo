# Roadmap

Tuplo is built in layers so each milestone is a self-contained thing to learn and to write about.

## Shipped in 0.1.0
- The tuple space: tuples, schemas, the full wildcard family, blocking `read`/`take`, deterministic matching.
- State-machine replication over sequencer-based total-order broadcast; N replicas converge.
- RMI server, client library, script-client, message-delay injection. 25 tests.

## Next
- **Networked SMR** — run the total-order layer over RMI so replicas are real separate processes/hosts (0.1.0 proves the algorithm in-process; this makes it a real cluster). *(post: "Total order over a real network")*
- **Perfect failure detector + views** — detect a downed replica, update the active-server view, keep serving. *(post: "What happens when a replica dies")*
- **The XL variant** — the Xu–Liskov replication scheme, sharing the `TupleSpace`/`TotalOrder` seams with SMR. *(post: "Two ways to replicate a tuple space")*
- **PuppetMaster + PCS** — the orchestration console: launch servers/clients from a config, plus `Crash`/`Freeze`/`Unfreeze` to inject faults on demand. *(post: "A puppet master for chaos testing")*
- **Benchmarks** — the workloads where SMR wins and where XL wins, with numbers.
- **Advanced fault model** — make progress with only a majority responding (drop the perfect-failure-detector assumption). *(post: "Majority rules: living without a perfect failure detector")*
- **Transport hardening** — the security angle: RMI deserialization risk, a safer wire format, auth/TLS. *(post: "Your RMI endpoint is a deserialization gadget waiting to happen")*
- **CI/pipeline polish** — release automation, coverage, spotless/checkstyle. *(post: "Shipping a Java library like you mean it")*
