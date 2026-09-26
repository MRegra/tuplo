package dev.sobatista.tuplo.node;

import java.util.HashMap;
import java.util.Map;

/** Tiny {@code --key value} command-line parser shared by the mains. */
final class Args {
    private final Map<String, String> values = new HashMap<>();

    static Args parse(String[] argv) {
        var a = new Args();
        for (int i = 0; i < argv.length - 1; i++) {
            if (argv[i].startsWith("--")) a.values.put(argv[i].substring(2), argv[i + 1]);
        }
        return a;
    }

    String get(String key, String def) { return values.getOrDefault(key, def); }

    String require(String key) {
        String v = values.get(key);
        if (v == null) throw new IllegalArgumentException("missing required --" + key);
        return v;
    }
}
