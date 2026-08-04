package dev.formwild.session;

import dev.formwild.model.FormFault;
import dev.formwild.model.Rep;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Appends completed sets to a CSV.
 *
 * <p>Rep counts alone are a toy; the useful question is whether depth is improving across
 * weeks. CSV rather than a database because the point is that the data is the lifter's —
 * openable in any spreadsheet, on their own machine, with nothing uploaded anywhere.
 */
public final class SessionLog {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String HEADER =
            "timestamp,exercise,rep,depth_deg,descent_ms,total_ms,clean,faults\n";

    private final Path file;

    public SessionLog() {
        this(Path.of("formwild-sessions.csv"));
    }

    public SessionLog(Path file) {
        this.file = file;
    }

    /** Appends one set. Returns the file written to. */
    public Path append(List<Rep> reps) throws IOException {
        boolean fresh = !Files.exists(file);
        var out = new StringBuilder();
        if (fresh) out.append(HEADER);

        String timestamp = LocalDateTime.now().format(STAMP);
        for (Rep rep : reps) {
            out.append("%s,squat,%d,%.1f,%d,%d,%s,%s%n".formatted(
                    timestamp,
                    rep.number(),
                    rep.depthDeg(),
                    rep.descentMs(),
                    rep.totalMs(),
                    rep.clean(),
                    quote(rep.faults().stream().map(FormFault::label).collect(Collectors.joining(" ")))));
        }

        Files.writeString(file, out.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        return file;
    }

    /** CSV-quotes a field only when it needs it. */
    private static String quote(String value) {
        if (value.isEmpty()) return "";
        return value.contains(",") || value.contains("\"")
                ? "\"" + value.replace("\"", "\"\"") + "\""
                : value;
    }
}
