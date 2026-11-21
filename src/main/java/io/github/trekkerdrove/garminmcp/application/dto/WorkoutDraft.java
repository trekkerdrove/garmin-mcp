package io.github.trekkerdrove.garminmcp.application.dto;

import java.util.List;
import org.jspecify.annotations.Nullable;

public record WorkoutDraft(String name, Sport sport, List<Step> steps) {
    public enum Sport { running, walking, cycling }
    public enum Kind { warmup, interval, recovery, cooldown }

    public record Step(Kind kind, @Nullable Integer durationSeconds, @Nullable Integer distanceMeters,
                       @Nullable Integer heartRateZone) {
        public Step {
            if (kind == null || (durationSeconds == null) == (distanceMeters == null)
                    || (durationSeconds != null && (durationSeconds < 1 || durationSeconds > 86_400))
                    || (distanceMeters != null && (distanceMeters < 1 || distanceMeters > 1_000_000))
                    || (heartRateZone != null && (heartRateZone < 1 || heartRateZone > 5))) {
                throw new IllegalArgumentException("Invalid workout step");
            }
        }
    }

    public WorkoutDraft {
        if (name == null || name.isBlank() || name.strip().length() > 120 || name.chars().anyMatch(Character::isISOControl)
                || sport == null || steps == null || steps.isEmpty() || steps.size() > 50
                || steps.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("Invalid workout");
        }
        name = name.strip();
        steps = List.copyOf(steps);
    }
}
