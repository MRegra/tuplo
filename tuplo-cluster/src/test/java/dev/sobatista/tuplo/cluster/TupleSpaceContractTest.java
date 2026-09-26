package dev.sobatista.tuplo.cluster;

import dev.sobatista.tuplo.core.LocalTupleSpace;
import dev.sobatista.tuplo.core.Tuple;
import dev.sobatista.tuplo.core.TupleSpace;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static dev.sobatista.tuplo.core.Fields.parseSchema;
import static dev.sobatista.tuplo.core.Fields.parseTuple;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The tuple-space contract every implementation must honour, run against all three: the local space, an SMR replica and
 * an XL replica. If a variant passes these, it behaves like a tuple space; the SMR- and XL-specific tests then check the
 * distributed guarantees on top.
 */
class TupleSpaceContractTest {

    static Stream<Arguments> spaces() {
        return Stream.of(
                Arguments.of(Named.of("local", (Supplier<TupleSpace>) LocalTupleSpace::new)),
                Arguments.of(Named.of("smr", (Supplier<TupleSpace>) () -> new Cluster(3).replica(0))),
                Arguments.of(Named.of("xl", (Supplier<TupleSpace>) () -> new XlCluster(3).replica(0))));
    }

    @ParameterizedTest @MethodSource("spaces") @Timeout(15)
    void addThenReadDoesNotRemove(Supplier<TupleSpace> factory) throws Exception {
        var s = factory.get();
        s.add(parseTuple("\"a\", \"1\""));
        assertEquals(parseTuple("\"a\", \"1\""), s.read(parseSchema("\"a\", \"*\"")));
        assertEquals(parseTuple("\"a\", \"1\""), s.read(parseSchema("\"a\", \"*\"")), "read is repeatable");
    }

    @ParameterizedTest @MethodSource("spaces") @Timeout(15)
    void takeRemoves(Supplier<TupleSpace> factory) throws Exception {
        var s = factory.get();
        s.add(parseTuple("\"gone\""));
        assertEquals(parseTuple("\"gone\""), s.take(parseSchema("\"*\"")));
        assertTrue(s.tryTake(parseSchema("\"*\"")).isEmpty(), "second take finds nothing");
    }

    @ParameterizedTest @MethodSource("spaces") @Timeout(15)
    void wildcardsMatchAcrossKinds(Supplier<TupleSpace> factory) throws Exception {
        var s = factory.get();
        s.add(parseTuple("\"job_42\", Config(1, \"x\")"));
        assertNotNull(s.read(parseSchema("\"job*\", null")));            // prefix string + any object
        assertNotNull(s.read(parseSchema("\"*42\", Config")));          // suffix string + any-of-type
    }

    @ParameterizedTest @MethodSource("spaces") @Timeout(15)
    void keepsDuplicates(Supplier<TupleSpace> factory) throws Exception {
        var s = factory.get();
        s.add(parseTuple("\"x\""));
        s.add(parseTuple("\"x\""));
        s.take(parseSchema("\"x\""));
        assertTrue(s.tryTake(parseSchema("\"x\"")).isPresent(), "the multiset kept both copies");
    }

    @ParameterizedTest @MethodSource("spaces") @Timeout(15)
    void takeBlocksUntilAdd(Supplier<TupleSpace> factory) throws Exception {
        var s = factory.get();
        var got = new AtomicReference<Tuple>();
        var started = new CountDownLatch(1);
        var t = new Thread(() -> {
            try { started.countDown(); got.set(s.take(parseSchema("\"ready\""))); } catch (InterruptedException ignored) {}
        });
        t.start();
        started.await();
        Thread.sleep(150);
        assertNull(got.get(), "take parks with no match");
        s.add(parseTuple("\"ready\""));
        t.join(4000);
        assertEquals(parseTuple("\"ready\""), got.get());
    }

    @ParameterizedTest @MethodSource("spaces") @Timeout(25)
    void everyTupleTakenExactlyOnceUnderConcurrency(Supplier<TupleSpace> factory) throws Exception {
        var s = factory.get();
        int n = 40;
        var seen = new ConcurrentHashMap<String, Boolean>();
        var done = new CountDownLatch(n);
        for (int i = 0; i < n; i++) {
            new Thread(() -> {
                try { seen.put(s.take(parseSchema("\"item\", \"*\"")).toString(), Boolean.TRUE); }
                catch (InterruptedException ignored) {} finally { done.countDown(); }
            }).start();
        }
        for (int i = 0; i < n; i++) s.add(parseTuple("\"item\", \"" + i + "\""));
        assertTrue(done.await(20, TimeUnit.SECONDS), "all takers finished");
        assertEquals(n, seen.size(), "no tuple was handed out twice");
    }
}
