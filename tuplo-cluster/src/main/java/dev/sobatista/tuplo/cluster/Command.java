package dev.sobatista.tuplo.cluster;

import dev.sobatista.tuplo.core.Schema;
import dev.sobatista.tuplo.core.Tuple;

import java.io.Serializable;

/**
 * A tuple-space operation as it travels through the replication layer.
 *
 * <p>In state-machine replication every replica must apply the <em>same</em> operations in the
 * <em>same</em> order, so operations are values (not method calls): they are ordered by the total-order
 * layer and then replayed deterministically by each {@link SmrReplica}. Each command carries who issued
 * it ({@code originReplica}) and a per-origin request id, so the origin replica knows which waiting
 * client to answer once the command's effect is known.
 */
public sealed interface Command extends Serializable permits Command.Add, Command.Take, Command.Read {

    int originReplica();
    long reqId();

    record Add(int originReplica, long reqId, Tuple tuple) implements Command {}
    record Take(int originReplica, long reqId, Schema schema) implements Command {}
    record Read(int originReplica, long reqId, Schema schema) implements Command {}
}
