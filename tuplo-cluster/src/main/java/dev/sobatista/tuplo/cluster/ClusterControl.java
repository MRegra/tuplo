package dev.sobatista.tuplo.cluster;

import dev.sobatista.tuplo.core.TupleSpace;

/**
 * The control surface a PuppetMaster drives, implemented by both variants ({@link Cluster} for SMR and
 * {@link XlCluster} for XL). It hides which replication scheme is underneath, so the same experiment script can be run
 * against either — which is exactly how you compare them.
 */
public interface ClusterControl {
    int size();
    TupleSpace replicaSpace(int i);
    void crash(int i);
    void freeze(int i);
    void unfreeze(int i);
    String status();
}
