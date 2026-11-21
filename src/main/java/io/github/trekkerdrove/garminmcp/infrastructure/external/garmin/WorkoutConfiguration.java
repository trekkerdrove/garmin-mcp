package io.github.trekkerdrove.garminmcp.infrastructure.external.garmin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import io.github.trekkerdrove.garminmcp.application.port.out.ActivityReader;
import io.github.trekkerdrove.garminmcp.application.port.out.WorkoutPublisher;
import io.github.trekkerdrove.garminmcp.application.service.CreateWorkout;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "garmin.workouts.enabled", havingValue = "true")
class WorkoutConfiguration {
    @Bean
    WorkoutPublisher workoutPublisher(
            @Value("${garmin.workouts.python}") String python,
            @Value("${garmin.workouts.script}") String script,
            @Value("${garmin.workouts.token-directory}") String tokenDirectory,
            @Value("${garmin.workouts.session-file}") String sessionFile) throws IOException {
        if (tokenDirectory.isBlank() == sessionFile.isBlank()) {
            throw new IllegalArgumentException("Set exactly one Garmin Connect session source.");
        }
        Path directory = sessionFile.isBlank() ? Path.of(tokenDirectory) : privateCopy(Path.of(sessionFile));
        return new PythonWorkoutPublisher(Path.of(python), Path.of(script), directory);
    }

    @Bean
    CreateWorkout createWorkout(ActivityReader activities, WorkoutPublisher publisher) {
        return new CreateWorkout(activities, publisher);
    }

    private static Path privateCopy(Path sessionFile) throws IOException {
        // The worker needs to save refreshed tokens even when the host mounts its secret read-only.
        var directory = Files.createTempDirectory("garmin-connect-",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        directory.toFile().deleteOnExit();
        var token = Files.createFile(directory.resolve("garmin_tokens.json"),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        token.toFile().deleteOnExit();
        try (var input = Files.newInputStream(sessionFile)) {
            byte[] seed = input.readNBytes(32_769);
            if (seed.length == 0 || seed.length > 32_768) {
                throw new IOException("Invalid Garmin Connect session file size.");
            }
            Files.write(token, seed);
        }
        return directory;
    }
}
