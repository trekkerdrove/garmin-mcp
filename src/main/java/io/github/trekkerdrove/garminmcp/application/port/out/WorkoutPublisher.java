package io.github.trekkerdrove.garminmcp.application.port.out;

import io.github.trekkerdrove.garminmcp.application.dto.WorkoutDraft;

public interface WorkoutPublisher {
    PublishedWorkout publish(WorkoutDraft workout, long expectedActivityId);

    record PublishedWorkout(long workoutId, String name, boolean reused) {}
}
