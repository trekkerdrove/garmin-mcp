package io.github.trekkerdrove.garminmcp.infrastructure.external.garmin;

import io.github.trekkerdrove.garminmcp.application.dto.CourseData.*;
import io.github.trekkerdrove.garminmcp.application.dto.GarminFailure;
import io.github.trekkerdrove.garminmcp.application.port.out.CourseLibrary;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.time.Duration;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

final class PythonCourseLibrary implements CourseLibrary {
    private static final Set<String> ERRORS = Set.of("invalid_input", "course_account_unverified", "course_busy",
            "course_source_too_large", "course_response_too_large", "course_export_unsupported", "course_no_geometry",
            "course_edit_unsupported", "garmin_unavailable", "garmin_access_denied", "garmin_rate_limited");
    private static final Set<String> MUTATION_STATUSES = Set.of("accepted", "already_absent", "course_not_found",
            "course_changed", "course_write_unconfirmed", "course_not_ready", "garmin_rate_limited");
    private final GarminConnectWorker worker;
    private final Capabilities capabilities;

    PythonCourseLibrary(GarminConnectWorker worker, Capabilities capabilities) {
        this.worker = worker;
        this.capabilities = capabilities;
    }

    @Override
    public Capabilities capabilities() {
        return capabilities;
    }

    @Override
    public Library list(long accountActivityId) {
        return list(accountActivityId, Duration.ofSeconds(60));
    }

    @Override
    public Library list(long accountActivityId, Duration timeout) {
        var reply = call("list", accountActivityId, Map.of(), false, timeout);
        return projection(reply, Library.class);
    }

    @Override
    public Optional<Snapshot> get(long accountActivityId, long courseId) {
        return get(accountActivityId, courseId, Duration.ofSeconds(60));
    }

    @Override
    public Optional<Snapshot> get(long accountActivityId, long courseId, Duration timeout) {
        var reply = call("get", accountActivityId, Map.of("courseId", courseId), false, timeout);
        if ("absent".equals(status(reply))) return Optional.empty();
        var result = projection(reply, Snapshot.class);
        if (result.summary() == null || result.content() == null || result.summary().courseId() != courseId
                || result.version() == null || result.settingsVersion() == null || result.navigationVersion() == null) {
            throw new GarminFailure("garmin_unavailable");
        }
        return Optional.of(result);
    }

    @Override
    public boolean verifyAbsent(long accountActivityId, long courseId) {
        var reply = call("absent", accountActivityId, Map.of("courseId", courseId), false);
        if ("present".equals(status(reply))) return false;
        if ("absent".equals(status(reply))) return true;
        throw failure(reply);
    }

    @Override
    public Mutation create(long accountActivityId, Content content) {
        return mutate("create", accountActivityId, Map.of("content", content));
    }

    @Override
    public Mutation update(long accountActivityId, Snapshot current, Content target, Set<String> fields) {
        return mutate("update", accountActivityId, Map.of("courseId", current.summary().courseId(),
                "version", current.version(), "content", target, "fields", fields));
    }

    @Override
    public Mutation delete(long accountActivityId, Snapshot current) {
        return mutate("delete", accountActivityId, Map.of("courseId", current.summary().courseId(), "version", current.version()));
    }

    @Override
    public Optional<byte[]> export(long accountActivityId, long courseId, Format format) {
        var reply = call("export", accountActivityId, Map.of("courseId", courseId, "format", format), false);
        if (Set.of("course_export_unsupported", "course_response_too_large", "garmin_unavailable").contains(status(reply))) {
            return Optional.empty();
        }
        if ("absent".equals(status(reply))) throw new GarminFailure("course_not_found");
        return Optional.of(Base64.getDecoder().decode(data(reply, "ok").path("contentBase64").asString("")));
    }

    private Mutation mutate(String command, long account, Map<String, Object> args) {
        var reply = call(command, account, args, true);
        String status = status(reply);
        if (!MUTATION_STATUSES.contains(status)) {
            if (ERRORS.contains(status)) throw failure(reply);
            throw new GarminFailure("course_write_unconfirmed");
        }
        var data = reply.path("data");
        long id = data.path("courseId").asLong(0);
        int retry = data.path("retryAfterSeconds").asInt(-1);
        return new Mutation(status, id > 0 ? id : null, retry >= 0 ? retry : null);
    }

    private JsonNode call(String command, long account, Map<String, Object> args, boolean mutation) {
        return call(command, account, args, mutation, Duration.ofSeconds(60));
    }

    private JsonNode call(String command, long account, Map<String, Object> args, boolean mutation, Duration timeout) {
        var request = new HashMap<>(args);
        request.put("operation", "course_" + command);
        request.put("expectedActivityId", account);
        return worker.call(request, true, mutation ? "course_write_unconfirmed" : "garmin_unavailable", timeout);
    }

    private static JsonNode data(JsonNode reply, String expected) {
        if (!expected.equals(status(reply)) || !reply.path("data").isObject()) throw failure(reply);
        return reply.path("data");
    }

    private <T> T projection(JsonNode reply, Class<T> type) {
        try {
            return worker.json.treeToValue(data(reply, "ok"), type);
        } catch (JacksonException invalid) {
            throw new GarminFailure("garmin_unavailable");
        }
    }

    private static String status(JsonNode reply) {
        return reply.path("status").asString("");
    }

    private static RuntimeException failure(JsonNode reply) {
        String status = status(reply);
        if ("garmin_rate_limited".equals(status)) {
            int seconds = reply.path("data").path("retryAfterSeconds").asInt(-1);
            return new Problem(status, seconds < 0 ? null : Map.of("retryAfterSeconds", seconds));
        }
        if ("course_busy".equals(status)) return new Problem(status);
        return new GarminFailure(ERRORS.contains(status) ? status : "garmin_unavailable");
    }
}
