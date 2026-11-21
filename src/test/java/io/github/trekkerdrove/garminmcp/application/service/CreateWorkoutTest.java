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
import io.github.trekkerdrove.garminmcp.application.port.out.WorkoutPublisher.PublishedWorkout;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CreateWorkoutTest {
    private final ActivityReader activities = mock(ActivityReader.class);
    private final WorkoutPublisher publisher = mock(WorkoutPublisher.class);
    private final CreateWorkout service = new CreateWorkout(activities, publisher);
    private final WorkoutDraft draft = new WorkoutDraft("Test", Sport.running, List.of(
            new Step(Kind.warmup, 120, null, null), new Step(Kind.interval, null, 400, 3)));

    @Test
    void previewsWithoutReadingEitherGarminApi() {
        var result = service.execute(draft, false);
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
    void verifiedAccountActivityIsRequiredBeforePublishing() {
        when(activities.search(Map.of(), 0, 1, SearchView.SUMMARY)).thenReturn(List.of());
        assertThatThrownBy(() -> service.execute(draft, true)).isInstanceOf(GarminFailure.class)
                .hasMessage("workout_account_unverified");
        verifyNoInteractions(publisher);
    }

    @Test
    void publishesAndReportsAReusedWorkoutUnderTheSameId() {
        var activity = new ActivityReader.Activity(42, null, LocalDate.of(2026, 11, 20), "running",
                Map.of(), Map.of(), Map.of(), new ActivityReader.Selection(List.of(), false), Map.of(), Map.of());
        when(activities.search(Map.of(), 0, 1, SearchView.SUMMARY)).thenReturn(List.of(activity));
        when(publisher.publish(draft, 42)).thenReturn(new PublishedWorkout(17, "Test", false),
                new PublishedWorkout(17, "Test", true));

        assertThat(service.execute(draft, true).status()).isEqualTo("created");
        var replay = service.execute(draft, true);
        assertThat(replay.status()).isEqualTo("already_exists");
        assertThat(replay.data()).isEqualTo(new PublishedWorkout(17, "Test", true));
    }
}
