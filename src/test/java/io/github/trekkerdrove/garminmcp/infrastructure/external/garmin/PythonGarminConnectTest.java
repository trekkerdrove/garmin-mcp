package io.github.trekkerdrove.garminmcp.infrastructure.external.garmin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import io.github.trekkerdrove.garminmcp.application.dto.GarminFailure;
import io.github.trekkerdrove.garminmcp.application.port.out.GarminConnectPort.Operation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

        var gateway = new PythonGarminConnect(new GarminConnectWorker(executable, directory.resolve("bridge.py"), directory));
        var result = gateway.publish(Operation.coach_workouts,
                Map.of("startDate", "2026-11-22", "endDate", "2026-11-28"), 42);
        assertThat(result.status()).isEqualTo("partial");
        assertThat(result.data()).isEqualTo(JsonMapper.builder().build().readTree(response).path("data"));
    }

    @Test
    void validatesCoachDetailReplyIdentityAndPropagatesStatuses(@TempDir Path directory) throws Exception {
        Files.createFile(directory.resolve("garmin_tokens.json"));
        var executable = directory.resolve("worker");
        var uuid = "123e4567-e89b-42d3-a456-426614174000";
        var args = Map.<String, Object>of("startDate", "2026-11-23", "endDate", "2026-11-29", "workoutUuid", uuid);
        var gateway = new PythonGarminConnect(new GarminConnectWorker(executable, directory.resolve("bridge.py"), directory));
        var okReply = """
                {"status":"ok","data":{"workoutUuid":"%s","workout":{"workoutSegments":%s}}}
                """;
        var reply = okReply.formatted(uuid, "[{\"workoutSteps\":[{\"stepOrder\":1}]}]");
        writeDetailsWorker(executable, uuid, reply);
        var result = gateway.publish(Operation.coach_workout_details, args, 42);
        assertThat(result.status()).isEqualTo("ok");
        assertThat(result.data()).isEqualTo(JsonMapper.builder().build().readTree(reply).path("data"));

        writeDetailsWorker(executable, uuid, """
                {"status":"no_data","data":{"workoutUuid":"%s","workout":{"workoutSegments":[]}}}
                """.formatted(uuid));
        assertThat(gateway.publish(Operation.coach_workout_details, args, 42).status()).isEqualTo("no_data");
        writeDetailsWorker(executable, uuid, reply.replace(uuid, "123e4567-e89b-42d3-a456-426614174001"));
        assertThatThrownBy(() -> gateway.publish(Operation.coach_workout_details, args, 42))
                .isInstanceOf(GarminFailure.class).hasMessage("coach_read_failed");
        for (String segments : new String[]{"[null]", "[{}, {\"workoutSteps\":[]}]", "[{\"workoutSteps\":[null]}]"}) {
            writeDetailsWorker(executable, uuid, okReply.formatted(uuid, segments));
            assertThatThrownBy(() -> gateway.publish(Operation.coach_workout_details, args, 42))
                    .isInstanceOf(GarminFailure.class).hasMessage("coach_read_failed");
        }
        for (String status : new String[]{"coach_workout_not_found", "coach_read_failed", "workout_detail_limit",
                "garmin_access_denied", "garmin_rate_limited", "workout_session_unavailable", "workout_service_unavailable"}) {
            writeDetailsWorker(executable, uuid, "{\"status\":\"" + status + "\"}");
            assertThatThrownBy(() -> gateway.publish(Operation.coach_workout_details, args, 42))
                    .isInstanceOf(GarminFailure.class).hasMessage(status);
        }
        Files.writeString(executable, "#!/bin/sh\nexit 1\n");
        assertThatThrownBy(() -> gateway.publish(Operation.coach_workout_details, args, 42))
                .isInstanceOf(GarminFailure.class).hasMessage("coach_read_failed");
    }

    private static void writeDetailsWorker(Path executable, String uuid, String reply) throws Exception {
        Files.writeString(executable, """
                #!/bin/sh
                IFS= read -r request
                for expected in '"operation":"coach_workout_details"' '"expectedActivityId":42' \
                    '"startDate":"2026-11-23"' '"endDate":"2026-11-29"' '"workoutUuid":"%s"'; do
                  case "$request" in
                    *"$expected"*) ;;
                    *) exit 1 ;;
                  esac
                done
                printf '%%s\\n' '%s'
                """.formatted(uuid, reply));
        assertThat(executable.toFile().setExecutable(true)).isTrue();
    }
}
