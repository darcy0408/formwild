package dev.formwild.session;

import dev.formwild.model.FormFault;
import dev.formwild.model.Rep;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SessionLogTest {

    private static Rep clean(int number) {
        return new Rep(number, 88, 1400, 2600, List.of());
    }

    private static Rep faulty(int number) {
        return new Rep(number, 118, 500, 1200,
                List.of(new FormFault.ShallowDepth(118, 100), new FormFault.RushedDescent(500)));
    }

    @Test
    @DisplayName("writes a header once, then one row per rep")
    void writesHeaderThenRows(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("sessions.csv");
        var log = new SessionLog(file);

        log.append(List.of(clean(1), clean(2)));
        List<String> lines = Files.readAllLines(file);

        assertEquals(3, lines.size(), "header plus two reps");
        assertTrue(lines.getFirst().startsWith("timestamp,exercise,rep,"));
        assertTrue(lines.get(1).contains(",squat,1,88.0,1400,2600,true,"));
    }

    @Test
    @DisplayName("appends later sets without repeating the header")
    void appendsWithoutDuplicatingHeader(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("sessions.csv");
        var log = new SessionLog(file);

        log.append(List.of(clean(1)));
        log.append(List.of(clean(1), clean(2)));

        List<String> lines = Files.readAllLines(file);
        assertEquals(4, lines.size(), "one header and three rep rows");
        assertEquals(1, lines.stream().filter(l -> l.startsWith("timestamp,")).count(),
                "the header must appear exactly once or the file will not open as a table");
    }

    @Test
    @DisplayName("records fault labels so trends stay analysable in a spreadsheet")
    void recordsFaultLabels(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("sessions.csv");
        new SessionLog(file).append(List.of(faulty(1)));

        String row = Files.readAllLines(file).get(1);
        assertTrue(row.contains("false"), "a faulty rep is not clean");
        assertTrue(row.contains("depth"), "expected the depth fault label in: " + row);
        assertTrue(row.contains("tempo"), "expected the tempo fault label in: " + row);
    }

    @Test
    @DisplayName("every row has the same column count as the header")
    void rowsAreWellFormed(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("sessions.csv");
        new SessionLog(file).append(List.of(clean(1), faulty(2)));

        List<String> lines = Files.readAllLines(file);
        long headerColumns = lines.getFirst().chars().filter(c -> c == ',').count();
        for (String row : lines.subList(1, lines.size())) {
            assertEquals(headerColumns, row.chars().filter(c -> c == ',').count(),
                    "ragged row would break any spreadsheet import: " + row);
        }
    }
}
