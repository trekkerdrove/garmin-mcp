package io.github.trekkerdrove.garminmcp.infrastructure.external.garmin;

import java.time.Duration;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import io.github.trekkerdrove.garminmcp.application.dto.GarminFailure;
import io.github.trekkerdrove.garminmcp.application.dto.WorkoutDraft;
import io.github.trekkerdrove.garminmcp.application.dto.ToolResult;
import io.github.trekkerdrove.garminmcp.application.port.out.GarminConnectPort;
import io.github.trekkerdrove.garminmcp.application.service.StrengthWorkouts;

final class PythonGarminConnect implements GarminConnectPort {
    private static final Set<String> FAILURES = Set.of("workout_account_unverified", "workout_name_conflict",
            "workout_library_limit", "workout_rejected", "workout_write_unconfirmed", "workout_session_unavailable",
            "workout_service_unavailable", "garmin_rate_limited", "garmin_access_denied", "workout_not_found",
            "workout_unsupported", "workout_detail_limit", "invalid_input");
    private static final Set<String> COACH_RESULTS = Set.of("ok", "partial", "no_data", "no_active_plan", "coach_read_failed");
    private final GarminConnectWorker worker;

    PythonGarminConnect(GarminConnectWorker worker) {
        this.worker = worker;
    }

    @Override
    public ToolResult publish(Operation operation, Map<String, Object> args, long expectedActivityId) {
        String failure = operation == Operation.search || operation == Operation.get
                || operation == Operation.coach_workouts || Boolean.FALSE.equals(args.get("confirm"))
                ? "workout_service_unavailable"
                : operation == Operation.set_weight ? "weight_write_unconfirmed" : "workout_write_unconfirmed";
        var request = new HashMap<>(args);
        request.put("operation", operation);
        request.put("expectedActivityId", expectedActivityId);
        // Coach can use four requests plus a session refresh.
        var reply = worker.call(request, false, failure, Duration.ofSeconds(operation == Operation.coach_workouts ? 95 : 45));
        var status = reply.path("status").asString("");
        var data = reply.path("data");
        if (data.isObject() && switch (operation) {
            case search -> status.equals("ok") || status.equals("no_data") || status.equals("truncated");
            case get -> status.equals("ok") && data.path("workoutId").asLong() == (long) args.get("workoutId");
            case coach_workouts -> COACH_RESULTS.contains(status)
                    && args.get("startDate").equals(data.path("startDate").asString())
                    && args.get("endDate").equals(data.path("endDate").asString())
                    && data.path("weeks").isArray() && data.path("entries").isArray();
            case duplicate -> data.path("sourceWorkoutId").asLong() == (long) args.get("workoutId")
                    && (Boolean.TRUE.equals(args.get("confirm"))
                        ? (status.equals("created") || status.equals("already_exists"))
                            && data.path("workoutId").asLong() > 0
                            && data.path("workoutId").asLong() != (long) args.get("workoutId")
                        : status.equals("preview"));
            case set_weight -> args.get("date").equals(data.path("date").asString())
                    && Set.of("preview", "created", "updated", "already_exists", "selection_required",
                            "weight_not_editable", "weight_measurement_not_found", "weight_cleanup_pending",
                            "weight_measurement_changed").contains(status);
            default -> false;
        }) {
            return new ToolResult(status, data);
        }
        long id = reply.path("workoutId").asLong(0);
        if ((operation == Operation.create || operation == Operation.create_strength) && id > 0
                && (status.equals("created") || status.equals("already_exists"))) {
            String name = operation == Operation.create ? ((WorkoutDraft) args.get("workout")).name()
                    : ((StrengthWorkouts.Plan) args.get("workout")).name();
            return new ToolResult(status, Map.of("workoutId", id, "name", name, "reused", status.equals("already_exists")));
        }
        if ((operation == Operation.update || operation == Operation.delete)
                && Long.valueOf(id).equals(args.get("workoutId"))) {
            boolean confirm = Boolean.TRUE.equals(args.get("confirm"));
            String name = reply.path("name").asString("");
            boolean success = operation == Operation.delete ? status.equals("deleted")
                    : status.equals("updated") || status.equals("unchanged");
            if (!name.isBlank() && name.length() <= 512 && (confirm ? success : status.equals("preview"))) {
                var details = new HashMap<String, Object>();
                details.put("workoutId", id);
                details.put("name", name);
                if (!confirm && operation == Operation.update) {
                    if (args.containsKey("name")) details.put("newName", args.get("name"));
                    if (args.containsKey("steps")) details.put("replacementSteps", args.get("steps"));
                }
                return new ToolResult(status, details);
            }
        }
        throw new GarminFailure(FAILURES.contains(status) ? status : failure);
    }
}
