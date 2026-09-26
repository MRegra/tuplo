package dev.sobatista.tuplo.core;

import org.junit.jupiter.api.Test;

import static dev.sobatista.tuplo.core.Fields.parseSchema;
import static dev.sobatista.tuplo.core.Fields.parseTuple;
import static org.junit.jupiter.api.Assertions.*;

class MatchingTest {

    private static boolean m(String schema, String tuple) {
        return parseSchema(schema).matches(parseTuple(tuple));
    }

    @Test void exactStringMatches() {
        assertTrue(m("\"a\", \"b\"", "\"a\", \"b\""));
        assertFalse(m("\"a\", \"b\"", "\"a\", \"c\""));
    }

    @Test void arityMustMatch() {
        assertFalse(m("\"a\"", "\"a\", \"b\""));
    }

    @Test void anyStringWildcard() {
        assertTrue(m("\"*\"", "\"anything\""));
        assertFalse(m("\"*\"", "Point(1, 2)"));   // string wildcard does not match an object
    }

    @Test void prefixAndSuffixWildcards() {
        assertTrue(m("\"user*\"", "\"user_42\""));
        assertFalse(m("\"user*\"", "\"admin_42\""));
        assertTrue(m("\"*.log\"", "\"error.log\""));
        assertFalse(m("\"*.log\"", "\"error.txt\""));
    }

    @Test void concreteObjectMatchesOnTypeAndArgs() {
        assertTrue(m("Point(1, 2)", "Point(1, 2)"));
        assertFalse(m("Point(1, 2)", "Point(1, 3)"));
        assertFalse(m("Point(1, 2)", "Line(1, 2)"));
    }

    @Test void anyOfTypeMatchesAnyInstance() {
        assertTrue(m("Point", "Point(9, 9)"));
        assertFalse(m("Point", "Line(1, 2)"));
        assertFalse(m("Point", "\"a\""));         // a type wildcard does not match a string
    }

    @Test void nullMatchesAnyObjectButNotStrings() {
        assertTrue(m("null", "Point(1, 2)"));
        assertTrue(m("null", "Anything(3)"));
        assertFalse(m("null", "\"a\""));
    }

    @Test void mixedSchema() {
        assertTrue(m("\"job*\", null, \"*\"", "\"job_run\", Config(1, \"x\"), \"whatever\""));
        assertFalse(m("\"job*\", null, \"*\"", "\"job_run\", \"not-an-object\", \"whatever\""));
    }

    @Test void objectWithStringArgs() {
        assertTrue(m("User(1, \"alice\")", "User(1, \"alice\")"));
        assertFalse(m("User(1, \"alice\")", "User(1, \"bob\")"));
    }

    @Test void tuplesRejectWildcards() {
        assertThrows(IllegalArgumentException.class, () -> parseTuple("\"*\""));
        assertThrows(IllegalArgumentException.class, () -> parseTuple("null"));
        assertThrows(IllegalArgumentException.class, () -> parseTuple("Point"));
    }
}
