package io.github.trekkerdrove.garminmcp.infrastructure.external.garmin;

import io.github.trekkerdrove.garminmcp.application.dto.GarminFailure;
import io.github.trekkerdrove.garminmcp.application.dto.ToolResult;
import io.github.trekkerdrove.garminmcp.application.port.out.GarminDevices;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;

final class PythonGarminDevices implements GarminDevices {
    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    private static final Set<String> FAILURES = Set.of("workout_account_unverified", "course_account_unverified",
            "workout_session_unavailable", "workout_service_unavailable", "garmin_unavailable", "garmin_access_denied",
            "garmin_rate_limited", "workout_library_limit", "course_response_too_large", "course_busy",
            "workout_not_found", "course_not_found", "device_not_found", "device_send_rejected", "device_send_unconfirmed");
    private static final String UNCONFIRMED = "device_send_unconfirmed";

    private final GarminConnectWorker worker;

    PythonGarminDevices(GarminConnectWorker worker) {
        this.worker = worker;
    }

    @Override
    public ToolResult list(long accountActivityId, Content accountType) {
        String failure = unavailable(accountType);
        var reply = worker.call(Map.of("operation", "list_devices", "expectedActivityId", accountActivityId),
                accountType == Content.COURSE, failure, TIMEOUT);
        String status = reply.path("status").asString("");
        var rows = reply.path("data").path("devices");
        if (!rows.isArray() || !(rows.isEmpty() ? "no_data" : "ok").equals(status)) throw failure(status, failure);
        var devices = new ArrayList<Map<String, String>>();
        for (var row : rows) {
            String id = string(row.path("deviceId"), failure);
            if (!id.matches("[0-9]{1,19}")) throw new GarminFailure(failure);
            try {
                if (Long.parseLong(id) <= 0) throw new GarminFailure(failure);
            } catch (NumberFormatException invalid) {
                throw new GarminFailure(failure);
            }
            devices.add(Map.of("deviceId", id, "name", string(row.path("name"), failure)));
        }
        return new ToolResult(status, Map.of("devices", devices));
    }

    @Override
    public ToolResult send(long accountActivityId, Content content, long contentId, String deviceId, boolean confirm) {
        boolean courses = content == Content.COURSE;
        String idKey = courses ? "courseId" : "workoutId";
        String fallback = confirm ? UNCONFIRMED : unavailable(content);
        var reply = worker.call(Map.of("operation", "send_to_device", "expectedActivityId", accountActivityId,
                idKey, contentId, "deviceId", deviceId, "confirm", confirm), courses, fallback, TIMEOUT);
        String status = reply.path("status").asString("");
        if (!(confirm ? "queued" : "preview").equals(status)) throw failure(status, fallback);
        var data = reply.path("data");
        var returnedId = data.path(idKey);
        if (!returnedId.isIntegralNumber() || !returnedId.canConvertToLong() || returnedId.asLong() != contentId
                || !deviceId.equals(string(data.path("deviceId"), fallback))) {
            throw new GarminFailure(fallback);
        }
        return new ToolResult(status, Map.of(idKey, contentId, "deviceId", deviceId,
                "name", string(data.path("name"), fallback), "deviceName", string(data.path("deviceName"), fallback)));
    }

    private static String string(JsonNode value, String failure) {
        if (!value.isString() || value.asString().isBlank()) throw new GarminFailure(failure);
        return value.asString();
    }

    private static GarminFailure failure(String status, String fallback) {
        return new GarminFailure(FAILURES.contains(status) ? status : fallback);
    }

    private static String unavailable(Content content) {
        return content == Content.COURSE ? "garmin_unavailable" : "workout_service_unavailable";
    }
}
