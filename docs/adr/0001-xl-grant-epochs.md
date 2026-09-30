# ADR 0001: XL grant records carry the coordinator's failure epoch

- Status: accepted (F-0603, PR #6)
- Date: 2026-09-30

## Context

In XL, every tuple has one coordinator: its origin, or the lowest surviving replica id if the origin crashed. The
coordinator grants a take to exactly one request. An earlier fix in this PR made `grant()` copy its decision to
every active peer before returning, so a successor coordinator knows about grants made by the one before it. Copies
were applied with "last writer wins" and carried no information about who wrote them or when.

A second adversary round showed this failure. A copy sent by a coordinator just before it crashed arrives late
(`ReplicaNode.xlReceiveGrant` waits in `delay()`) and overwrites a grant the successor has already made to live
taker B. The original taker A then retries under the same `ReqId` and also gets the tuple. That violates
exactly-once take (`docs/SPEC.md` R4, R16).

Facts the fix relies on:
- `grant()` returns true only after every copy has been applied, so a taker whose copy is still in flight was never
  told it won.
- Replicas crash and stay down (`View.recover` has no callers).
- Faults happen one at a time.
- The failure detector is perfect: `Membership.markFailed` only runs after a failed reachability check.

## Decision

- Each grant record stores `(ReqId, epoch)` (`XlReplica.Grant`). The epoch is the number of replicas the granting
  coordinator knows have failed (`XlNetwork.failedIds().size()`). It only grows under crash-stop.
- The taker sends its own known-failed set with `grant(tid, req, knownFailed)`. The coordinator merges that set into
  its own view via `XlNetwork.learnFailed(...)` (safe with a perfect detector) *before* checking anything else, and
  refuses unless it is the coordinator for that tuple in its own view after the merge. Then the existing live-holder
  check applies. The merge happens outside the replica's own lock, because it can call back into `onPeerFailed`,
  which takes that lock — merging while holding it would be a lock-order inversion.
- Grant copies carry `XlReplica.GrantStamp(epoch, failed)`. The receiver (`receiveGrant`) merges the failed set,
  then:
  - ignores the copy if the tuple is tombstoned;
  - ignores it if its epoch is older than the recorded one (a deposed coordinator's stale copy);
  - ignores it if the epochs are equal, the recorded holder is live, and it is a different request (first writer
    wins at a tied epoch);
  - otherwise writes it.
- `take` and `tryTake` share one loop (`tryGrant`) that retries across successive coordinators, so a `tryTake`
  reservation can no longer be stranded if its coordinator crashes mid-attempt.

## Alternatives considered

- **Never overwrite a live holder in `receiveGrant`** (the adversary's smallest fix). It closes the reported
  interleaving. But when a second crash follows (a survivor kept taker A from the dead coordinator, a later
  coordinator granted taker B, then that coordinator also crashes), the survivor can grant to A again. Rejected as
  incomplete.
- **Drop copies from senders that have left the view.** This depends on the receiver having already noticed the
  failure, which may not have happened when the delayed copy lands. Epochs give the same protection without that
  dependency.
- **Full consensus (Paxos/Raft) per grant.** Correct, but out of proportion for a teaching tuple space whose stated
  fault model is one crash at a time with a perfect detector.
- **Put a view number in `View`.** A view counter that only increases is equivalent under crash-stop. The
  failed-set size needs no new shared state, and sending the set along also spreads failure information faster.

## Consequences

- The signatures of `PeerRemote.xlGrant` and `PeerRemote.xlReceiveGrant` change, so every replica in a cluster must
  run the same version. Fine for 0.3.0, noted in `CHANGELOG.md`.
- `XlNetwork` gains `failedIds()` and `learnFailed(...)`. `XlCluster`'s implementation is a no-op for `learnFailed`
  because all in-process replicas already share one `View` with no propagation delay; `tuplo-node`'s `Network`
  delegates to `Membership.markFailed`.
- Adding recovery or rejoin later would break the "epoch only grows" assumption. That feature must switch to a
  (failure-count, incarnation) epoch and revisit this ADR.
- A crashed coordinator's in-flight grant can no longer win over its successor's decision. Exactly-once take holds
  under the documented fault model.
