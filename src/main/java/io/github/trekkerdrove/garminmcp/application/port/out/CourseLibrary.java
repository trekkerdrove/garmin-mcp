package io.github.trekkerdrove.garminmcp.application.port.out;

import io.github.trekkerdrove.garminmcp.application.dto.CourseData.*;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;

public interface CourseLibrary {
    record Capabilities(Set<String> sports, Set<String> editFields, Set<String> pointTypes,
                        boolean create, boolean delete, Set<Format> nativeExports) {}
    record Mutation(String status, Long courseId, Integer retryAfterSeconds) {}

    Capabilities capabilities();
    Library list(long accountActivityId);
    default Library list(long accountActivityId, Duration timeout) {
        return list(accountActivityId);
    }
    Optional<Snapshot> get(long accountActivityId, long courseId);
    default Optional<Snapshot> get(long accountActivityId, long courseId, Duration timeout) {
        return get(accountActivityId, courseId);
    }
    boolean verifyAbsent(long accountActivityId, long previouslyOwnedCourseId);
    Mutation create(long accountActivityId, Content content);
    Mutation update(long accountActivityId, Snapshot current, Content target, Set<String> fields);
    Mutation delete(long accountActivityId, Snapshot current);
    Optional<byte[]> export(long accountActivityId, long courseId, Format format);
}
