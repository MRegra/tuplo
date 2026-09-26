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
- **SMR variant** (shipped): every replica starts empty, receives every command in the same total order, and
  applies it deterministically. Any replica can serve any client. Losing a replica loses no tuples.
- Base fault model: a perfect failure detector, and at most one fault at a time with time to recover between
  faults. Relaxing that (progress with only a majority) is a roadmap item.

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
