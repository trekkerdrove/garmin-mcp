package io.github.trekkerdrove.garminmcp.infrastructure.external.garmin;

import io.github.trekkerdrove.garminmcp.application.dto.GarminFailure;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static io.github.trekkerdrove.garminmcp.application.dto.CourseData.MAX_EXPORT_BYTES;
import static io.github.trekkerdrove.garminmcp.application.dto.CourseData.MAX_HTTP_BYTES;

final class GarminConnectWorker {
    private static final int EXPORT_WIRE_LIMIT = 4 * ((MAX_EXPORT_BYTES + 2) / 3) + 1024;
    final JsonMapper json = JsonMapper.builder().build();
    private final Path python;
    private final Path script;
    private final Path tokenDirectory;

    GarminConnectWorker(Path python, Path script, Path tokenDirectory) {
        this.python = python.toAbsolutePath();
        this.script = script.toAbsolutePath();
        this.tokenDirectory = tokenDirectory.toAbsolutePath();
    }

    JsonNode call(Map<String, Object> args, boolean courses, String failure, Duration timeout) {
        if (!Files.isRegularFile(tokenDirectory.resolve("garmin_tokens.json"))) {
            throw new GarminFailure(courses ? "course_account_unverified" : "workout_session_unavailable");
        }
        var arguments = new ArrayList<>(List.of(python.toString(), "-I", script.toString(), tokenDirectory.toString()));
        if (courses) arguments.add("courses");
        boolean courseResponse = courses && !"send_to_device".equals(args.get("operation"))
                && !"list_devices".equals(args.get("operation"));
        int maxResponse = !courseResponse ? 65_537
                : "course_export".equals(args.get("operation")) ? EXPORT_WIRE_LIMIT + 1 : MAX_HTTP_BYTES + 1;
        int maxRequest = courses ? MAX_HTTP_BYTES : 16_384;
        var command = new ProcessBuilder(arguments).redirectError(ProcessBuilder.Redirect.DISCARD);
        command.environment().clear();
        Process worker = null;
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            byte[] request = json.writeValueAsBytes(args);
            if (request.length > maxRequest) throw new GarminFailure(courses ? "course_source_too_large" : "workout_rejected");
            worker = command.start();
            var output = worker.getInputStream();
            var response = new FutureTask<>(() -> {
                try (output) {
                    return output.readNBytes(maxResponse + 1);
                }
            });
            var input = worker.getOutputStream();
            var delivery = new FutureTask<>(() -> {
                try (input) {
                    input.write(request);
                }
                return null;
            });
            Thread.startVirtualThread(response);
            Thread.startVirtualThread(delivery);
            if (!worker.waitFor(remaining(deadline), TimeUnit.NANOSECONDS) || worker.exitValue() != 0) {
                throw new GarminFailure(failure);
            }
            delivery.get(remaining(deadline), TimeUnit.NANOSECONDS);
            var bytes = response.get(remaining(deadline), TimeUnit.NANOSECONDS);
            if (bytes.length == 0 || bytes.length > maxResponse) {
                throw new GarminFailure(courseResponse && bytes.length > maxResponse ? "course_response_too_large" : failure);
            }
            return json.readTree(bytes);
        } catch (IOException | JacksonException | ExecutionException | TimeoutException error) {
            throw new GarminFailure(worker == null ? courses ? "garmin_unavailable" : "workout_service_unavailable" : failure);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new GarminFailure(failure);
        } finally {
            if (worker != null && worker.isAlive()) worker.destroyForcibly();
        }
    }

    private static long remaining(long deadline) {
        return Math.max(0, deadline - System.nanoTime());
    }
}
