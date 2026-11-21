package io.github.trekkerdrove.garminmcp.infrastructure.external.garmin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import io.github.trekkerdrove.garminmcp.application.dto.GarminFailure;
import io.github.trekkerdrove.garminmcp.application.dto.WorkoutDraft;
import io.github.trekkerdrove.garminmcp.application.port.out.WorkoutPublisher;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

final class PythonWorkoutPublisher implements WorkoutPublisher {
    private static final Set<String> FAILURES = Set.of("workout_account_unverified", "workout_name_conflict",
            "workout_library_limit", "workout_rejected", "workout_write_unconfirmed", "workout_session_unavailable",
            "workout_service_unavailable", "garmin_rate_limited", "garmin_access_denied");
    private static final int MAX_RESPONSE = 4096;
    private final JsonMapper json = JsonMapper.builder().build();
    private final Path python;
    private final Path script;
    private final Path tokenDirectory;

    PythonWorkoutPublisher(Path python, Path script, Path tokenDirectory) {
        this.python = python.toAbsolutePath();
        this.script = script.toAbsolutePath();
        this.tokenDirectory = tokenDirectory.toAbsolutePath();
    }

    @Override
    public PublishedWorkout publish(WorkoutDraft workout, long expectedActivityId) {
        if (!Files.isRegularFile(tokenDirectory.resolve("garmin_tokens.json"))) {
            throw new GarminFailure("workout_session_unavailable");
        }
        var command = new ProcessBuilder(python.toString(), "-I", script.toString(), tokenDirectory.toString())
                .redirectError(ProcessBuilder.Redirect.DISCARD);
        command.environment().clear();
        Process worker = null;
        try {
            var request = json.writeValueAsBytes(Map.of("workout", workout, "expectedActivityId", expectedActivityId));
            worker = command.start();
            try (var input = worker.getOutputStream()) {
                input.write(request);
            }
            if (!worker.waitFor(45, TimeUnit.SECONDS) || worker.exitValue() != 0) {
                throw new GarminFailure("workout_write_unconfirmed");
            }
            try (var output = worker.getInputStream()) {
                var bytes = output.readNBytes(MAX_RESPONSE + 1);
                if (bytes.length == 0 || bytes.length > MAX_RESPONSE) {
                    throw new GarminFailure("workout_write_unconfirmed");
                }
                var reply = json.readTree(bytes);
                var status = reply.path("status").asString("");
                long id = reply.path("workoutId").asLong(0);
                if (id > 0 && (status.equals("created") || status.equals("already_exists"))) {
                    return new PublishedWorkout(id, workout.name(), status.equals("already_exists"));
                }
                throw new GarminFailure(FAILURES.contains(status) ? status : "workout_write_unconfirmed");
            }
        } catch (IOException | JacksonException error) {
            throw new GarminFailure(worker == null ? "workout_service_unavailable" : "workout_write_unconfirmed");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new GarminFailure("workout_write_unconfirmed");
        } finally {
            if (worker != null && worker.isAlive()) {
                worker.destroyForcibly();
            }
        }
    }
}
