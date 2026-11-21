package io.github.trekkerdrove.garminmcp.application.service;

import java.util.Map;
import java.util.concurrent.Semaphore;
import io.github.trekkerdrove.garminmcp.application.dto.GarminFailure;
import io.github.trekkerdrove.garminmcp.application.dto.ToolResult;
import io.github.trekkerdrove.garminmcp.application.dto.WorkoutDraft;
import io.github.trekkerdrove.garminmcp.application.port.out.ActivityReader;
import io.github.trekkerdrove.garminmcp.application.port.out.ActivityReader.SearchView;
import io.github.trekkerdrove.garminmcp.application.port.out.WorkoutPublisher;

public final class CreateWorkout {
    private final ActivityReader activities;
    private final WorkoutPublisher publisher;
    private final Semaphore slot = new Semaphore(1);

    public CreateWorkout(ActivityReader activities, WorkoutPublisher publisher) {
        this.activities = activities;
        this.publisher = publisher;
    }

    public ToolResult execute(WorkoutDraft draft, boolean confirm) {
        if (!confirm) {
            return new ToolResult("preview", draft);
        }
        if (!slot.tryAcquire()) {
            throw new GarminFailure("workout_writer_busy");
        }
        try {
            var recent = activities.search(Map.of(), 0, 1, SearchView.SUMMARY);
            if (recent.isEmpty()) {
                throw new GarminFailure("workout_account_unverified");
            }
            var saved = publisher.publish(draft, recent.getFirst().id());
            return new ToolResult(saved.reused() ? "already_exists" : "created", saved);
        } finally {
            slot.release();
        }
    }
}
