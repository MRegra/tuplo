package dev.sobatista.tuplo.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TupleTest {

    @Test void rejectsEmptyFieldList() {
        assertThrows(IllegalArgumentException.class, () -> new Tuple(List.of()));
    }

    @Test void rejectsAWildcardField() {
        assertThrows(IllegalArgumentException.class, () -> new Tuple(List.of(new Field.AnyStr())));
    }

    @Test void ofFactoryBuildsFromVarargs() {
        var t = Tuple.of(new Field.Str("a"), new Field.Str("b"));
        assertEquals(2, t.arity());
        assertEquals(List.of(new Field.Str("a"), new Field.Str("b")), t.fields());
    }

    @Test void toStringRendersAngleBracketsAndCommas() {
        var t = Tuple.of(new Field.Str("a"), new Field.Obj("Point", List.of(1, 2)));
        assertEquals("<\"a\", Point(1, 2)>", t.toString());
    }

    @Test void toStringWithASingleField() {
        assertEquals("<\"solo\">", Tuple.of(new Field.Str("solo")).toString());
    }

    @Test void equalTuplesAreEqualAndHashConsistently() {
        var a = Tuple.of(new Field.Str("x"));
        var b = Tuple.of(new Field.Str("x"));
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }
}
