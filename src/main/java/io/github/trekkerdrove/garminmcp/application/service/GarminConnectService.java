package io.github.trekkerdrove.garminmcp.application.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import io.github.trekkerdrove.garminmcp.application.dto.GarminFailure;
import io.github.trekkerdrove.garminmcp.application.dto.ToolResult;
import io.github.trekkerdrove.garminmcp.application.dto.WorkoutDraft;
import io.github.trekkerdrove.garminmcp.application.port.out.ActivityReader;
import io.github.trekkerdrove.garminmcp.application.port.out.ActivityReader.SearchView;
import io.github.trekkerdrove.garminmcp.application.port.out.GarminConnectPort;
import io.github.trekkerdrove.garminmcp.application.port.out.GarminConnectPort.Operation;

public final class GarminConnectService {
    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");
    private final ActivityReader activities;
    private final GarminConnectPort publisher;
    private final Clock clock;
    private final Semaphore slot = new Semaphore(1);

    public GarminConnectService(ActivityReader activities, GarminConnectPort publisher, Clock clock) {
        this.activities = activities;
        this.publisher = publisher;
        this.clock = clock;
    }

    public ToolResult create(WorkoutDraft draft, boolean confirm) {
        if (!confirm) {
            return new ToolResult("preview", draft);
        }
        return run(Operation.create, Map.of("workout", draft));
    }

    ToolResult createStrength(StrengthWorkouts.Plan plan) {
        return run(Operation.create_strength, Map.of("workout", plan));
    }

    public ToolResult update(long workoutId, String name, List<WorkoutDraft.Step> steps, boolean confirm) {
        if (name == null && steps == null) {
            throw new IllegalArgumentException("Supply a name or steps");
        }
        var args = new HashMap<>(target(workoutId, confirm));
        if (name != null) args.put("name", WorkoutDraft.validName(name));
        if (steps != null) args.put("steps", WorkoutDraft.validSteps(steps));
        return run(Operation.update, args);
    }

    public ToolResult delete(long workoutId, boolean confirm) {
        return run(Operation.delete, target(workoutId, confirm));
    }

    public ToolResult search(String query, int limit) {
        if (limit < 1 || limit > 20) {
            throw new IllegalArgumentException("Invalid workout search limit");
        }
        return run(Operation.search, Map.of("query", query == null ? "" : WorkoutDraft.validName(query), "limit", limit));
    }

    public ToolResult get(long workoutId) {
        return run(Operation.get, target(workoutId, false));
    }

    public ToolResult getCoachWorkouts() {
        var today = LocalDate.now(clock.withZone(PARIS));
        return run(Operation.coach_workouts, Map.of("startDate", today.toString(), "endDate", today.plusDays(6).toString()));
    }

    public ToolResult duplicate(long workoutId, String name, List<WorkoutDraft.DistanceChange> changes, boolean confirm) {
        var args = new HashMap<>(target(workoutId, confirm));
        args.put("name", WorkoutDraft.validName(name));
        if (changes != null) {
            if (changes.size() > 50 || changes.stream().anyMatch(Objects::isNull)
                    || changes.stream().map(WorkoutDraft.DistanceChange::stepOrder).distinct().count() != changes.size()) {
                throw new IllegalArgumentException("Invalid workout distance changes");
            }
            args.put("distanceChanges", List.copyOf(changes));
        }
        return run(Operation.duplicate, args);
    }

    public ToolResult setWeight(String date, String time, BigDecimal weightKg, String selection, boolean confirm) {
        var now = OffsetDateTime.now(clock.withZone(PARIS));
        var day = date == null ? now.toLocalDate() : LocalDate.parse(date);
        if (day.getYear() < 1900 || day.isAfter(now.toLocalDate()) || weightKg == null
                || weightKg.signum() <= 0 || weightKg.compareTo(BigDecimal.valueOf(1_000)) >= 0
                || weightKg.stripTrailingZeros().scale() > 3
                || (selection != null && (!selection.matches("[0-9]{1,20}:-?[0-9]{1,15}:-?[0-9]{1,15}:[0-9]{1,6}(?:\\.[0-9]{1,9})?")
                        || time != null))) {
            throw new IllegalArgumentException("Invalid weigh-in");
        }
        if (time != null && !time.matches("[0-9]{2}:[0-9]{2}")) {
            throw new IllegalArgumentException("Expected HH:mm");
        }
        var local = day.atTime(time == null ? LocalTime.MIDNIGHT : LocalTime.parse(time));
        var offsets = PARIS.getRules().getValidOffsets(local);
        if (offsets.size() != 1 || local.atOffset(offsets.getFirst()).isAfter(now)) {
            throw new IllegalArgumentException("Invalid local time");
        }
        var args = new HashMap<String, Object>();
        args.put("date", day.toString());
        args.put("timestamp", local.atOffset(offsets.getFirst()).toString());
        args.put("weightKg", weightKg);
        args.put("confirm", confirm);
        if (selection != null) {
            args.put("selection", selection);
        }
        return run(Operation.set_weight, args);
    }

    private static Map<String, Object> target(long workoutId, boolean confirm) {
        if (workoutId <= 0) throw new IllegalArgumentException("Invalid workout ID");
        return Map.of("workoutId", workoutId, "confirm", confirm);
    }

    private ToolResult run(Operation operation, Map<String, Object> args) {
        if (!slot.tryAcquire()) {
            throw new GarminFailure("workout_writer_busy");
        }
        try {
            var recent = activities.search(Map.of(), 0, 1, SearchView.SUMMARY);
            if (recent.isEmpty()) {
                throw new GarminFailure("workout_account_unverified");
            }
            return publisher.publish(operation, args, recent.getFirst().id());
        } finally {
            slot.release();
        }
    }
}
