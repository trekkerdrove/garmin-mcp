package io.github.trekkerdrove.garminmcp.application.service;

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
import io.github.trekkerdrove.garminmcp.application.port.out.WorkoutPublisher;
import io.github.trekkerdrove.garminmcp.application.port.out.WorkoutPublisher.Operation;

public final class WorkoutService {
    private final ActivityReader activities;
    private final WorkoutPublisher publisher;
    private final Semaphore slot = new Semaphore(1);

    public WorkoutService(ActivityReader activities, WorkoutPublisher publisher) {
        this.activities = activities;
        this.publisher = publisher;
    }

    public ToolResult create(WorkoutDraft draft, boolean confirm) {
        if (!confirm) {
            return new ToolResult("preview", draft);
        }
        return run(Operation.create, Map.of("workout", draft));
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
