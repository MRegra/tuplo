package dev.sobatista.tuplo.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Parser error paths of {@link Fields}, complementing the happy-path parsing already exercised
 * indirectly through {@link MatchingTest}.
 */
class FieldsParsingTest {

    @Test void rejectsObjectArgThatIsNeitherIntNorQuotedString() {
        var ex = assertThrows(IllegalArgumentException.class, () -> Fields.parseField("Point(1, abc)"));
        assertTrue(ex.getMessage().contains("must be an int or a"), ex.getMessage());
    }

    @Test void rejectsTypeNameThatIsNotAValidIdentifierOnObject() {
        var ex = assertThrows(IllegalArgumentException.class, () -> Fields.parseField("1Point(1)"));
        assertTrue(ex.getMessage().contains("not a valid type name"), ex.getMessage());
    }

    @Test void rejectsBareIdentifierThatIsNotAValidTypeName() {
        assertThrows(IllegalArgumentException.class, () -> Fields.parseField("1Point"));
    }

    @Test void rejectsUnterminatedString() {
        assertThrows(IllegalArgumentException.class, () -> Fields.parseField("\"abc"));
    }

    @Test void rejectsMalformedObjectMissingClosingParen() {
        assertThrows(IllegalArgumentException.class, () -> Fields.parseField("Point(1, 2"));
    }

    @Test void rejectsEmptyFieldList() {
        assertThrows(IllegalArgumentException.class, () -> Fields.parseFields("   "));
    }
}
