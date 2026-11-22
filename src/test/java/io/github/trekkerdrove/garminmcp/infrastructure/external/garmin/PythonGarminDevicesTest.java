package io.github.trekkerdrove.garminmcp.infrastructure.external.garmin;

import io.github.trekkerdrove.garminmcp.application.dto.ToolResult;
import io.github.trekkerdrove.garminmcp.application.port.out.GarminDevices.Content;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class PythonGarminDevicesTest {
    @Test
    void treatsUnreadableOrMismatchedWorkerOutputAsUnconfirmedWithoutReplay(@TempDir Path directory) throws Exception {
        Files.createFile(directory.resolve("garmin_tokens.json"));
        var executable = directory.resolve("worker");
        var attempts = directory.resolve("attempts");
        var gateway = new PythonGarminDevices(new GarminConnectWorker(executable, directory.resolve("bridge.py"), directory));
        int count = 0;
        for (String output : List.of("an invalid reply with private details", " ", "null", """
                {"status":"queued","data":{"courseId":43,"deviceId":"7","name":"Run","deviceName":"Watch"}}
                """, "x".repeat(65_538))) {
            Files.writeString(executable, """
                    #!/bin/sh
                    IFS= read -r request
                    printf '.\\n' >> "${0%/*}/attempts"
                    /bin/cat <<'END'
                    """ + output + "\nEND\n");
            assertThat(executable.toFile().setExecutable(true)).isTrue();
            assertThat(ToolResult.run(() -> gateway.send(7, Content.COURSE, 42, "7", true)))
                    .isEqualTo(new ToolResult("device_send_unconfirmed", null));
            assertThat(Files.readAllLines(attempts)).hasSize(++count);
        }
    }

    @Test
    void doesNotReportUncertaintyIfTheProcessCouldNotStart(@TempDir Path directory) throws Exception {
        Files.createFile(directory.resolve("garmin_tokens.json"));
        var missing = directory.resolve("missing");
        var gateway = new PythonGarminDevices(new GarminConnectWorker(missing, directory.resolve("bridge.py"), directory));
        assertThat(ToolResult.run(() -> gateway.send(7, Content.WORKOUT, 42, "7", true)))
                .isEqualTo(new ToolResult("workout_service_unavailable", null));
    }
}
