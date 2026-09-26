package dev.sobatista.tuplo.node;

import dev.sobatista.tuplo.core.Schema;
import dev.sobatista.tuplo.core.Tuple;
import dev.sobatista.tuplo.core.TupleSpace;

import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Wraps any {@link TupleSpace} and delays each incoming operation by a random time in
 * {@code [minMs, maxMs]}, as the project asks ("delay any incoming message for a random amount of time").
 *
 * <p>The delay is applied per call and does not reorder anything — a single operation just arrives late.
 * With {@code min == max == 0} it is a no-op passthrough. This is how experiments make timing bugs and
 * fault-tolerance corner cases actually show up instead of hiding behind a fast local network.
 */
public final class DelayingTupleSpace implements TupleSpace {

    private final TupleSpace delegate;
    private final int minMs;
    private final int maxMs;

    public DelayingTupleSpace(TupleSpace delegate, int minMs, int maxMs) {
        if (minMs < 0 || maxMs < minMs) throw new IllegalArgumentException("bad delay interval [" + minMs + ", " + maxMs + "]");
        this.delegate = delegate;
        this.minMs = minMs;
        this.maxMs = maxMs;
    }

    private void delay() {
        if (maxMs == 0) return;
        int ms = minMs == maxMs ? minMs : ThreadLocalRandom.current().nextInt(minMs, maxMs + 1);
        if (ms > 0) {
            try {
                Thread.sleep(ms);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override public void add(Tuple t) { delay(); delegate.add(t); }
    @Override public Tuple read(Schema s) throws InterruptedException { delay(); return delegate.read(s); }
    @Override public Tuple take(Schema s) throws InterruptedException { delay(); return delegate.take(s); }
    @Override public Optional<Tuple> tryRead(Schema s) { delay(); return delegate.tryRead(s); }
    @Override public Optional<Tuple> tryTake(Schema s) { delay(); return delegate.tryTake(s); }
    @Override public int size() { return delegate.size(); }
}
