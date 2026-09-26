# Contributing to Tuplo

Thanks for looking! Tuplo is a teaching-first project about distributed systems, so clear code and clear
explanations both count as contributions.

## Build & test
Requires **JDK 21** and **Maven 3.9+**.

```bash
mvn test          # build + run all tests
mvn install       # build the modules into your local ~/.m2 (needed before exec:java below)
```

Run the in-process 3-replica SMR demo (no network setup):

```bash
mvn -q -pl tuplo-node exec:java -Dexec.mainClass=dev.sobatista.tuplo.node.SmrDemo
```

## How it's laid out
- `tuplo-core` — the tuple space itself: tuples, schemas, wildcard matching, the blocking `LocalTupleSpace`. Start here.
- `tuplo-cluster` — total-order broadcast and the SMR replica. The distributed algorithm.
- `tuplo-node` — RMI transport, the server, the client library and the script-client.

## Sending a change
1. Branch from `main`.
2. Keep the change small and focused. Add a test — a bug fix gets a test that fails without it.
3. Use [Conventional Commits](https://www.conventionalcommits.org/): `feat:`, `fix:`, `docs:`, `refactor:`, `test:`, `chore:`; `feat!:` or a `BREAKING CHANGE:` footer for anything that breaks the public API.
4. `mvn test` is green.
5. Open a PR describing what and why.

By contributing you agree your work is licensed under the project's [Apache-2.0](LICENSE) license.

## Style
Readable over clever. This code is also documentation — someone should be able to learn the algorithm by
reading it. Comments explain *why*, not *what*.
