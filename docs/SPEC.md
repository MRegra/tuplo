# Tuplo — semantics

This is a plain-English spec of what Tuplo guarantees, written from scratch (the original course PDF is not
redistributed). If the code and this doc disagree, that's a bug — file it.

## Data model
- A **tuple** is a non-empty, ordered sequence of **fields**. Fields are one of:
  - a **string** — any text (the parser writes them in quotes: `"build"`);
  - an **object** — a type name plus constructor arguments that are integers or strings: `Point(1, 2)`, `User(1, "alice")`.
- The **tuple space** is a **multiset** of tuples: duplicates are allowed and each copy counts.

## Operators
- `add(tuple)` — insert a tuple. Never blocks.
- `read(schema)` — return a tuple matching the schema **without** removing it.
- `take(schema)` — return a tuple matching the schema **and** remove it.

`read` and `take` have **blocking semantics**: if nothing matches yet, the call waits until some `add` produces a
match. There is no timeout in the base version.

## Schemas and matching
A **schema** has the same shape as a tuple, but any field may be a **wildcard**. A schema matches a tuple when:
1. they have the **same number of fields**, and
2. every schema field matches the tuple field in the same position.

Field matching:

| Schema field | Written as | Matches |
|---|---|---|
| exact string | `"build"` | the identical string |
| any string | `"*"` | any string field |
| prefix | `"job*"` | strings starting with `job` |
| suffix | `"*.log"` | strings ending with `.log` |
| exact object | `Point(1, 2)` | an object of that type with equal args |
| any of a type | `Point` | any object of type `Point` |
| any object | `null` | any object field (of any type) |

Notes:
- String wildcards never match object fields, and object wildcards never match string fields.
- A tuple (as opposed to a schema) may not contain wildcards — `add` of a wildcard is rejected.

## Determinism (why it matters for replication)
When several tuples match, Tuplo always returns the **oldest** one (insertion order). This isn't cosmetic: it's
what lets independent replicas that apply the same operations in the same order remove the *same* tuple on a
`take`, so they stay identical. See [ARCHITECTURE.md](ARCHITECTURE.md).

## Replication & fault tolerance
- **SMR variant**: every replica starts empty, receives every command in the same total order, and
  applies it deterministically. Any replica can serve any client. Losing a replica loses no tuples. Across
  processes the order comes from a sequencer (the lowest live replica); if it crashes, the next one takes over and
  first brings every survivor to the same point (see [ARCHITECTURE.md](ARCHITECTURE.md#networked-mode)).
- **XL variant**: every replica holds every tuple; `add` goes to every live replica with no global order, `read` is
  local, and `take` asks the tuple's coordinator (its creator, or the lowest survivor) for an exclusive grant.
  SMR replicas agree on the *order* of tuples; XL replicas agree on the *multiset*.
- Base fault model: a perfect failure detector, and at most one fault at a time with time to recover between
  faults. Failures are permanent (a crashed replica does not rejoin). Relaxing that (progress with only a majority)
  is a roadmap item.
- **Crash** stops a replica for good; the others notice (a failed call or the heartbeat), drop it from their view,
  and carry on. **Freeze** keeps a replica receiving messages but not processing them; **unfreeze** processes the
  backlog in order. While a replica is frozen, an XL `add` waits for it (XL waits for every live replica); an SMR
  `add` through another replica does not, unless the frozen replica is the sequencer (then ordering pauses).
- **Message delays**: a replica started with `--delay-min/--delay-max` delays every incoming client and replica
  message by a random time in that range, without reordering a sender's messages.

## Client scripts (`.tuplo`)
A script is executed top-to-bottom, synchronously. Commands:

```
add  <field1, ..., fieldn>
read <field1, ..., fieldn>
take <field1, ..., fieldn>
wait x                      # sleep x milliseconds
begin-repeat x              # repeat the block below, x times (no nesting)
end-repeat
```
Blank lines and `#` comments are ignored.

## Traceability: the original statement → tests

Every requirement of the original project statement (DIDA-TUPLE, IST 2018-19; kept locally, not redistributed),
paraphrased, with the test that proves it. Test names are `Class#method`; `MultiProcess…` tests fork three replica
JVMs and run for **both** variants.

| # | Requirement (statement §) | Where | Proven by | Status |
|---|---|---|---|---|
| R1 | Tuple = ordered sequence of fields; the space is a **multiset** (§1) | `Tuple`, `LocalTupleSpace` | `LocalTupleSpaceTest#multisetKeepsDuplicates`, `TupleSpaceContractTest#keepsDuplicates` | ✅ |
| R2 | `add` inserts a tuple (§1) | all spaces | `TupleSpaceContractTest#addThenReadDoesNotRemove` (local, SMR, XL) | ✅ |
| R3 | `read` returns a match **without** removing it (§1) | all spaces | `LocalTupleSpaceTest#addReadDoesNotRemove`, `TupleSpaceContractTest#addThenReadDoesNotRemove`, `MultiProcessClusterTest#replicasInSeparateProcessesConverge` | ✅ |
| R4 | `take` returns a match **and** removes it, exactly once (§1) | all spaces | `TupleSpaceContractTest#takeRemoves`, `#everyTupleTakenExactlyOnceUnderConcurrency`, `SmrReplicaTest#takeRemovesEverywhereAndOnlyOnce`, `XlReplicaTest#concurrentTakesAcrossReplicasNeverDoubleTake`, `#takeRetriesTheSameTupleAgainstANewCoordinatorInsteadOfAbandoningIt`, `MultiProcessClusterTest#xlCoordinatorCrashDuringConcurrentTakesNeverDoubleGrants` | ✅ |
| R5 | `read`/`take` **block** until a match exists (§2.2.1) | all spaces | `LocalTupleSpaceTest#takeBlocksUntilAdd`, `TupleSpaceContractTest#takeBlocksUntilAdd`, `MultiProcessClusterTest#replicasInSeparateProcessesConverge` (take on one process released by an add on another) | ✅ |
| R6 | String fields; object fields written as constructor calls with int/string args (§2.2.1) | `Fields`, `Field.Obj` | `MatchingTest#concreteObjectMatchesOnTypeAndArgs`, `#objectWithStringArgs`, `ScriptTest#parsesObjectAndWildcardFields` | ✅ |
| R7 | Object wildcards: exact object, any instance of a type, any object (`null`) (§2.2.1) | `Field` | `MatchingTest#anyOfTypeMatchesAnyInstance`, `#nullMatchesAnyObjectButNotStrings`, `#mixedSchema` | ✅ |
| R8 | String wildcards: `"*"`, prefix `"abc*"`, suffix `"*abc"` (§2.2.1) | `Field` | `MatchingTest#anyStringWildcard`, `#prefixAndSuffixWildcards` | ✅ |
| R9 | Schema matches only tuples of the same arity; tuples can't hold wildcards | `Schema`, `Tuple` | `MatchingTest#arityMustMatch`, `#tuplesRejectWildcards` | ✅ |
| R10 | Set of **server processes**, any of which serves any operation (§2.1) | `ReplicaNode` | `MultiProcessClusterTest#replicasInSeparateProcessesConverge` (writes through all three processes) | ✅ |
| R11 | **Client library** API to reach the servers (§2.2) | `RemoteTupleSpaceClient` | `RmiIntegrationTest#addReadTakeOverRmi`, every `MultiProcessClusterTest` | ✅ |
| R12 | **script-client** runs a script synchronously: `add/read/take/wait/begin-repeat/end-repeat`, no nested repeat (§2.2.1) | `Script`, `ScriptClient` | `ScriptTest#runsAddsReadsAndRepeat`, `#rejectsNestedRepeat`, `MultiProcessClusterTest#delayedMessagesAndTheScriptClientStillConverge` | ✅ |
| R13 | **SMR**: replicas start empty, get every command in the same order, react deterministically; the ordering layer is ours (§2.3) | `SmrReplica`, `SequencerTotalOrder`, `RmiTotalOrder` | `SmrReplicaTest#allReplicasConvergeToIdenticalState`, `MultiProcessClusterTest#replicasInSeparateProcessesConverge[SMR]` (identical *sequences* across JVMs) | ✅ |
| R14 | **XL** after Xu & Liskov (§2.3) | `XlReplica`, `XlCluster`, `ReplicaNode` | `XlReplicaTest#*`, `MultiProcessClusterTest#*[XL]` | ✅ |
| R15 | Share the common parts so a future variant is cheap (§2.3) | `TupleSpace`, `TotalOrder`, `XlNetwork`, `ClusterControl` | `TupleSpaceContractTest` (one contract, three implementations) | ✅ |
| R16 | A failed replica loses no tuples (§2.4) | both variants | `FaultToleranceTest#smrSurvivesAReplicaCrash`, `#xlSurvivesCrashOfTheTupleCoordinator`, `MultiProcessClusterTest#crashOfAPlainReplicaLosesNothing`, `#crashOfTheSequencerOrCoordinatorLosesNothing` | ✅ |
| R17 | Perfect failure detector: survivors eventually update their **view** (§2.4) | `View`, `Membership` | `MultiProcessClusterTest#crashOfTheSequencerOrCoordinatorLosesNothing` (view shows `presumed failed [0]`), `FaultToleranceTest#xlCrashReleasesAGrantHeldByTheDeadReplica` | ✅ |
| R18 | Configurable random **message delay** per server, never reordering (§2.4.1) | `DelayingTupleSpace`, `ReplicaNode --delay-min/--delay-max` | `MultiProcessClusterTest#delayedMessagesAndTheScriptClientStillConverge` (SMR still yields identical sequences) | ✅ |
| R19 | PuppetMaster: single console, commands from the console or a script, `Wait` (§3) | `PuppetMaster` (in-process and `--nodes` networked) | `PuppetMasterTest#*`, `MultiProcessClusterTest#puppetMasterMainDrivesANetworkedClusterFromAScript` | ✅ |
| R20 | `Status`: every node prints who is present / presumed failed (§3) | `ReplicaNode#status`, `RemoteCluster#status` | `MultiProcessClusterTest#crashOfAPlainReplicaLosesNothing`, `#puppetMasterMainDrivesANetworkedClusterFromAScript` | ✅ |
| R21 | `Crash` really kills the process (§3) | `ReplicaNode#crash` | `MultiProcessClusterTest#crashOf*` (asserts the JVM exited) | ✅ |
| R22 | `Freeze`: keep receiving, stop processing; `Unfreeze`: process the backlog (§3) | `Faults`, `SmrReplica#freeze`, `ReplicaNode#freeze` | `FaultToleranceTest#smrFreezeThenUnfreezeLosesNothing`, `MultiProcessClusterTest#frozenReplicaDefersThenCatchesUp` | ✅ |
| R23 | `Server`/`Client` commands **launch** processes through a per-machine **PCS** on port 10000 (§3) | — | — | ⏳ roadmap: replicas and clients are started by hand (or by the test fixture) and the PuppetMaster connects to them |
| R24 | PuppetMaster commands run **asynchronously** except `Wait`; step-by-step script mode (§3) | — | — | ⏳ roadmap: commands currently run one after another |
| R25 | Advanced: SMR and XL making progress with only a **majority** (no perfect failure detector) (§4) | — | — | ⏳ roadmap (optional in the statement) |
| R26 | Performance evaluation: workloads where each variant wins (§5) | — | — | ⏳ roadmap: benchmarks |
| R27 | C# / .NET Remoting, LaTeX report, dates, grading (§1, §6–§11) | — | — | n/a — Java/RMI is the deliberate substitute (see ARCHITECTURE.md); course logistics don't apply |

## Coverage

`mvn verify` runs JaCoCo on every module and **fails the build** if line coverage drops below the floor in the
module's `pom.xml` (`tuplo.coverage.line`): **100% for `tuplo-core`**, and **99% for `tuplo-cluster`**, which is
100% minus the exclusion list below. `tuplo-node` is reported but not gated: its networked code runs mostly inside
the forked replica JVMs of `MultiProcessClusterTest`, which JaCoCo's in-process agent doesn't see.

Exclusion list (lines that can't be reached through the public API, so no test covers them):

| Location | Why it's unreachable |
|---|---|
| `SmrReplica#await`, the `catch (ExecutionException e)` rethrow (2 lines) | the result futures are only ever `complete`d, never `completeExceptionally`; the branch is a defensive guard |
