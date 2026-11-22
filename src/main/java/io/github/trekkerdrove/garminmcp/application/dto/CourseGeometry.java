package io.github.trekkerdrove.garminmcp.application.dto;

import io.github.trekkerdrove.garminmcp.application.dto.CourseData.*;
import java.util.ArrayList;
import java.util.List;

public final class CourseGeometry {
    private static final double EARTH_RADIUS = 6_371_008.8;

    private CourseGeometry() {}

    public static double distance(Point from, Point to) {
        double lat = Math.toRadians(to.latitude() - from.latitude());
        double lon = Math.toRadians(longitudeDelta(to.longitude() - from.longitude()));
        double h = Math.pow(Math.sin(lat / 2), 2) + Math.cos(Math.toRadians(from.latitude()))
                * Math.cos(Math.toRadians(to.latitude())) * Math.pow(Math.sin(lon / 2), 2);
        return 2 * EARTH_RADIUS * Math.asin(Math.sqrt(Math.min(1, h)));
    }

    private static double longitudeDelta(double longitude) {
        return Math.IEEEremainder(longitude, 360);
    }

    public static Metrics metrics(List<List<Point>> segments) {
        int count = 0;
        int elevations = 0;
        double distance = 0;
        double ascent = 0;
        double descent = 0;
        for (var segment : segments) {
            Point previous = null;
            for (Point point : segment) {
                if (++count > CourseData.MAX_POINTS) throw new Problem("course_source_too_large");
                if (point.elevationMeters() != null) elevations++;
                if (previous != null) {
                    distance += distance(previous, point);
                    if (point.elevationMeters() != null && previous.elevationMeters() != null) {
                        double difference = point.elevationMeters() - previous.elevationMeters();
                        ascent += Math.max(0, difference);
                        descent += Math.max(0, -difference);
                    }
                }
                previous = point;
            }
        }
        return new Metrics(distance, count > 1 && count == elevations && Double.isFinite(ascent) ? ascent : null,
                count > 1 && count == elevations && Double.isFinite(descent) ? descent : null,
                count == 0 ? null : (double) elevations / count, "trace");
    }

    public static List<Point> continuous(List<List<Point>> segments) {
        var points = new ArrayList<Point>();
        for (var segment : segments) {
            if (segment.isEmpty()) continue;
            if (!points.isEmpty() && distance(points.getLast(), segment.getFirst()) > 0) {
                throw new Problem("course_no_geometry", "Separate segments cannot be joined for Garmin.");
            }
            points.addAll(segment);
        }
        if (metrics(segments).distanceMeters() <= 0) throw new Problem("course_no_geometry");
        return List.copyOf(points);
    }

    public static List<Point> simplified(List<List<Point>> segments, int limit) {
        var points = segments.stream().flatMap(List::stream).toList();
        if (points.size() <= limit) return points;
        var sample = new ArrayList<Point>(limit);
        for (int i = 0; i < limit; i++) {
            sample.add(points.get((int) ((long) i * (points.size() - 1) / (limit - 1))));
        }
        return List.copyOf(sample);
    }

    public static final class Anchors {
        private final List<Point> points;
        private final double[] offsets;

        public Anchors(List<Point> points) {
            this.points = points;
            offsets = new double[points.size()];
            for (int i = 1; i < points.size(); i++) {
                offsets[i] = offsets[i - 1] + distance(points.get(i - 1), points.get(i));
            }
        }

        public NavigationPoint resolve(NavigationPoint point, boolean relocated) {
            if (point.name() == null || point.name().isBlank() || point.type() == null) return null;
            if ((point.latitude() == null) != (point.longitude() == null)) return null;
            if (point.distanceMeters() != null && (!Double.isFinite(point.distanceMeters())
                    || point.distanceMeters() < 0)) return null;
            String timestamp = point.timestamp();
            Point position = point.latitude() == null ? null : new Point(point.latitude(), point.longitude(), null, null);
            Double offset = relocated ? null : point.distanceMeters();
            if (offset != null && offset > offsets[offsets.length - 1]) return null;
            if (offset == null && timestamp != null) {
                for (int i = 0; i < points.size(); i++) {
                    if (timestamp.equals(points.get(i).timestamp())
                            && (position == null || distance(position, points.get(i)) <= 20)) {
                        if (offset != null && Math.abs(offset - offsets[i]) > 1) return null;
                        offset = offsets[i];
                    }
                }
            }
            if (offset == null && position != null) offset = nearest(position);
            if (offset == null) return null;
            Point anchor = at(offset);
            if (position != null && distance(position, anchor) > 20) return null;
            return new NavigationPoint(point.name(), point.type(), position == null ? anchor.latitude() : position.latitude(),
                    position == null ? anchor.longitude() : position.longitude(), offset, timestamp);
        }

        private Double nearest(Point position) {
            double best = Double.POSITIVE_INFINITY;
            double offset = 0;
            boolean ambiguous = false;
            for (int i = 1; i < points.size(); i++) {
                Point from = points.get(i - 1);
                Point to = points.get(i);
                double scale = Math.cos(Math.toRadians(position.latitude()));
                double x = longitudeDelta(to.longitude() - from.longitude()) * scale;
                double y = to.latitude() - from.latitude();
                double px = longitudeDelta(position.longitude() - from.longitude()) * scale;
                double py = position.latitude() - from.latitude();
                double squared = x * x + y * y;
                double fraction = squared == 0 ? 0 : Math.clamp((px * x + py * y) / squared, 0, 1);
                double gap = distance(position, interpolate(from, to, fraction));
                double along = offsets[i - 1] + (offsets[i] - offsets[i - 1]) * fraction;
                if (gap < best - 1) {
                    best = gap;
                    offset = along;
                    ambiguous = false;
                } else if (Math.abs(gap - best) <= 1 && Math.abs(along - offset) > 20) {
                    ambiguous = true;
                }
            }
            return best <= 20 && !ambiguous ? offset : null;
        }

        private Point at(double offset) {
            int index = java.util.Arrays.binarySearch(offsets, offset);
            if (index >= 0) return points.get(index);
            int next = -index - 1;
            return interpolate(points.get(next - 1), points.get(next),
                    (offset - offsets[next - 1]) / (offsets[next] - offsets[next - 1]));
        }

        private static Point interpolate(Point from, Point to, double ratio) {
            return new Point(from.latitude() + (to.latitude() - from.latitude()) * ratio,
                    longitudeDelta(from.longitude() + longitudeDelta(to.longitude() - from.longitude()) * ratio),
                    null, null);
        }
    }
}
