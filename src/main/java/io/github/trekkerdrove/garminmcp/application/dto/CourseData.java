package io.github.trekkerdrove.garminmcp.application.dto;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

public final class CourseData {
    public static final int MAX_SOURCE_BYTES = 4 * 1024 * 1024;
    public static final int MAX_HTTP_BYTES = 8 * 1024 * 1024;
    public static final int MAX_EXPORT_BYTES = 8 * 1024 * 1024;
    public static final int MAX_POINTS = 50_000;
    public static final int MAX_COURSE_POINTS = 500;

    private CourseData() {}

    public enum Format {
        gpx("application/gpx+xml"), tcx("application/vnd.garmin.tcx+xml"),
        fit("application/octet-stream"), geojson("application/geo+json");

        private final String mimeType;

        Format(String mimeType) {
            this.mimeType = mimeType;
        }

        public String mimeType() {
            return mimeType;
        }
    }

    public record Point(Double latitude, Double longitude, @Nullable Double elevationMeters, @Nullable String timestamp) {
        public Point {
            if (latitude == null || !Double.isFinite(latitude) || Math.abs(latitude) > 90
                    || longitude == null || !Double.isFinite(longitude) || Math.abs(longitude) > 180
                    || elevationMeters != null && !Double.isFinite(elevationMeters)) {
                throw new IllegalArgumentException("Invalid course coordinate");
            }
            if (latitude == 0) latitude = 0d;
            if (longitude == 0) longitude = 0d;
            timestamp = canonicalTimestamp(timestamp);
        }
    }

    public record NavigationPoint(String name, String type, @Nullable Double latitude, @Nullable Double longitude,
                                  @Nullable Double distanceMeters, @Nullable String timestamp) {
        public NavigationPoint {
            if (latitude != null && latitude == 0) latitude = 0d;
            if (longitude != null && longitude == 0) longitude = 0d;
            if (distanceMeters != null && distanceMeters == 0) distanceMeters = 0d;
            timestamp = canonicalTimestamp(timestamp);
        }
    }

    private static String canonicalTimestamp(String value) {
        return value == null ? null : OffsetDateTime.parse(value).toInstant().toString();
    }

    public record Metrics(Double distanceMeters, Double ascentMeters, Double descentMeters,
                          Double elevationCoverage, String source) {}

    public record Trace(List<List<Point>> segments, List<NavigationPoint> coursePoints,
                        String name, String sport, String description, String source,
                        Metrics metrics, List<String> warnings, List<String> losses) {
        public Trace {
            segments = segments.stream().map(List::copyOf).toList();
            coursePoints = List.copyOf(coursePoints);
            warnings = List.copyOf(warnings);
            losses = List.copyOf(losses);
        }

        public int pointCount() {
            return segments.stream().mapToInt(List::size).sum();
        }
    }

    public record Content(String name, String sport, String description, String privacy,
                          List<List<Point>> segments, List<NavigationPoint> coursePoints) {
        public Content {
            segments = segments.stream().map(List::copyOf).toList();
            coursePoints = List.copyOf(coursePoints);
        }
    }

    public record Summary(long courseId, String name, String sport, String privacy,
                          Double distanceMeters, Double ascentMeters, Double descentMeters,
                          Instant modifiedAt) {}

    public record Snapshot(Summary summary, Content content, String version, String settingsVersion, String navigationVersion,
                           List<String> related, List<String> warnings) {
        public Snapshot {
            related = List.copyOf(related);
            warnings = List.copyOf(warnings);
        }
    }

    public record Library(List<Summary> items, boolean complete) {
        public Library {
            items = List.copyOf(items);
        }
    }

    public record Source(String kind, @Nullable Format format, @Nullable String fileName, @Nullable String text,
                         @Nullable String contentBase64, @Nullable Integer selectionIndex, @Nullable List<Point> points,
                         @Nullable Map<String, Object> geometry, @Nullable Long activityId, @Nullable String planId) {}

    public record Changes(@Nullable String name, @Nullable String description, @Nullable String sport,
                          @Nullable Source source, @Nullable List<NavigationPoint> coursePoints,
                          @Nullable Boolean allowDataLoss) {}

    public record PlanRequest(String sport, String shape, Object start, List<Object> via, Object finish,
                              Double targetDistanceMeters, Double distanceToleranceMeters,
                              List<String> avoid, Integer alternatives) {}

    public record Route(Trace trace, Point snappedStart, Double snapDistanceMeters) {}

    public record Export(byte[] bytes, String origin, List<String> warnings) {}

    public static final class Problem extends RuntimeException {
        private final String status;
        private final Object details;

        public Problem(String status) {
            this(status, null);
        }

        public Problem(String status, Object details) {
            super(status);
            this.status = status;
            this.details = details;
        }

        public String status() { return status; }
        public Object details() { return details; }
    }
}
