package dev.sobatista.tuplo.cluster;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

import static dev.sobatista.tuplo.core.Fields.parseTuple;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deterministic interleavings for the epoch-stamped grant rule introduced to close the F-0603 second adversary
 * finding (see {@link XlStaleGrantAdversaryTest} for the original reported interleaving). Each test drives {@link
 * XlReplica#grant} and {@link XlReplica#receiveGrant} directly so the ordering is exact, instead of racing threads.
 */
class XlGrantAuthorityTest {

    /** A single-replica network: {@code self} is always the coordinator and every peer is always active. */
    private static XlNetwork trivialNetworkFor(XlReplica[] self) {
        return new XlNetwork() {
            @Override public List<XlPeer> activePeers() { return List.of(self[0]); }
            @Override public XlPeer coordinatorOf(XlReplica.TupleId t) { return self[0]; }
            @Override public boolean isActive(int id) { return true; }
            @Override public Set<Integer> failedIds() { return Set.of(); }
            @Override public void learnFailed(Set<Integer> ids) { }
        };
    }

    @Test
    @Timeout(10)
    void twoSuccessiveCrashesStillPreventADoubleGrant() throws Exception {
        // Narrative: r2 (this replica) kept taker A's grant at epoch 0 (the tuple's original coordinator, replica 0).
        // Replica 1 then became coordinator, learned 0 had failed, and replicated its own grant to taker B at epoch 1,
        // overwriting A's record here. Replica 1 has since crashed too; A retries against r2 under the same request.
        XlReplica[] r = new XlReplica[1];
        r[0] = new XlReplica(2, trivialNetworkFor(r));
        var x = new XlReplica.TupleId(0, 0);
        r[0].receiveStore(x, parseTuple("\"t\", \"k0\""));
        var reqA = new XlReplica.ReqId(3, 1);
        var reqB = new XlReplica.ReqId(4, 1);

        r[0].receiveGrant(x, reqA, new XlReplica.GrantStamp(0, Set.of()));
        r[0].receiveGrant(x, reqB, new XlReplica.GrantStamp(1, Set.of(0)));

        assertFalse(r[0].grant(x, reqA, Set.of()), "A's retry must be refused: r2 already holds B at the higher epoch");
    }

    @Test
    @Timeout(10)
    void aLowerEpochCopyNeverOverwritesTheRecordedHolder() throws Exception {
        XlReplica[] r = new XlReplica[1];
        r[0] = new XlReplica(0, trivialNetworkFor(r));
        var x = new XlReplica.TupleId(0, 0);
        r[0].receiveStore(x, parseTuple("\"t\", \"k0\""));
        var reqHigh = new XlReplica.ReqId(1, 1);
        var reqLow = new XlReplica.ReqId(2, 1);

        r[0].receiveGrant(x, reqHigh, new XlReplica.GrantStamp(5, Set.of()));
        r[0].receiveGrant(x, reqLow, new XlReplica.GrantStamp(2, Set.of()));   // must be dropped: lower epoch

        assertFalse(r[0].grant(x, reqLow, Set.of()), "the lower-epoch copy must not have become the recorded holder");
        assertTrue(r[0].grant(x, reqHigh, Set.of()), "the higher-epoch holder is still reqHigh (same request re-grants trivially)");
    }

    @Test
    @Timeout(10)
    void aSameEpochTieKeepsTheFirstWriter() throws Exception {
        XlReplica[] r = new XlReplica[1];
        r[0] = new XlReplica(0, trivialNetworkFor(r));
        var x = new XlReplica.TupleId(0, 0);
        r[0].receiveStore(x, parseTuple("\"t\", \"k0\""));
        var reqFirst = new XlReplica.ReqId(1, 1);
        var reqSecond = new XlReplica.ReqId(2, 1);

        r[0].receiveGrant(x, reqFirst, new XlReplica.GrantStamp(3, Set.of()));
        r[0].receiveGrant(x, reqSecond, new XlReplica.GrantStamp(3, Set.of()));   // same epoch: first writer must win

        assertTrue(r[0].grant(x, reqFirst, Set.of()), "reqFirst remains the holder at the tied epoch");
        assertFalse(r[0].grant(x, reqSecond, Set.of()), "reqSecond must not have displaced the first writer");
    }

    /** A network whose failed set is mutable and shared, like {@link Membership}'s view in a real cluster. */
    private static final class MutableNetwork implements XlNetwork {
        private final XlPeer[] peers;                             // filled in lazily, read only when a method is called
        private final Set<Integer> failed = new CopyOnWriteArraySet<>();
        MutableNetwork(XlPeer[] peers) { this.peers = peers; }
        @Override public List<XlPeer> activePeers() {
            return java.util.Arrays.stream(peers).filter(p -> p != null && !failed.contains(p.id())).toList();
        }
        @Override public XlPeer coordinatorOf(XlReplica.TupleId t) {
            var active = java.util.Arrays.stream(peers).filter(p -> p != null).map(XlPeer::id)
                    .filter(id -> !failed.contains(id)).toList();
            return XlNetwork.coordinatorId(t, active).map(id -> peers[id]).orElse(null);
        }
        @Override public boolean isActive(int id) { return !failed.contains(id); }
        @Override public Set<Integer> failedIds() { return Set.copyOf(failed); }
        @Override public void learnFailed(Set<Integer> ids) { failed.addAll(ids); }
    }

    /** A no-op stand-in for a replica that has actually crashed: only its id ever gets looked at. */
    private static XlPeer deadPeer(int id) {
        return new XlPeer() {
            @Override public int id() { return id; }
            @Override public void receiveStore(XlReplica.TupleId tid, dev.sobatista.tuplo.core.Tuple tuple) { }
            @Override public void receiveRemove(XlReplica.TupleId tid) { }
            @Override public boolean grant(XlReplica.TupleId tid, XlReplica.ReqId req, Set<Integer> knownFailed) { return false; }
            @Override public void receiveGrant(XlReplica.TupleId tid, XlReplica.ReqId req, XlReplica.GrantStamp stamp) { }
        };
    }

    @Test
    @Timeout(10)
    void grantMergesTheTakersKnownFailedSetBeforeCheckingCoordinatorAuthority() throws Exception {
        // Tuple X originates at replica 0. Replica 0 has actually crashed, so replica 1 is now the true coordinator,
        // but replica 1's own failure detector has not caught up yet: from its own view, replica 0 still looks alive,
        // so coordinatorOf(X) would still resolve to replica 0 and replica 1 would (wrongly) refuse the grant. The
        // taker piggybacks what *it* already knows — replica 0 is dead — and that must be merged in first.
        XlPeer[] peers = new XlPeer[2];
        var network = new MutableNetwork(peers);
        peers[0] = deadPeer(0);
        var r1 = new XlReplica(1, network);
        peers[1] = r1;
        var x = new XlReplica.TupleId(0, 0);
        r1.receiveStore(x, parseTuple("\"t\", \"k0\""));
        var req = new XlReplica.ReqId(2, 1);

        assertTrue(r1.grant(x, req, Set.of(0)),
                "merging the taker's knownFailed set must let replica 1 recognize itself as the new coordinator");
    }
}
