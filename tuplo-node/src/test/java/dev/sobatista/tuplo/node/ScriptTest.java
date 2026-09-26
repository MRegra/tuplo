package dev.sobatista.tuplo.node;

import dev.sobatista.tuplo.core.LocalTupleSpace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;

import static dev.sobatista.tuplo.core.Fields.parseSchema;
import static org.junit.jupiter.api.Assertions.*;

class ScriptTest {

    @Test @Timeout(5)
    void runsAddsReadsAndRepeat() throws Exception {
        var space = new LocalTupleSpace();
        var script = Script.parse(List.of(
                "# a tiny producer",
                "add <\"counter\", \"start\">",
                "begin-repeat 3",
                "  add <\"tick\", \"x\">",
                "end-repeat",
                "read <\"counter\", \"*\">"));
        script.run(space);
        assertEquals(4, space.size(), "1 counter + 3 ticks");
        assertTrue(space.tryRead(parseSchema("\"counter\", \"start\"")).isPresent());
    }

    @Test
    void rejectsNestedRepeat() {
        var script = Script.parse(List.of("begin-repeat 2", "begin-repeat 2", "end-repeat", "end-repeat"));
        assertThrows(IllegalStateException.class, () -> script.run(new LocalTupleSpace()));
    }

    @Test
    void parsesObjectAndWildcardFields() {
        var s = Script.parse(List.of("add <\"user\", Point(1, 2)>", "take <\"user\", null>"));
        assertEquals(2, s.size());
    }
}
