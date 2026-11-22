package io.github.trekkerdrove.garminmcp.infrastructure.external.garmin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import io.github.trekkerdrove.garminmcp.application.port.out.GarminConnectPort.Operation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class PythonGarminConnectTest {
    @Test
    void acceptsPartialCoachReplyFromWorker(@TempDir Path directory) throws Exception {
        Files.createFile(directory.resolve("garmin_tokens.json"));
        var executable = directory.resolve("worker");
        var response = """
                {"status":"partial","data":{"startDate":"2026-11-22","endDate":"2026-11-28",
                "weeks":[{"weekStartDate":"2026-11-16","status":"published"},
                         {"weekStartDate":"2026-11-23","status":"error","error":"coach_read_failed"}],
                "entries":[{"date":"2026-11-22","trainingPlanId":11,"name":"Easy run"}]}}
                """;
        Files.writeString(executable, """
                #!/bin/sh
                IFS= read -r request
                for expected in '"operation":"coach_workouts"' '"expectedActivityId":42' \
                    '"startDate":"2026-11-22"' '"endDate":"2026-11-28"'; do
                  case "$request" in
                    *"$expected"*) ;;
                    *) exit 1 ;;
                  esac
                done
                printf '%s\\n' '
                """ + response + "'\n");
        assertThat(executable.toFile().setExecutable(true)).isTrue();

        var gateway = new PythonGarminConnect(executable, directory.resolve("bridge.py"), directory);
        var result = gateway.publish(Operation.coach_workouts,
                Map.of("startDate", "2026-11-22", "endDate", "2026-11-28"), 42);
        assertThat(result.status()).isEqualTo("partial");
        assertThat(result.data()).isEqualTo(JsonMapper.builder().build().readTree(response).path("data"));
    }
}
