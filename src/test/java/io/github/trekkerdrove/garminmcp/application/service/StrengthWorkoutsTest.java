package io.github.trekkerdrove.garminmcp.application.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Map;

import io.github.trekkerdrove.garminmcp.application.dto.ToolResult;
import io.github.trekkerdrove.garminmcp.application.port.out.ActivityReader;
import io.github.trekkerdrove.garminmcp.application.port.out.GarminConnectPort;
import io.github.trekkerdrove.garminmcp.application.port.out.StrengthExerciseCatalog.Exercise;
import io.github.trekkerdrove.garminmcp.application.service.StrengthWorkouts.Block;
import io.github.trekkerdrove.garminmcp.application.service.StrengthWorkouts.Plan;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class StrengthWorkoutsTest {
    private final ActivityReader activities = mock(ActivityReader.class);
    private final GarminConnectPort publisher = mock(GarminConnectPort.class);
    private final GarminConnectService workouts = new GarminConnectService(activities, publisher, Clock.systemUTC());
    private final StrengthWorkouts strength = new StrengthWorkouts(() -> List.of(
            new Exercise("SQUAT", "BARBELL_BACK_SQUAT"), new Exercise("SQUAT", "BARBELL_FRONT_SQUAT"),
            new Exercise("PUSH_UP", "PUSH_UP"), new Exercise("PLANK", "PLANK"), new Exercise("SUSPENSION", "PLANK")));

    @Test
    void searchesExactAndAmbiguousNamesWithAnExplicitTruncationStatus() {
        assertThat(strength.search("  barbell_back_squat ", 10).status()).isEqualTo("ok");
        assertThat(strength.search("SUSPENSION/PLANK", 1).status()).isEqualTo("ok");
        assertThat(strength.search("PLANK", 1).status()).isEqualTo("truncated");
        assertThat(strength.search("SQUAT À LA BARRE", 1).status()).isEqualTo("truncated");
        assertThat(strength.search("exercice inexistant", 10).status()).isEqualTo("no_data");
    }

    @Test
    void previewsAllSetsAndRestsInOrderWithoutCheckingThePrivateAccount() {
        var result = strength.create("Séance", List.of(
                new Block("SQUAT", "BARBELL_BACK_SQUAT", 2, 8, null, new BigDecimal("62.5"), 90, 45),
                new Block("SQUAT", "BARBELL_BACK_SQUAT", 1, 6, null, new BigDecimal("65.0"), null, 120),
                new Block("PUSH_UP", "PUSH_UP", 2, 12, null, null, 60, null),
                new Block("PLANK", "PLANK", 1, null, 30, null, null, null)), false, workouts);
        assertThat(result.status()).isEqualTo("preview");
        var plan = (Plan) result.data();
        assertThat(plan.steps()).extracting(step -> step.get("kind"))
                .containsExactly("set", "rest", "set", "rest", "set", "rest", "set", "rest", "set", "set");
        assertThat(plan.steps().stream().filter(step -> step.get("kind").equals("rest"))
                .map(step -> step.get("durationSeconds"))).containsExactly(90, 45, 120, 60);
        assertThat(plan.steps().get(0)).containsEntry("weightKg", new BigDecimal("62.5"));
        assertThat(plan.steps().get(4)).containsEntry("weightKg", new BigDecimal("65"));
        assertThat(plan.steps().getLast()).containsEntry("durationSeconds", 30).containsEntry("weightKg", null);
        verifyNoInteractions(activities, publisher);
    }

    @Test
    void rejectsWrongPairsAndUnexecutablePlansBeforeWriting() {
        var wrong = new Block("PLANK", "BARBELL_BACK_SQUAT", 1, 8, null, null, null, null);
        assertThat(strength.create("Séance", List.of(wrong), true, workouts)).isEqualTo(
                new ToolResult("invalid_input", Map.of("exerciseIndex", 1, "reason", "unknown_exercise",
                        "category", "PLANK", "exerciseName", "BARBELL_BACK_SQUAT")));
        var invalid = List.of(
                new Block("PLANK", "PLANK", 1, 8, 30, null, null, null),
                new Block("PLANK", "PLANK", 1, null, 30, null, null, 1),
                new Block("PLANK", "PLANK", 1, null, 30, null, 1, null),
                new Block("PLANK", "PLANK", 26, null, 30, null, 1, null),
                new Block("PLANK", "PLANK", Integer.MAX_VALUE, null, 30, null, null, null),
                new Block("PLANK", "PLANK", 1, null, 30, new BigDecimal("453.501"), null, null));
        for (var block : invalid) {
            assertThat(strength.create("Séance", List.of(block), true, workouts).status()).isEqualTo("invalid_input");
        }
        verifyNoInteractions(activities, publisher);
    }
}
