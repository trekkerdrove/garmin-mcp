package io.github.trekkerdrove.garminmcp.application.port.out;

import java.util.List;

public interface StrengthExerciseCatalog {
    record Exercise(String category, String exerciseName) {}

    List<Exercise> exercises();
}
