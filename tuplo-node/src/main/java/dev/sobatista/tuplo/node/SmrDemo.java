package dev.sobatista.tuplo.node;

import dev.sobatista.tuplo.cluster.Cluster;

import static dev.sobatista.tuplo.core.Fields.parseSchema;
import static dev.sobatista.tuplo.core.Fields.parseTuple;

/**
 * A no-RMI demo of the distributed part: a 3-replica SMR cluster in one process. It writes on one replica,
 * reads/takes on another, and shows every replica converges to the same state.
 *
 * <pre>{@code mvn -q -pl tuplo-node exec:java -Dexec.mainClass=dev.sobatista.tuplo.node.SmrDemo}</pre>
 */
public final class SmrDemo {

    public static void main(String[] args) throws Exception {
        var c = new Cluster(3);
        System.out.println("3-replica SMR cluster up.\n");

        c.replica(0).add(parseTuple("\"task\", \"build\""));
        c.replica(1).add(parseTuple("\"task\", \"test\""));
        System.out.println("added two tasks on replicas 0 and 1");
        System.out.println("replica 2 reads: " + c.replica(2).read(parseSchema("\"task\", \"*\"")));

        System.out.println("\nreplica 2 takes a task: " + c.replica(2).take(parseSchema("\"task\", \"*\"")));
        System.out.printf("sizes after take -> r0=%d r1=%d r2=%d (all equal = replicas agree)%n",
                c.replica(0).size(), c.replica(1).size(), c.replica(2).size());

        // blocking take that resolves when a producer adds later, on a different replica
        var consumer = new Thread(() -> {
            try {
                System.out.println("\nreplica 0 take(<\"ready\">) is blocking, waiting for a match...");
                System.out.println("replica 0 got: " + c.replica(0).take(parseSchema("\"ready\"")));
            } catch (InterruptedException ignored) {}
        });
        consumer.start();
        Thread.sleep(300);
        System.out.println("replica 2 adds <\"ready\"> -> unblocks the take on replica 0");
        c.replica(2).add(parseTuple("\"ready\""));
        consumer.join();
        System.out.printf("final sizes -> r0=%d r1=%d r2=%d%n", c.replica(0).size(), c.replica(1).size(), c.replica(2).size());
    }
}
