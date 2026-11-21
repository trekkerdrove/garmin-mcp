package io.github.trekkerdrove.garminmcp.application.dto;

import java.util.List;
import org.jspecify.annotations.Nullable;

public record WorkoutDraft(String name, Sport sport, List<Step> steps) {
    public enum Sport { running, walking, cycling }
    public enum Kind { warmup, interval, recovery, cooldown }

    public record PaceSecondsPerKm(int fast, int slow) {
        public PaceSecondsPerKm {
            if (fast <= 0 || slow < fast) {
                throw new IllegalArgumentException("Pace bounds must be positive and ordered fastest first");
            }
        }
    }

    public record DistanceChange(int stepOrder, int distanceMeters) {
        public DistanceChange {
            if (stepOrder < 1 || distanceMeters < 1 || distanceMeters > 1_000_000) {
                throw new IllegalArgumentException("Invalid workout distance change");
            }
        }
    }

    public record Step(Kind kind, @Nullable Integer durationSeconds, @Nullable Integer distanceMeters,
                       @Nullable Integer heartRateZone, @Nullable PaceSecondsPerKm paceSecondsPerKm) {
        public Step {
            if (kind == null || (durationSeconds == null) == (distanceMeters == null)
                    || (durationSeconds != null && (durationSeconds < 1 || durationSeconds > 86_400))
                    || (distanceMeters != null && (distanceMeters < 1 || distanceMeters > 1_000_000))
                    || (heartRateZone != null && (heartRateZone < 1 || heartRateZone > 5 || paceSecondsPerKm != null))) {
                throw new IllegalArgumentException("Invalid workout step");
            }
        }
    }

    public WorkoutDraft {
        name = validName(name);
        steps = validSteps(steps);
        if (sport == null) {
            throw new IllegalArgumentException("Missing sport");
        }
        if (sport == Sport.cycling && steps.stream().anyMatch(step -> step.paceSecondsPerKm() != null)) {
            throw new IllegalArgumentException("Pace targets require running or walking");
        }
    }

    public static String validName(String name) {
        if (name == null || name.isBlank() || name.strip().length() > 120 || name.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid workout name");
        }
        return name.strip();
    }

    public static List<Step> validSteps(List<Step> steps) {
        if (steps == null || steps.isEmpty() || steps.size() > 50 || steps.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("Invalid workout steps");
        }
        return List.copyOf(steps);
    }
}
