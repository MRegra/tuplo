package dev.sobatista.tuplo.core;

import java.io.Serializable;
import java.util.List;

/**
 * An immutable tuple: a fixed-length sequence of <em>concrete</em> {@link Field}s.
 *
 * <p>The tuple space stores a multiset of these (duplicates are allowed and kept). Two tuples with
 * equal fields in the same order are equal, which is what makes a "take" of a specific tuple well
 * defined across replicas.
 */
public record Tuple(List<Field> fields) implements Serializable {

    public Tuple {
        fields = List.copyOf(fields);
        if (fields.isEmpty()) throw new IllegalArgumentException("a tuple must have at least one field");
        for (Field f : fields) {
            if (!f.isConcrete()) throw new IllegalArgumentException("a tuple field must be concrete, not a wildcard: " + f);
        }
    }

    public static Tuple of(Field... fields) { return new Tuple(List.of(fields)); }

    public int arity() { return fields.size(); }

    @Override public String toString() {
        var sb = new StringBuilder("<");
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(fields.get(i));
        }
        return sb.append('>').toString();
    }
}
