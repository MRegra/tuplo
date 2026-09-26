package dev.sobatista.tuplo.node;

import java.nio.file.Path;

/**
 * The script-client: connects to a Tuplo server and runs a script file against it.
 *
 * <pre>{@code
 *   mvn -q -pl tuplo-node exec:java -Dexec.mainClass=dev.sobatista.tuplo.node.ScriptClient \
 *       -Dexec.args="--host localhost --port 1099 --name s1 --script examples/producer.tuplo"
 * }</pre>
 */
public final class ScriptClient {

    public static void main(String[] args) throws Exception {
        var a = Args.parse(args);
        String host = a.get("host", "localhost");
        int port = Integer.parseInt(a.get("port", "1099"));
        String name = a.get("name", "s1");
        Path script = Path.of(a.require("script"));

        var client = RemoteTupleSpaceClient.connect(host, port, name);
        System.out.println("connected: " + client.status());
        Script.fromFile(script).run(client);
        System.out.println("script done: " + client.status());
    }
}
