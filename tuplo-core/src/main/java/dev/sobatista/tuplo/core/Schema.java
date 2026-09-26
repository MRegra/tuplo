package dev.sobatista.tuplo.core;

import java.io.Serializable;
import java.util.List;

/**
 * A schema (a "template" in Linda terms): a fixed-length sequence of {@link Field}s where any field
 * may be concrete or a wildcard. Used by {@code read} and {@code take} to select a tuple.
 *
 * <p>A schema matches a tuple when they have the same arity and every schema field matches the tuple
 * field in the same position (see {@link Field#matches(Field)}).
 */
public record Schema(List<Field> fields) implements Serializable {

    public Schema {
        fields = List.copyOf(fields);
        if (fields.isEmpty()) throw new IllegalArgumentException("a schema must have at least one field");
    }

    public static Schema of(Field... fields) { return new Schema(List.of(fields)); }

    public int arity() { return fields.size(); }

    /** True when this schema matches the given tuple. */
    public boolean matches(Tuple tuple) {
        if (tuple.arity() != arity()) return false;
        for (int i = 0; i < fields.size(); i++) {
            if (!fields.get(i).matches(tuple.fields().get(i))) return false;
        }
        return true;
    }

    @Override public String toString() {
        var sb = new StringBuilder("<");
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(fields.get(i));
        }
        return sb.append('>').toString();
    }
}
