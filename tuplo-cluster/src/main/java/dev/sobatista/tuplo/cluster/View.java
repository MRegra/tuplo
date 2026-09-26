package dev.sobatista.tuplo.cluster;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * The set of replicas currently believed to be alive — the "view".
 *
 * <p>Both variants need this. The project assumes a <em>perfect failure detector</em>: when a replica crashes, every
 * surviving node eventually learns and updates its view. The XL variant in particular "waits for replies from all
 * nodes that are active", so "active" has to be an agreed, changing set — that's this. In-process, all replicas share
 * one {@code View}, which is a faithful stand-in for a perfect detector that has already converged.
 */
public final class View {

    private final Set<Integer> active = new CopyOnWriteArraySet<>();

    public View(int replicaCount) {
        for (int i = 0; i < replicaCount; i++) active.add(i);
    }

    public boolean isActive(int id) { return active.contains(id); }

    /** Remove a replica from the view (it crashed). Idempotent. */
    public void fail(int id) { active.remove(id); }

    /** Bring a replica back (recovered). Idempotent. */
    public void recover(int id) { active.add(id); }

    public Set<Integer> active() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(active));
    }

    public int size() { return active.size(); }

    @Override public String toString() { return "view" + new LinkedHashSet<>(active); }
}
