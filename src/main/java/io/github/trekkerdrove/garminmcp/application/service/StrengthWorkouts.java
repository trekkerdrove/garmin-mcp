package io.github.trekkerdrove.garminmcp.application.service;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import io.github.trekkerdrove.garminmcp.application.dto.ToolResult;
import io.github.trekkerdrove.garminmcp.application.dto.WorkoutDraft;
import io.github.trekkerdrove.garminmcp.application.port.out.StrengthExerciseCatalog;
import io.github.trekkerdrove.garminmcp.application.port.out.StrengthExerciseCatalog.Exercise;
import org.jspecify.annotations.Nullable;

public final class StrengthWorkouts {
    private static final BigDecimal MAX_KG = new BigDecimal("453.5");
    // shortcut: common French aliases only, extend when more translations are needed.
    private static final Map<String, Set<String>> ALIASES = Map.of(
            "squat a la barre", Set.of("BARBELL_BACK_SQUAT", "BARBELL_FRONT_SQUAT"),
            "squat arriere", Set.of("BARBELL_BACK_SQUAT"),
            "squat avant", Set.of("BARBELL_FRONT_SQUAT"),
            "developpe couche", Set.of("BARBELL_BENCH_PRESS", "DUMBBELL_BENCH_PRESS"),
            "pompes", Set.of("PUSH_UP"),
            "gainage", Set.of("PLANK"));
    private final StrengthExerciseCatalog catalog;

    public StrengthWorkouts(StrengthExerciseCatalog catalog) {
        this.catalog = catalog;
    }

    public record Block(String category, String exerciseName, int sets,
                        @Nullable Integer reps, @Nullable Integer durationSeconds,
                        @Nullable BigDecimal weightKg, @Nullable Integer restBetweenSetsSeconds,
                        @Nullable Integer restAfterBlockSeconds) {}

    public record Plan(String name, String sport, List<Map<String, Object>> steps) {}

    public ToolResult search(String query, int limit) {
        String text = searchText(WorkoutDraft.validName(query));
        if (text.isEmpty() || limit < 1 || limit > 20) {
            throw new IllegalArgumentException("Invalid exercise search");
        }
        Set<String> aliases = ALIASES.getOrDefault(text, Set.of());
        var exercises = catalog.exercises();
        var matches = exercises.stream()
                .filter(exercise -> searchText(exercise.exerciseName()).equals(text)
                        || searchText(exercise.category() + "/" + exercise.exerciseName()).equals(text))
                .limit(limit + 1L).toList();
        if (matches.isEmpty()) {
            matches = exercises.stream()
                    .filter(exercise -> aliases.isEmpty()
                            ? searchText(exercise.exerciseName()).contains(text)
                                    || searchText(exercise.category()).contains(text)
                            : aliases.contains(exercise.exerciseName()))
                    .limit(limit + 1L).toList();
        }
        var rows = matches.stream().limit(limit)
                .map(exercise -> Map.of("category", exercise.category(), "exerciseName", exercise.exerciseName(),
                        "label", label(exercise.exerciseName()) + " (" + label(exercise.category()) + ")"))
                .toList();
        var data = Map.of("exercises", rows);
        if (rows.isEmpty()) return ToolResult.empty(data);
        return matches.size() > limit ? ToolResult.truncated(data) : ToolResult.ok(data);
    }

    public ToolResult create(String name, List<Block> blocks, boolean confirm, GarminConnectService workouts) {
        name = WorkoutDraft.validName(name);
        if (blocks == null || blocks.isEmpty() || blocks.size() > 50) {
            throw new IllegalArgumentException("Expected 1 to 50 exercise blocks");
        }

        int stepCount = 0;
        for (int i = 0; i < blocks.size(); i++) {
            Block block = blocks.get(i);
            if (!validBlock(block, i == blocks.size() - 1)) {
                return invalidBlock(i, block, "invalid_exercise_parameters");
            }
            stepCount += block.sets()
                    + (seconds(block.restBetweenSetsSeconds()) > 0 ? block.sets() - 1 : 0)
                    + (seconds(block.restAfterBlockSeconds()) > 0 ? 1 : 0);
            if (stepCount > 50) return invalidBlock(i, block, "step_limit_exceeded");
        }

        var references = new HashSet<>(catalog.exercises());
        var steps = new ArrayList<Map<String, Object>>(stepCount);
        for (int i = 0; i < blocks.size(); i++) {
            Block block = blocks.get(i);
            if (!references.contains(new Exercise(block.category(), block.exerciseName()))) {
                return invalidBlock(i, block, "unknown_exercise");
            }
            var set = new HashMap<String, Object>();
            set.put("kind", "set");
            set.put("category", block.category());
            set.put("exerciseName", block.exerciseName());
            if (block.reps() != null) set.put("reps", block.reps());
            else set.put("durationSeconds", block.durationSeconds());
            set.put("weightKg", block.weightKg() == null ? null : block.weightKg().stripTrailingZeros());
            var series = Collections.unmodifiableMap(set);
            for (int number = 1; number <= block.sets(); number++) {
                steps.add(series);
                int rest = number < block.sets() ? seconds(block.restBetweenSetsSeconds())
                        : seconds(block.restAfterBlockSeconds());
                if (rest > 0) steps.add(Map.of("kind", "rest", "durationSeconds", rest));
            }
        }

        var plan = new Plan(name, "strength_training", List.copyOf(steps));
        return confirm ? workouts.createStrength(plan) : new ToolResult("preview", plan);
    }

    private static boolean validBlock(Block block, boolean last) {
        if (block == null || !validKey(block.category()) || !validKey(block.exerciseName())
                || block.sets() < 1 || block.sets() > 50
                || (block.reps() == null) == (block.durationSeconds() == null)
                || (block.reps() != null && block.reps() < 1)
                || (block.durationSeconds() != null && (block.durationSeconds() < 1 || block.durationSeconds() > 86_400))) {
            return false;
        }
        int between = seconds(block.restBetweenSetsSeconds());
        int after = seconds(block.restAfterBlockSeconds());
        var kg = block.weightKg();
        return between >= 0 && between <= 86_400 && (block.sets() > 1 || between == 0)
                && after >= 0 && after <= 86_400 && (!last || after == 0)
                && (kg == null || (kg.signum() > 0 && kg.compareTo(MAX_KG) <= 0 && kg.stripTrailingZeros().scale() <= 3));
    }

    private static boolean validKey(String key) {
        return key != null && key.matches("[A-Z0-9_]{1,120}");
    }

    private static ToolResult invalidBlock(int index, Block block, String reason) {
        var data = new HashMap<String, Object>();
        data.put("exerciseIndex", index + 1);
        data.put("reason", reason);
        if (block != null && validKey(block.category())) data.put("category", block.category());
        if (block != null && validKey(block.exerciseName())) data.put("exerciseName", block.exerciseName());
        return new ToolResult("invalid_input", data);
    }

    private static int seconds(Integer value) {
        return value == null ? 0 : value;
    }

    private static String label(String key) {
        return key.replace('_', ' ').toLowerCase(Locale.ROOT);
    }

    private static String searchText(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").strip();
    }
}
