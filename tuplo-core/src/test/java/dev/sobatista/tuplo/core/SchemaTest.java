package dev.sobatista.tuplo.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SchemaTest {

    @Test void rejectsEmptyFieldList() {
        assertThrows(IllegalArgumentException.class, () -> new Schema(List.of()));
    }

    @Test void ofFactoryBuildsFromVarargs() {
        var s = Schema.of(new Field.AnyStr(), new Field.Str("b"));
        assertEquals(2, s.arity());
    }

    @Test void toStringRendersAngleBracketsAndCommas() {
        var s = Schema.of(new Field.AnyStr(), new Field.AnyOfType("Point"));
        assertEquals("<\"*\", Point>", s.toString());
    }

    @Test void toStringWithASingleField() {
        assertEquals("<\"*\">", Schema.of(new Field.AnyStr()).toString());
    }

    @Test void arityMismatchNeverMatches() {
        assertFalse(Schema.of(new Field.AnyStr()).matches(Tuple.of(new Field.Str("a"), new Field.Str("b"))));
    }
}
