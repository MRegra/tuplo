package dev.sobatista.tuplo.node;

import dev.sobatista.tuplo.cluster.Cluster;
import dev.sobatista.tuplo.cluster.XlCluster;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;

import static dev.sobatista.tuplo.core.Fields.parseSchema;
import static org.junit.jupiter.api.Assertions.*;

/** Drives both variants through the PuppetMaster command language, including fault injection. */
class PuppetMasterTest {

    @Test @Timeout(15)
    void freezeThenUnfreezeOnSmr() throws Exception {
        var cluster = new Cluster(3);
        new PuppetMaster(cluster).run(List.of(
                "# freeze replica 2, write on 0, then thaw",
                "freeze 2",
                "status",
                "unfreeze 2"));
        // after unfreeze the cluster is consistent again
        cluster.replica(0).add(dev.sobatista.tuplo.core.Fields.parseTuple("\"x\""));
        assertEquals(1, cluster.replica(2).size());
    }

    @Test @Timeout(15)
    void crashOnXlKeepsDataTakeable() throws Exception {
        var cluster = new XlCluster(3);
        cluster.replica(1).add(dev.sobatista.tuplo.core.Fields.parseTuple("\"payload\""));
        new PuppetMaster(cluster).run(List.of("crash 1", "status"));
        // replica 1 (the origin/coordinator) is down, but the tuple survives on the others
        assertEquals(dev.sobatista.tuplo.core.Fields.parseTuple("\"payload\""),
                cluster.replica(0).take(parseSchema("\"payload\"")));
    }

    @Test @Timeout(15)
    void clientScriptRunsAgainstAReplica() throws Exception {
        var cluster = new Cluster(2);
        var script = java.nio.file.Files.createTempFile("pm", ".tuplo");
        java.nio.file.Files.writeString(script, "add <\"hi\">\nread <\"hi\">\n");
        try {
            new PuppetMaster(cluster).run(List.of("client 0 " + script, "status"));
            assertEquals(1, cluster.replica(1).size(), "the add replicated to the other node");
        } finally {
            java.nio.file.Files.deleteIfExists(script);
        }
    }

    @Test
    void rejectsBadReplicaIndex() {
        var cluster = new Cluster(2);
        assertThrows(IllegalArgumentException.class, () -> new PuppetMaster(cluster).run(List.of("crash 9")));
    }
}
