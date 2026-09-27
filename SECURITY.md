# Security Policy

## Supported versions
Tuplo is pre-1.0; only the latest `0.x` release gets fixes.

## Reporting a vulnerability
Please **do not** open a public issue for a vulnerability. Email **sobatistacyber@gmail.com** with details and a
proof of concept if you have one. You'll get an acknowledgement within a few days.

Tuplo is a teaching implementation of a distributed tuple space — it is **not hardened for untrusted networks**.
Java RMI in particular deserializes remote input; run Tuplo only on networks you trust. A `ReplicaNode` exposes two
unauthenticated interfaces on the same RMI port: `NodeControl` for the PuppetMaster (`crash`, `freeze`, `unfreeze` —
anyone who can reach the port can stop or stall a replica) and `PeerRemote` for replica-to-replica traffic (`peerFailed`,
`order`, `deliver`, and the XL peer messages — anyone who can reach the port can forge gossip to evict a live replica
from the view, or inject commands straight into the replicated log). Hardening the transport
(auth, TLS, allow-lists, a safer wire format) is on the [roadmap](ROADMAP.md).
