package dev.sobatista.tuplo.cluster;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.Set;

import static dev.sobatista.tuplo.core.Fields.parseTuple;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression (F-0603, PR #6 second adversary round): a grant copy replicated by a coordinator that crashed right
 * after sending it must not be able to overwrite a live grant made by its successor.
 *
 * <p>Before the epoch-stamped fix, {@link XlReplica#receiveGrant} did unconditional last-writer-wins: replica 0
 * (coordinator of tuple X) crashes while its copy of {@code grant(X, reqA)} to replica 1 is still in flight. Replica
 * 1 becomes the new coordinator and grants X to a different, live taker (reqB). The late copy from 0 then lands and
 * overwrites replica 1's record with reqA, so taker A's retry (see {@link XlReplica#take}'s cross-coordinator retry)
 * is wrongly granted the same tuple a second time. This test adopts the adversary's exact interleaving, updated only
 * for the new {@code grant}/{@code receiveGrant} signatures (epoch + known-failed set).
 */
class XlStaleGrantAdversaryTest {

    @Test
    @Timeout(10)
    void aLateReplicatedGrantFromACrashedCoordinatorMustNotOverwriteTheSuccessorsGrant() throws Exception {
        XlReplica[] r = new XlReplica[3];
        var network = new XlNetwork() {                          // replica 0 (origin/coordinator of X) has crashed
            @Override public List<XlPeer> activePeers() { return List.of(r[1], r[2]); }
            @Override public boolean isActive(int id) { return id != 0; }
            @Override public Set<Integer> failedIds() { return Set.of(0); }
            @Override public void learnFailed(Set<Integer> ids) { }
            @Override public XlPeer coordinatorOf(XlReplica.TupleId t) { return r[1]; }
        };
        r[1] = new XlReplica(1, network);
        r[2] = new XlReplica(2, network);
        var x = new XlReplica.TupleId(0, 0);
        r[1].receiveStore(x, parseTuple("\"t\", \"k0\""));
        r[2].receiveStore(x, parseTuple("\"t\", \"k0\""));
        var reqA = new XlReplica.ReqId(2, 7);
        var reqB = new XlReplica.ReqId(2, 8);

        assertTrue(r[1].grant(x, reqB, Set.of()), "new coordinator (epoch 1: replica 0 failed) grants X to live taker B");
        // 0's in-flight copy of its own (epoch 0, before anyone had failed) grant to A lands late.
        r[1].receiveGrant(x, reqA, new XlReplica.GrantStamp(0, Set.of()));
        assertFalse(r[1].grant(x, reqA, Set.of()), "A's retry must be refused: X is already granted to live taker B");
    }
}
