package dev.sobatista.tuplo.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Direct behaviour of the {@link Field} variants: validation, equals/matches semantics and rendering.
 * {@link MatchingTest} covers matching through the parser; these tests exercise the record constructors
 * and {@code toString}/{@code isConcrete} directly, including the wildcard variants a tuple can never
 * hold ({@link Field.PrefixStr}, {@link Field.SuffixStr}) which the parser never routes through a
 * concreteness check.
 */
class FieldTest {

    @Test void strRejectsNullValue() {
        assertThrows(IllegalArgumentException.class, () -> new Field.Str(null));
    }

    @Test void strToStringQuotesValue() {
        assertEquals("\"hello\"", new Field.Str("hello").toString());
    }

    @Test void strIsConcreteAndMatchesOnlyEqualValue() {
        var f = new Field.Str("a");
        assertTrue(f.isConcrete());
        assertTrue(f.matches(new Field.Str("a")));
        assertFalse(f.matches(new Field.Str("b")));
    }

    @Test void objRejectsBlankOrNullType() {
        assertThrows(IllegalArgumentException.class, () -> new Field.Obj("", List.of()));
        assertThrows(IllegalArgumentException.class, () -> new Field.Obj("   ", List.of()));
        assertThrows(IllegalArgumentException.class, () -> new Field.Obj(null, List.of()));
    }

    @Test void objRejectsArgsThatAreNotIntegerOrString() {
        var ex = assertThrows(IllegalArgumentException.class, () -> new Field.Obj("Point", List.of(1.5)));
        assertTrue(ex.getMessage().contains("must be Integer or String"), ex.getMessage());
    }

    @Test void objToStringFormatsMixedArgsAndQuotesStrings() {
        var o = new Field.Obj("User", List.of(1, "alice"));
        assertEquals("User(1, \"alice\")", o.toString());
    }

    @Test void objToStringWithNoArgs() {
        assertEquals("Empty()", new Field.Obj("Empty", List.of()).toString());
    }

    @Test void objIsConcreteAndMatchesOnlyEqualObject() {
        var o = new Field.Obj("Point", List.of(1, 2));
        assertTrue(o.isConcrete());
        assertTrue(o.matches(new Field.Obj("Point", List.of(1, 2))));
        assertFalse(o.matches(new Field.Obj("Point", List.of(1, 3))));
    }

    @Test void prefixStrRejectsNullPrefix() {
        assertThrows(IllegalArgumentException.class, () -> new Field.PrefixStr(null));
    }

    @Test void prefixStrIsWildcardAndRendersWithTrailingStar() {
        var p = new Field.PrefixStr("ab");
        assertFalse(p.isConcrete(), "a prefix wildcard is never concrete, so a tuple must reject it");
        assertEquals("\"ab*\"", p.toString());
        assertTrue(p.matches(new Field.Str("abcdef")));
    }

    @Test void suffixStrRejectsNullSuffix() {
        assertThrows(IllegalArgumentException.class, () -> new Field.SuffixStr(null));
    }

    @Test void suffixStrIsWildcardAndRendersWithLeadingStar() {
        var s = new Field.SuffixStr("ab");
        assertFalse(s.isConcrete(), "a suffix wildcard is never concrete, so a tuple must reject it");
        assertEquals("\"*ab\"", s.toString());
        assertTrue(s.matches(new Field.Str("xyzab")));
    }
}
