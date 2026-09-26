package dev.sobatista.tuplo.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * An in-memory, thread-safe tuple space.
 *
 * <p>Tuples are held in insertion order. When several tuples match a schema, the <b>oldest</b> one is
 * chosen. That determinism is not just tidiness: it is what lets N replicas that apply the same
 * ordered stream of operations end up removing the <em>same</em> tuple on a {@code take}, which is the
 * whole point of state-machine replication (see {@code tuplo-cluster}).
 *
 * <p>Blocking {@code read}/{@code take} wait on a condition that {@code add} signals, so a consumer
 * that asks for a tuple before it exists simply parks until a producer adds a match.
 */
public final class LocalTupleSpace implements TupleSpace {

    private final List<Tuple> tuples = new ArrayList<>();
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition added = lock.newCondition();

    @Override
    public void add(Tuple tuple) {
        lock.lock();
        try {
            tuples.add(tuple);
            added.signalAll();          // wake everyone blocked in read/take; each re-checks its own schema
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Tuple read(Schema schema) throws InterruptedException {
        lock.lock();
        try {
            int i;
            while ((i = indexOfMatch(schema)) < 0) added.await();
            return tuples.get(i);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Tuple take(Schema schema) throws InterruptedException {
        lock.lock();
        try {
            int i;
            while ((i = indexOfMatch(schema)) < 0) added.await();
            return tuples.remove(i);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Optional<Tuple> tryRead(Schema schema) {
        lock.lock();
        try {
            int i = indexOfMatch(schema);
            return i < 0 ? Optional.empty() : Optional.of(tuples.get(i));
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Optional<Tuple> tryTake(Schema schema) {
        lock.lock();
        try {
            int i = indexOfMatch(schema);
            return i < 0 ? Optional.empty() : Optional.of(tuples.remove(i));
        } finally {
            lock.unlock();
        }
    }

    @Override
    public int size() {
        lock.lock();
        try {
            return tuples.size();
        } finally {
            lock.unlock();
        }
    }

    /** Index of the oldest tuple matching the schema, or -1. Caller holds the lock. */
    private int indexOfMatch(Schema schema) {
        for (int i = 0; i < tuples.size(); i++) {
            if (schema.matches(tuples.get(i))) return i;
        }
        return -1;
    }
}
