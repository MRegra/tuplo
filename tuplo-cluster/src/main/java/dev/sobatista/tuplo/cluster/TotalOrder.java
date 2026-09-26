package dev.sobatista.tuplo.cluster;

/**
 * The total-order broadcast layer: the one piece SMR cannot live without.
 *
 * <p>State-machine replication only works if every replica sees every command in the same order. That
 * is exactly what this abstraction promises: {@link #submit(Command)} hands a command to the ordering
 * layer, and every registered replica later gets {@link Deliverer#deliver(long, Command)} called with a
 * strictly increasing sequence number, in the same order everywhere.
 *
 * <p>{@link SequencerTotalOrder} is the simplest correct implementation (one node stamps the numbers).
 * Swapping it for a leaderless/consensus-based ordering is how the XL variant and the "majority only"
 * advanced mode plug in — the replicas above it don't change.
 */
public interface TotalOrder {

    /** A replica's hook for receiving ordered commands. */
    interface Deliverer {
        void deliver(long seq, Command command);
    }

    /** Register a replica to receive ordered deliveries. Call before submitting anything. */
    void register(Deliverer deliverer);

    /** Submit a command to be ordered and delivered to every registered replica. */
    void submit(Command command);
}
