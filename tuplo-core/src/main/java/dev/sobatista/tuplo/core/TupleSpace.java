package dev.sobatista.tuplo.core;

import java.util.Optional;

/**
 * The tuple space: a multiset of {@link Tuple}s with three operators.
 *
 * <ul>
 *   <li>{@code add} inserts a tuple (duplicates allowed).</li>
 *   <li>{@code read} returns a tuple matching a schema <em>without</em> removing it.</li>
 *   <li>{@code take} returns a matching tuple <em>and</em> removes it.</li>
 * </ul>
 *
 * <p>{@code read} and {@code take} <b>block</b> until a matching tuple exists. The {@code try*}
 * variants never block and return {@link Optional#empty()} when there is no match — useful for tests
 * and for replication layers that drive the space from an ordered command log.
 */
public interface TupleSpace {

    /** Insert a tuple. Never blocks. */
    void add(Tuple tuple);

    /** Block until a tuple matches {@code schema}, then return it without removing it. */
    Tuple read(Schema schema) throws InterruptedException;

    /** Block until a tuple matches {@code schema}, then remove and return it. */
    Tuple take(Schema schema) throws InterruptedException;

    /** Non-blocking read: the matching tuple, or empty if none matches right now. */
    Optional<Tuple> tryRead(Schema schema);

    /** Non-blocking take: the matching tuple (removed), or empty if none matches right now. */
    Optional<Tuple> tryTake(Schema schema);

    /** Current number of tuples (counting duplicates). Mainly for tests and status. */
    int size();
}
