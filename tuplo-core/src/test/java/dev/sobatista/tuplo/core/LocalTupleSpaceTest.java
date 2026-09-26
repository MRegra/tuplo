package dev.sobatista.tuplo.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static dev.sobatista.tuplo.core.Fields.parseSchema;
import static dev.sobatista.tuplo.core.Fields.parseTuple;
import static org.junit.jupiter.api.Assertions.*;

class LocalTupleSpaceTest {

    @Test void addReadDoesNotRemove() throws Exception {
        var space = new LocalTupleSpace();
        space.add(parseTuple("\"a\", \"1\""));
        assertEquals(parseTuple("\"a\", \"1\""), space.read(parseSchema("\"a\", \"*\"")));
        assertEquals(1, space.size(), "read must not remove");
    }

    @Test void takeRemoves() throws Exception {
        var space = new LocalTupleSpace();
        space.add(parseTuple("\"a\""));
        assertEquals(parseTuple("\"a\""), space.take(parseSchema("\"*\"")));
        assertEquals(0, space.size());
        assertTrue(space.tryTake(parseSchema("\"*\"")).isEmpty());
    }

    @Test void multisetKeepsDuplicates() {
        var space = new LocalTupleSpace();
        space.add(parseTuple("\"x\""));
        space.add(parseTuple("\"x\""));
        assertEquals(2, space.size());
    }

    @Test void takeIsOldestFirst() throws Exception {
        var space = new LocalTupleSpace();
        space.add(parseTuple("\"job\", \"first\""));
        space.add(parseTuple("\"job\", \"second\""));
        assertEquals(parseTuple("\"job\", \"first\""), space.take(parseSchema("\"job\", \"*\"")),
                "determinism: the oldest match must be taken (so replicas agree)");
    }

    @Test @Timeout(5)
    void takeBlocksUntilAdd() throws Exception {
        var space = new LocalTupleSpace();
        var got = new AtomicReference<Tuple>();
        var started = new CountDownLatch(1);
        var consumer = new Thread(() -> {
            try {
                started.countDown();
                got.set(space.take(parseSchema("\"ready\"")));
            } catch (InterruptedException ignored) {
            }
        });
        consumer.start();
        started.await();
        Thread.sleep(100);                       // consumer is now blocked in take()
        assertNull(got.get());
        space.add(parseTuple("\"ready\""));
        consumer.join(2000);
        assertEquals(parseTuple("\"ready\""), got.get());
    }

    @Test @Timeout(10)
    void onlyOneTakerGetsEachTuple() throws Exception {
        var space = new LocalTupleSpace();
        int n = 50;
        var done = new CountDownLatch(n);
        var wins = new java.util.concurrent.atomic.AtomicInteger();
        for (int i = 0; i < n; i++) {
            new Thread(() -> {
                try {
                    space.take(parseSchema("\"item\""));
                    wins.incrementAndGet();
                } catch (InterruptedException ignored) {
                } finally {
                    done.countDown();
                }
            }).start();
        }
        for (int i = 0; i < n; i++) space.add(parseTuple("\"item\""));
        assertTrue(done.await(8, TimeUnit.SECONDS));
        assertEquals(n, wins.get(), "each added tuple is taken exactly once");
        assertEquals(0, space.size());
    }
}
