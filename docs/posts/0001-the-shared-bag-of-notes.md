---
title: "I rebuilt a college project as a distributed system. Let's start with the weird little idea at its heart."
subtitle: "What a tuple space is, why blocking reads feel like magic, and the one-line trick that keeps replicas honest. (Tuplo, part 1.)"
tags: [distributed-systems, java, concurrency, tuple-space, coordination]
status: draft
date: 2026-09-26
series: Tuplo
part: 1
---

Years ago I built a distributed system for a university class. It was in C#. It worked. Then it sat in a repo and
collected dust, like most college projects do.

I dug it back up, threw away the C#, and rebuilt it in Java from scratch. Not because Java is cooler (it isn't),
but because I wanted to actually *understand* the thing this time, and the best way I know to understand something
is to build it and then explain it to you. So here we are.

The project is a **tuple space**. If that sounds academic, hang on. It's one of the simplest, nicest ideas in
distributed computing, and almost nobody talks about it.

## The shared bag of notes

Picture a corkboard in a shared kitchen. Anyone can pin a note on it. Anyone can walk up, find a note that matches
what they're looking for, and take it down.

That's a tuple space. The notes are **tuples** — little sequences of values, like `<"task", "build", 3>`. The board
is the space. And there are exactly three things you can do:

- **add** — pin a note.
- **read** — find a matching note and copy it, leaving it on the board.
- **take** — find a matching note and rip it down.

"Matching" is where it gets fun. You don't ask for a specific note. You ask with a **pattern**:

> "Give me any note that starts with `task`, has any second word, and any number at the end."

In Tuplo that pattern is a *schema*, and it looks like this:

```
take <"task", "*", null>
```

`"*"` means any string. `null` means any object. There are prefix and suffix wildcards too (`"job*"`, `"*.log"`).
The board hands you the first note that fits. You didn't need to know who wrote it, when, or why. You just described
what you needed. That's the whole charm: processes coordinate without knowing about each other. It's a
[1980s idea called Linda](https://en.wikipedia.org/wiki/Linda_(coordination_language)), and it still slaps.

Here's the actual matching code, because I promised you real code and not hand-waving:

```java
public boolean matches(Tuple tuple) {
    if (tuple.arity() != arity()) return false;          // same number of fields, or no deal
    for (int i = 0; i < fields.size(); i++) {
        if (!fields.get(i).matches(tuple.fields().get(i))) return false;
    }
    return true;
}
```

Same length, every field matches its partner. Boring on purpose. Boring code is the good kind.

## The part that feels like magic

Now the trick that makes tuple spaces genuinely useful for coordination: **read and take block.**

If you ask for a note and there isn't one yet, you don't get `null`. You don't get an error. You just... wait.
Your call parks itself and goes to sleep. The moment someone pins a matching note, you wake up and grab it.

Think about what that gives you for free. A worker can say "give me a job" *before any job exists*, and it'll simply
hang there, patient, until work shows up. That's a work queue. You just built a work queue out of a corkboard and
the word "wait."

In Java it's a lock and a condition:

```java
public Tuple take(Schema schema) throws InterruptedException {
    lock.lock();
    try {
        int i;
        while ((i = indexOfMatch(schema)) < 0) added.await();   // no match? sleep until an add wakes me
        return tuples.remove(i);
    } finally {
        lock.unlock();
    }
}
```

`add` does the waking:

```java
public void add(Tuple tuple) {
    lock.lock();
    try {
        tuples.add(tuple);
        added.signalAll();     // "hey, new note!" — everyone who was waiting re-checks their pattern
    } finally {
        lock.unlock();
    }
}
```

That `while` loop matters, by the way. Not an `if`. When `add` shouts "new note!", *everyone* wakes up, but the
note only fits one of them. The rest have to look, shrug, and go back to sleep. Classic condition-variable stuff —
get it wrong with an `if` and you'll ship a bug that only shows up under load, at 3am, in production. Ask me how I
know.

## The one-line trick that keeps everyone honest

Here's the detail I want you to remember, because the whole distributed part of this project hangs off it.

When several notes match your pattern, which one do you get?

Tuplo always gives you the **oldest** one. First in, first matched. That's this line:

```java
for (int i = 0; i < tuples.size(); i++) {
    if (schema.matches(tuples.get(i))) return i;   // oldest match wins
}
```

Looks like a tidiness choice. It's not. It's load-bearing.

Because soon this board won't live on one machine. It'll be copied across several servers, so that if one dies,
your notes don't die with it. And those copies stay in sync by doing the exact same operations in the exact same
order. If two copies could pick *different* matching notes for the same `take`, they'd drift apart, and your
"fault-tolerant" system would quietly start lying to people.

"Always take the oldest" is the promise that makes every copy make the same choice every time. One boring `for`
loop is the difference between replicas that agree and replicas that don't. I love that.

## So what

Right now Tuplo is a single, in-memory board with add, read, take, wildcard matching, and blocking that actually
blocks. It's about 150 lines of core code and it's covered by tests I trust. You can clone it and run it today.

Next post, we make it distributed: three servers, one shared history, and the question that keeps distributed-
systems people up at night — *if everyone has to agree on the order of events, who decides the order?*

That's where it stops being a corkboard and starts being interesting.

The code is here: **[github.com/MRegra/tuplo](https://github.com/MRegra/tuplo)**. It's Apache-2.0, it's meant to
be read, and it started life as a project I barely understood at 22. Funny how that works.
