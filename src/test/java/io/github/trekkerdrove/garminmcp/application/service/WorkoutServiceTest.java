package io.github.trekkerdrove.garminmcp.application.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import io.github.trekkerdrove.garminmcp.application.dto.GarminFailure;
import io.github.trekkerdrove.garminmcp.application.dto.WorkoutDraft;
import io.github.trekkerdrove.garminmcp.application.dto.WorkoutDraft.Kind;
import io.github.trekkerdrove.garminmcp.application.dto.WorkoutDraft.Sport;
import io.github.trekkerdrove.garminmcp.application.dto.WorkoutDraft.Step;
import io.github.trekkerdrove.garminmcp.application.port.out.ActivityReader;
import io.github.trekkerdrove.garminmcp.application.port.out.ActivityReader.SearchView;
import io.github.trekkerdrove.garminmcp.application.port.out.WorkoutPublisher;
import io.github.trekkerdrove.garminmcp.application.port.out.WorkoutPublisher.Operation;
import io.github.trekkerdrove.garminmcp.application.dto.ToolResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WorkoutServiceTest {
    private final ActivityReader activities = mock(ActivityReader.class);
    private final WorkoutPublisher publisher = mock(WorkoutPublisher.class);
    private final WorkoutService service = new WorkoutService(activities, publisher);
    private final WorkoutDraft draft = new WorkoutDraft("Test", Sport.running, List.of(
            new Step(Kind.warmup, 120, null, null), new Step(Kind.interval, null, 400, 3)));

    @Test
    void previewsWithoutReadingEitherGarminApi() {
        var result = service.create(draft, false);
        assertThat(result.status()).isEqualTo("preview");
        assertThat(result.data()).isEqualTo(draft);
        verifyNoInteractions(activities, publisher);
    }

    @Test
    void rejectsAmbiguousStepsAndMalformedWorkouts() {
        assertThatThrownBy(() -> new Step(Kind.interval, 60, 400, 3)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Step(Kind.interval, null, 400, 6)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WorkoutDraft(" ", Sport.walking, draft.steps()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validatesChangesAndTargetsBeforeContactingGarmin() {
        assertThatThrownBy(() -> service.update(17, null, null, true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.update(17, null, List.of(), true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.delete(0, true)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(activities, publisher);
    }

    @Test
    void verifiedAccountActivityIsRequiredBeforePublishing() {
        when(activities.search(Map.of(), 0, 1, SearchView.SUMMARY)).thenReturn(List.of());
        assertThatThrownBy(() -> service.create(draft, true)).isInstanceOf(GarminFailure.class)
                .hasMessage("workout_account_unverified");
        verifyNoInteractions(publisher);
    }

    @Test
    void publishesAndReportsAReusedWorkoutUnderTheSameId() {
        var activity = new ActivityReader.Activity(42, null, LocalDate.of(2026, 11, 20), "running",
                Map.of(), Map.of(), Map.of(), new ActivityReader.Selection(List.of(), false), Map.of(), Map.of());
        when(activities.search(Map.of(), 0, 1, SearchView.SUMMARY)).thenReturn(List.of(activity));
        var reused = new ToolResult("already_exists", Map.of("workoutId", 17, "name", "Test", "reused", true));
        when(publisher.publish(Operation.create, Map.of("workout", draft), 42)).thenReturn(
                new ToolResult("created", Map.of("workoutId", 17, "reused", false)), reused);

        assertThat(service.create(draft, true).status()).isEqualTo("created");
        var replay = service.create(draft, true);
        assertThat(replay.status()).isEqualTo("already_exists");
        assertThat(replay).isEqualTo(reused);
    }
}
