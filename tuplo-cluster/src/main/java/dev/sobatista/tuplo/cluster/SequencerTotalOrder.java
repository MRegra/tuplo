package dev.sobatista.tuplo.cluster;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Sequencer-based total order: one place stamps every command with the next sequence number and fans it
 * out to all replicas under a single lock, so the global order is unambiguous.
 *
 * <p>This is the textbook "sequencer" approach to total-order broadcast. It is simple and correct while
 * one fault happens at a time (the project's basic fault model). Its weakness — the sequencer is a single
 * point of ordering — is precisely what the advanced, consensus-based ordering removes; because the
 * replicas depend only on {@link TotalOrder}, that swap doesn't touch them.
 *
 * <p>This in-process implementation delivers synchronously on the submitting thread while holding the
 * lock. That is enough to model SMR and to test convergence deterministically; a networked build moves
 * the fan-out onto the transport (see {@code tuplo-node}).
 */
public final class SequencerTotalOrder implements TotalOrder {

    private final List<Deliverer> replicas = new CopyOnWriteArrayList<>();
    private final Object seqLock = new Object();
    private long nextSeq = 0;

    @Override
    public void register(Deliverer deliverer) {
        replicas.add(deliverer);
    }

    @Override
    public void submit(Command command) {
        synchronized (seqLock) {                 // one command is fully ordered+delivered before the next
            long seq = nextSeq++;
            for (Deliverer r : replicas) {
                r.deliver(seq, command);
            }
        }
    }
}
