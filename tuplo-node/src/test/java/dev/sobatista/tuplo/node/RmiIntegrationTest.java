package dev.sobatista.tuplo.node;

import dev.sobatista.tuplo.core.LocalTupleSpace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.concurrent.ThreadLocalRandom;

import static dev.sobatista.tuplo.core.Fields.parseSchema;
import static dev.sobatista.tuplo.core.Fields.parseTuple;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Starts a real RMI server and a real client in this JVM (loopback) and drives add/read/take across the
 * wire — the same path the {@code TupleSpaceServer} + {@code ScriptClient} mains use.
 */
class RmiIntegrationTest {

    @Test @Timeout(20)
    void addReadTakeOverRmi() throws Exception {
        int port = ThreadLocalRandom.current().nextInt(20000, 40000);
        Registry registry = LocateRegistry.createRegistry(port);
        var server = new TestServer("s1", new LocalTupleSpace());
        registry.rebind("s1", server);
        try {
            var client = RemoteTupleSpaceClient.connect("localhost", port, "s1");
            client.add(parseTuple("\"greeting\", \"olá\""));
            assertEquals(parseTuple("\"greeting\", \"olá\""), client.read(parseSchema("\"greeting\", \"*\"")));
            assertEquals(parseTuple("\"greeting\", \"olá\""), client.take(parseSchema("\"greeting\", \"*\"")));
            assertTrue(client.status().contains("s1"));
        } finally {
            UnicastRemoteObject.unexportObject(server, true);
            UnicastRemoteObject.unexportObject(registry, true);
        }
    }

    /** A tiny public server so RMI can export it from the test (mirrors TupleSpaceServer). */
    static final class TestServer extends UnicastRemoteObject implements RemoteTupleSpace {
        private final String name;
        private final dev.sobatista.tuplo.core.TupleSpace space;
        TestServer(String name, dev.sobatista.tuplo.core.TupleSpace space) throws java.rmi.RemoteException {
            this.name = name; this.space = space;
        }
        @Override public void add(dev.sobatista.tuplo.core.Tuple t) { space.add(t); }
        @Override public dev.sobatista.tuplo.core.Tuple read(dev.sobatista.tuplo.core.Schema s) throws InterruptedException { return space.read(s); }
        @Override public dev.sobatista.tuplo.core.Tuple take(dev.sobatista.tuplo.core.Schema s) throws InterruptedException { return space.take(s); }
        @Override public String status() { return "server " + name + ": " + space.size() + " tuple(s)"; }
    }
}
