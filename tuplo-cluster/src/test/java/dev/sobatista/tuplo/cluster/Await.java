package dev.sobatista.tuplo.cluster;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Test-only helper: deterministically wait for a thread to reach a given {@link Thread.State} instead of
 * guessing with a fixed {@code Thread.sleep}. Polls with a short interval up to a deadline and fails loudly
 * if the thread never gets there, rather than silently racing.
 */
final class Await {
    private Await() {}

    static void state(Thread t, Thread.State want, long timeoutMs) {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (t.getState() == want) return;
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("interrupted while waiting for thread state " + want);
            }
        }
        fail("thread " + t.getName() + " never reached state " + want + "; last seen " + t.getState());
    }
}
