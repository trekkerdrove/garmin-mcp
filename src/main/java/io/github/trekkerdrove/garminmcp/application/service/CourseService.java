package io.github.trekkerdrove.garminmcp.application.service;

import io.github.trekkerdrove.garminmcp.application.dto.CourseData.*;
import io.github.trekkerdrove.garminmcp.application.dto.CourseGeometry;
import io.github.trekkerdrove.garminmcp.application.dto.GarminFailure;
import io.github.trekkerdrove.garminmcp.application.dto.ToolResult;
import io.github.trekkerdrove.garminmcp.application.port.out.ActivityReader;
import io.github.trekkerdrove.garminmcp.application.port.out.CourseCodec;
import io.github.trekkerdrove.garminmcp.application.port.out.CourseLibrary;
import io.github.trekkerdrove.garminmcp.application.port.out.CourseLibrary.Mutation;
import io.github.trekkerdrove.garminmcp.application.port.out.CourseRouter;
import io.github.trekkerdrove.garminmcp.application.port.out.GarminArchive;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.GeneralSecurityException;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import static io.github.trekkerdrove.garminmcp.application.dto.CourseData.*;

public final class CourseService {
    private static final Duration TTL = Duration.ofMinutes(15);
    private static final Duration RECOVERY = Duration.ofMinutes(60);
    private static final Duration CANDIDATE_LOOKUP = Duration.ofSeconds(15);
    private static final int EXPORT_WINDOW = 256 * 1024;
    private static final int MAX_CACHE_BYTES = 48 * 1024 * 1024;
    private static final int MAX_CACHE_ITEMS = 128;
    private static final int MAX_CANDIDATES = 16;
    private static final int MAX_LIBRARY_SIZE = 2_000;
    private static final Set<String> SPORTS = Set.of("running", "trail_running", "hiking", "road_biking",
            "mountain_biking", "gravel_cycling", "obstacle_run", "other");
    private static final Comparator<Summary> ORDER = Comparator.comparing(Summary::modifiedAt,
            Comparator.nullsLast(Comparator.reverseOrder())).thenComparing(Summary::courseId, Comparator.reverseOrder());
    private final CourseLibrary library;
    private final ActivityReader activities;
    private final GarminArchive archive;
    private final CourseCodec codec;
    private final Clock clock;
    private final Semaphore writing = new Semaphore(1);
    private final Semaphore processing = new Semaphore(2);
    private final Map<String, Cached> cache = new LinkedHashMap<>();
    private final byte[] cursorKey = token().getBytes(StandardCharsets.UTF_8);
    private int cacheBytes;

    public CourseService(CourseLibrary library, ActivityReader activities, GarminArchive archive, CourseCodec codec, Clock clock) {
        this.library = library;
        this.activities = activities;
        this.archive = archive;
        this.codec = codec;
        this.clock = clock;
    }

    public enum Action { create, update, duplicate, delete }

    private abstract static class Cached {
        private Instant expiresAt;
        private final int bytes;

        Cached(Instant expiresAt, int bytes) {
            this.expiresAt = expiresAt;
            this.bytes = bytes;
        }

        final Instant expiry() {
            return expiresAt;
        }
    }

    private static final class Operation extends Cached {
        final String id;
        final Action action;
        final long account;
        final Snapshot current;
        final Content target;
        final Set<String> fields;
        final Object preview;
        ToolResult result;
        Set<Long> priorIds = Set.of();
        Long candidate;
        Instant nextAttempt;
        boolean active;
        boolean dispatched;

        Operation(String id, Action action, long account, Snapshot current, Content target,
                  Set<String> fields, Object preview, Instant expiresAt, int bytes) {
            super(expiresAt, bytes);
            this.id = id;
            this.action = action;
            this.account = account;
            this.current = current;
            this.target = target;
            this.fields = Set.copyOf(fields);
            this.preview = preview;
        }

        long courseId() {
            return current == null ? 0 : current.summary().courseId();
        }
    }

    private static final class Plan extends Cached {
        final Trace trace;

        Plan(Trace trace, Instant expiresAt) {
            super(expiresAt, traceBytes(trace.segments(), trace.coursePoints()));
            this.trace = trace;
        }
    }

    private static final class Listing extends Cached {
        final String query;
        final String sport;
        final List<Summary> rows;
        final boolean complete;

        Listing(String query, String sport, List<Summary> rows, boolean complete, Instant expiresAt) {
            super(expiresAt, 512 * rows.size());
            this.query = query;
            this.sport = sport;
            this.rows = rows;
            this.complete = complete;
        }
    }

    private static final class ExportFile extends Cached {
        final Export export;

        ExportFile(Export export, Instant expiresAt) {
            super(expiresAt, export.bytes().length);
            this.export = export;
        }
    }

    public ToolResult plan(CourseRouter router, PlanRequest request) {
        return bounded(() -> {
            List<Route> routes = router.route(request);
            if (routes.isEmpty()) throw new Problem("route_not_found");
            var items = new ArrayList<Map<String, Object>>();
            var plans = new LinkedHashMap<String, Cached>();
            Instant expiresAt = now().plus(TTL);
            Double target = request.targetDistanceMeters();
            Double tolerance = request.distanceToleranceMeters() != null ? request.distanceToleranceMeters()
                    : target == null ? null : target / 10;
            boolean within = target == null;
            for (Route route : routes.stream().limit(3).toList()) {
                Trace trace = route.trace();
                CourseGeometry.continuous(trace.segments());
                String id = token();
                plans.put(id, new Plan(trace, expiresAt));
                Double difference = target == null ? null : trace.metrics().distanceMeters() - target;
                boolean met = difference == null || Math.abs(difference) <= tolerance;
                within |= met;
                items.add(values("planId", id, "expiresAt", expiresAt, "sport", trace.sport(), "source", trace.source(),
                        "metrics", trace.metrics(), "distanceDifferenceMeters", difference,
                        "status", met ? "ok" : "target_not_met", "map", CourseGeometry.simplified(trace.segments(), 150),
                        "snappedStart", route.snappedStart(), "snapDistanceMeters", route.snapDistanceMeters(),
                        "warnings", trace.warnings()));
            }
            putAll(plans);
            return new ToolResult(within ? "ok" : "target_not_met", values("items", items));
        });
    }

    public ToolResult list(String query, String sport, Integer limit, String cursorId) {
        return bounded(() -> {
            int size = integer(limit, 20, 1, 50);
            String filter = search(query);
            String sportFilter = sport == null ? null : sport(sport);
            List<Summary> rows;
            boolean complete;
            int offset;
            String listingId;
            if (cursorId != null) {
                var cursor = parseCursor(cursorId);
                listingId = cursor[0];
                offset = Integer.parseInt(cursor[1]);
                var listing = get(listingId, Listing.class, "course_cursor_invalid");
                if (!Objects.equals(filter, listing.query) || !Objects.equals(sportFilter, listing.sport)
                        || offset <= 0 || offset >= listing.rows.size()) {
                    throw new Problem("course_cursor_invalid");
                }
                rows = listing.rows;
                complete = listing.complete;
            } else {
                var read = library.list(account());
                rows = read.items().stream().filter(row -> filter == null || row.name() != null
                                && search(row.name()).contains(filter))
                        .filter(row -> sportFilter == null || Objects.equals(sportFilter, row.sport()))
                        .sorted(ORDER).limit(MAX_LIBRARY_SIZE).toList();
                complete = read.complete() && read.items().size() <= MAX_LIBRARY_SIZE;
                offset = 0;
                listingId = token();
                if (rows.size() > size) put(listingId, new Listing(filter, sportFilter, rows, complete, now().plus(TTL)));
            }
            int next = Math.min(rows.size(), offset + size);
            String nextCursor = null;
            if (next < rows.size()) {
                String position = listingId + "." + next;
                nextCursor = position + "." + cursorSignature(position);
            }
            var data = values("items", rows.subList(offset, next).stream().map(CourseService::summary).toList(),
                    "nextCursor", nextCursor, "complete", complete);
            return new ToolResult(!complete ? "truncated" : rows.isEmpty() ? "no_data" : "ok", data);
        });
    }

    private String[] parseCursor(String cursor) {
        if (cursor.length() > 110) throw new Problem("course_cursor_invalid");
        var parts = cursor.split("\\.", -1);
        if (parts.length != 3 || !parts[1].matches("[0-9]{1,4}") || !MessageDigest.isEqual(
                cursorSignature(parts[0] + "." + parts[1]).getBytes(StandardCharsets.UTF_8),
                parts[2].getBytes(StandardCharsets.UTF_8))) throw new Problem("course_cursor_invalid");
        return parts;
    }

    private String cursorSignature(String position) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(cursorKey, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(position.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public ToolResult getCourse(Long courseId, String view, Integer offset, Integer limit, String expectedRevision) {
        return bounded(() -> {
            validateId(courseId);
            String requested = view == null ? "summary" : view;
            var course = requireCourse(account(), courseId);
            var data = summary(course.summary());
            if ("summary".equals(requested)) return ToolResult.ok(data);
            String revision = revision(course);
            var segments = course.content().segments();
            var metrics = CourseGeometry.metrics(segments);
            if ("detail".equals(requested)) {
                data.putAll(values("description", course.content().description(), "revision", revision,
                        "geometry", values("pointCount", count(segments), "segmentCount", segments.size(), "metrics", metrics),
                        "coursePoints", course.content().coursePoints(), "related", course.related(), "warnings", course.warnings()));
                return ToolResult.ok(data);
            }
            if (!"geometry".equals(requested)) throw new Problem("invalid_input");
            int from = integer(offset, 0, 0, MAX_POINTS);
            int size = integer(limit, 500, 1, 500);
            if (from > 0 && expectedRevision == null) throw new Problem("invalid_input", "revision is required after the first page.");
            if (expectedRevision != null && !revision.equals(expectedRevision)) throw new Problem("course_changed");
            if (segments.isEmpty()) throw new Problem("course_no_geometry");
            var points = segments.stream().flatMap(List::stream).toList();
            if (from > points.size()) throw new Problem("invalid_input");
            int end = Math.min(points.size(), from + size);
            var segmentStarts = new ArrayList<Integer>();
            int position = 0;
            for (var segment : segments) {
                if (position >= from && position < end) segmentStarts.add(position);
                position += segment.size();
            }
            return ToolResult.ok(values("courseId", courseId, "revision", revision, "offset", from,
                    "points", points.subList(from, end), "segmentStarts", segmentStarts, "totalPoints", points.size(),
                    "nextOffset", end == points.size() ? null : end));
        });
    }

    public ToolResult prepare(Action action, Long courseId, Changes changes) {
        return bounded(() -> prepareWrite(action, courseId, changes));
    }

    private ToolResult prepareWrite(Action action, Long courseId, Changes changes) {
        var capabilities = library.capabilities();
        if (action == Action.create || action == Action.duplicate) {
            if (!capabilities.create()) throw new Problem("course_edit_unsupported");
        }
        if (action == Action.delete && !capabilities.delete()) throw new Problem("course_edit_unsupported");
        if (action != Action.create) validateId(courseId);
        long account = account();
        Snapshot current = action == Action.create ? null : library.get(account, courseId).orElse(null);
        if (current == null && action != Action.create) {
            if (action == Action.delete) return new ToolResult("already_absent", values("courseId", courseId));
            throw new Problem("course_not_found");
        }

        if (action == Action.delete) {
            return preview(action, account, current, null, Set.of(), values("course", summary(current.summary()),
                    "revision", revision(current), "irreversible", true, "related", current.related()));
        }
        if (changes == null) throw new Problem("invalid_input");
        Trace trace = changes.source() == null ? null : source(changes.source());
        if (action == Action.create && trace == null) throw new Problem("invalid_input");
        Content original = current == null ? null : current.content();
        boolean update = action == Action.update;
        boolean copy = action == Action.duplicate;
        var warnings = new ArrayList<String>();
        var losses = new ArrayList<String>();
        if (trace != null) {
            warnings.addAll(trace.warnings());
            losses.addAll(trace.losses());
        }
        if (copy) {
            warnings.add("The copy has its own identifier and does not transfer Garmin associations or sharing.");
            if (!current.related().isEmpty()) losses.add("Garmin associations omitted: " + String.join(", ", current.related()));
            losses.addAll(current.warnings());
        }
        String inheritedName = original != null ? original.name() : nonblank(trace.name(), fileName(changes.source()));
        String name = update && changes.name() == null ? original.name()
                : checkedText(changes.name() == null && !copy ? inheritedName : changes.name(), 128, true);
        if (copy && search(name).equals(search(original.name()))) throw new Problem("invalid_input", "Choose a different name for the copy.");
        String requestedSport = changes.sport();
        if (requestedSport == null) requestedSport = original == null ? trace.sport() : original.sport();
        if (requestedSport == null && !update) {
            requestedSport = "running";
            warnings.add("No reliable source sport was supplied. The preview uses running.");
        }
        String sport = requestedSport == null ? null : sport(requestedSport);
        if (sport != null && !sport.equals(requestedSport)) {
            warnings.add("Sport " + requestedSport + " maps to " + sport + ".");
        }
        if ((!update || changes.sport() != null) && !capabilities.sports().contains(sport)) {
            throw new Problem("course_edit_unsupported", values("sport", sport, "supportedSports", capabilities.sports()));
        }
        String description = changes.description() != null ? checkedText(changes.description(), 512, false)
                : original == null ? checkedText(trace.description(), 512, false) : original.description();
        String privacy = update ? original.privacy() : "private";
        List<List<Point>> segments = trace == null ? original.segments() : trace.segments();
        List<NavigationPoint> supplied = changes.coursePoints() != null ? changes.coursePoints()
                : original != null ? original.coursePoints() : trace.coursePoints();
        if (trace != null && changes.coursePoints() != null && !trace.coursePoints().isEmpty()) {
            warnings.add("The supplied navigation points replace the source navigation points.");
        }
        boolean replacing = update && trace != null;
        if ((!update || replacing) && segments.size() > 1) {
            warnings.add("Boundaries between contiguous segments are flattened by Garmin.");
        }
        if (replacing && !trace.coursePoints().isEmpty() && changes.coursePoints() == null) {
            losses.add("Navigation points carried by the replacement source require an explicit coursePoints list.");
        }
        List<NavigationPoint> points = supplied;
        if (!update || trace != null || changes.coursePoints() != null) {
            var line = CourseGeometry.continuous(segments);
            if (replacing || !update) segments = List.of(line);
            if (supplied.size() > MAX_COURSE_POINTS) throw new Problem("course_source_too_large");
            var anchors = new CourseGeometry.Anchors(line);
            var projected = new ArrayList<NavigationPoint>();
            for (var point : supplied) {
                if (point == null) throw new Problem("invalid_input");
                if (changes.coursePoints() != null) validateNavigation(point);
                boolean relocate = replacing && changes.coursePoints() == null;
                var resolved = anchors.resolve(point, relocate);
                if (resolved == null || !capabilities.pointTypes().contains(resolved.type())) {
                    losses.add("Navigation point " + (point.name() == null ? "(unnamed)" : checkedText(point.name(), 128, false))
                            + " has an unsupported type or an ambiguous or missing anchor.");
                } else {
                    checkedText(resolved.name(), 128, true);
                    if (relocate && !Objects.equals(point.distanceMeters(), resolved.distanceMeters())) {
                        warnings.add("Navigation point " + resolved.name() + " will be anchored at " + resolved.distanceMeters() + " m.");
                    }
                    projected.add(new NavigationPoint(resolved.name(), resolved.type(), resolved.latitude(),
                            resolved.longitude(), resolved.distanceMeters(), millisecondTime(resolved.timestamp(), losses)));
                }
            }
            points = projected;
        }
        if (!update || replacing) {
            segments = segments.stream().map(segment -> segment.stream().map(point -> new Point(
                    point.latitude(), point.longitude(), point.elevationMeters(), millisecondTime(point.timestamp(), losses))).toList()).toList();
            if (segments.stream().flatMap(List::stream).anyMatch(point -> point.elevationMeters() == null)) {
                warnings.add("Garmin may supplement missing elevations.");
            }
        }
        var target = new Content(name, sport, emptyAsNull(description), privacy, segments, points);
        var diff = update ? changes(original, target) : Map.<String, Object>of();
        if (update && diff.isEmpty()) return new ToolResult("unchanged", summary(current.summary()));
        if (update && !capabilities.editFields().containsAll(diff.keySet())) {
            throw new Problem("course_edit_unsupported", values("requestedFields", diff.keySet(), "supportedFields", capabilities.editFields()));
        }
        if (update && diff.containsKey("coursePoints")) losses.addAll(current.warnings());
        if (update && privacy == null) throw new Problem("course_edit_unsupported", "Garmin privacy could not be verified.");

        Library siblings = update ? null : library.list(account);
        var sameName = siblings == null ? List.<Long>of() : siblings.items().stream()
                .filter(row -> Objects.equals(search(row.name()), search(name))).map(Summary::courseId).limit(50).toList();
        if (siblings != null && !siblings.complete()) warnings.add("The owner library read is incomplete.");
        var preview = values("name", name, "requestedSport", requestedSport, "sport", sport, "privacy", privacy,
                "description", description, "source", trace == null ? "garmin" : trace.source(),
                "metrics", trace == null ? CourseGeometry.metrics(segments) : trace.metrics(),
                "map", CourseGeometry.simplified(segments, 150), "pointCount", count(segments),
                "coursePoints", points, "supportedPointTypes", capabilities.pointTypes().stream().sorted().toList(),
                "warnings", warnings, "losses", losses, "homonymCourseIds", sameName);
        if (current != null) {
            preview.put(copy ? "sourceCourseId" : "courseId", current.summary().courseId());
            preview.put("revision", revision(current));
        }
        if (update) preview.put("diff", diff);
        if (!losses.isEmpty() && !Boolean.TRUE.equals(changes.allowDataLoss())) {
            return new ToolResult("course_data_loss", preview);
        }
        return preview(action, account, current, target, diff.keySet(), preview);
    }

    private static void validateNavigation(NavigationPoint point) {
        checkedText(point.name(), 128, true);
        if (point.type() == null || point.type().isBlank()
                || (point.latitude() == null) != (point.longitude() == null)
                || point.latitude() == null && point.distanceMeters() == null
                || point.distanceMeters() != null && (!Double.isFinite(point.distanceMeters()) || point.distanceMeters() < 0)) {
            throw new Problem("invalid_input", "A navigation point requires a type and valid coordinates or distance.");
        }
        if (point.latitude() != null) new Point(point.latitude(), point.longitude(), null, null);
    }

    private ToolResult preview(Action action, long account, Snapshot current, Content target,
                               Set<String> fields, Map<String, Object> data) {
        String id = token();
        Instant expires = now().plus(TTL);
        data.put("operationId", id);
        data.put("expiresAt", expires);
        int bytes = 4096 + (current == null ? 0 : contentBytes(current.content()))
                + (target == null ? 0 : contentBytes(target));
        put(id, new Operation(id, action, account, current, target, fields, data, expires, bytes));
        return new ToolResult("preview", data);
    }

    public ToolResult confirm(Action action, String id) {
        Operation op;
        synchronized (cache) {
            op = get(id, Operation.class, "operation_expired");
            if (op.action != action) throw new Problem("invalid_input");
            if (op.active) return state(op, "in_progress", null);
            if (op.result != null && (op.nextAttempt == null || now().isBefore(op.nextAttempt))) return op.result;
            var pending = conflictingOperation(op);
            if (pending != null) return state(op, "course_busy", values("pendingOperationId", pending.id));
            if (!writing.tryAcquire()) return state(op, "course_busy", null);
            op.active = true;
            op.dispatched = false;
            op.nextAttempt = null;
            ((Cached) op).expiresAt = now().plus(RECOVERY);
        }
        try {
            ToolResult result;
            try {
                result = execute(op);
            } catch (Problem failure) {
                if (!op.dispatched && Set.of("garmin_rate_limited", "course_busy").contains(failure.status())) {
                    Integer delay = failure.details() instanceof Map<?, ?> details
                            && details.get("retryAfterSeconds") instanceof Integer seconds ? seconds : null;
                    result = retry(op, failure.status(), delay, failure.details());
                } else {
                    result = state(op, failure.status(), failure.details());
                }
            } catch (GarminFailure failure) {
                result = !op.dispatched && "garmin_unavailable".equals(failure.status())
                        ? retry(op, failure.status(), null, null) : state(op, failure.status(), null);
            } catch (RuntimeException failure) {
                result = state(op, op.dispatched ? "course_write_unconfirmed" : "garmin_unavailable", null);
            }
            synchronized (cache) {
                op.result = result;
                return result;
            }
        } finally {
            synchronized (cache) {
                op.active = false;
                writing.release();
            }
        }
    }

    private Operation conflictingOperation(Operation next) {
        for (var item : cache.values()) {
            if (!(item instanceof Operation pending) || pending == next || !pending.dispatched
                    || pending.result == null || !"course_write_unconfirmed".equals(pending.result.status())) continue;
            boolean matchingCreation = createsCourse(next) && createsCourse(pending) && next.target.equals(pending.target);
            Long pendingId = knownId(pending);
            boolean sameCourse = !createsCourse(next) && pendingId != null && next.courseId() == pendingId;
            if (matchingCreation || sameCourse) return pending;
        }
        return null;
    }

    private static boolean createsCourse(Operation op) {
        return op.action == Action.create || op.action == Action.duplicate;
    }

    private ToolResult execute(Operation op) {
        if (op.current != null) {
            var live = library.get(op.account, op.courseId());
            if (live.isEmpty()) return state(op, op.action == Action.delete ? "already_absent" : "course_not_found", null);
            if (!revision(live.get()).equals(revision(op.current))) return state(op, "course_changed", null);
        }
        if (createsCourse(op)) {
            long deadline = System.nanoTime() + CANDIDATE_LOOKUP.toNanos();
            var before = completeLibrary(op.account, deadline);
            var matching = candidates(op, before, false, deadline);
            if (matching.size() == 1) return saved(op, "already_exists", matching.getFirst());
            if (matching.size() > 1) throw new Problem("course_busy", "Several existing courses match.");
            op.priorIds = before.items().stream().map(Summary::courseId).collect(java.util.stream.Collectors.toUnmodifiableSet());
        }
        Mutation mutation;
        try {
            op.dispatched = true;
            mutation = switch (op.action) {
                case create, duplicate -> library.create(op.account, op.target);
                case update -> library.update(op.account, op.current, op.target, op.fields);
                case delete -> library.delete(op.account, op.current);
            };
        } catch (GarminFailure failure) {
            if (!"course_write_unconfirmed".equals(failure.status())) throw failure;
            return verify(op);
        }
        if (mutation.courseId() != null) op.candidate = mutation.courseId();
        return switch (mutation.status()) {
            case "accepted", "course_write_unconfirmed" -> verify(op);
            case "course_not_ready", "garmin_rate_limited" -> retry(op, mutation.status(), mutation.retryAfterSeconds(), null);
            default -> state(op, mutation.status(), null);
        };
    }

    private ToolResult retry(Operation op, String status, Integer seconds, Object details) {
        int delay = seconds == null ? 2 : Math.max(0, seconds);
        synchronized (cache) {
            op.nextAttempt = now().plusSeconds(delay);
        }
        return state(op, status, values("retryAfterSeconds", delay, "details", details instanceof Map<?, ?> ? null : details));
    }

    public ToolResult operation(String id) {
        Operation op;
        synchronized (cache) {
            op = get(id, Operation.class, "operation_expired");
            if (op.active) return state(op, "in_progress", null);
            if (op.result == null) return new ToolResult("preview", op.preview);
            if (!op.dispatched || !"course_write_unconfirmed".equals(op.result.status()) || !writing.tryAcquire()) return op.result;
            op.active = true;
        }
        try {
            var result = verify(op);
            synchronized (cache) {
                op.result = result;
                return result;
            }
        } finally {
            synchronized (cache) {
                op.active = false;
                writing.release();
            }
        }
    }

    private ToolResult verify(Operation op) {
        try {
            if (op.action == Action.delete) {
                if (library.verifyAbsent(op.account, op.courseId())) {
                    return state(op, "deleted", values("courseId", op.courseId()));
                }
            } else if (op.action == Action.update || op.candidate != null) {
                long id = op.action == Action.update ? op.courseId() : op.candidate;
                if (!createsCourse(op) || !op.priorIds.contains(id)) {
                    var course = library.get(op.account, id);
                    if (course.isPresent() && equal(op.target, course.get().content(), true,
                            op.action != Action.update || op.fields.contains("source"))
                            && (op.action != Action.update || preservesFields(op, course.get()))) {
                        return saved(op, op.action == Action.update ? "updated" : "created", course.get());
                    }
                }
            } else {
                long deadline = System.nanoTime() + CANDIDATE_LOOKUP.toNanos();
                var matching = candidates(op, completeLibrary(op.account, deadline), true, deadline);
                if (matching.size() == 1) return saved(op, "created", matching.getFirst());
            }
        } catch (Problem | GarminFailure failedRead) {
            return state(op, "course_write_unconfirmed", values("courseId", knownId(op)));
        }
        return state(op, "course_write_unconfirmed", values("courseId", knownId(op)));
    }

    private static boolean preservesFields(Operation op, Snapshot after) {
        return op.current.related().equals(after.related())
                && Objects.equals(op.current.settingsVersion(), after.settingsVersion())
                && (op.fields.contains("coursePoints") || Objects.equals(op.current.navigationVersion(), after.navigationVersion()));
    }

    private static Long knownId(Operation op) {
        return op.candidate != null ? op.candidate : op.current == null || op.action == Action.duplicate ? null : op.courseId();
    }

    private List<Snapshot> candidates(Operation op, Library read, boolean reconcile, long deadline) {
        var rows = read.items().stream().filter(row -> Objects.equals(row.name(), op.target.name()))
                .filter(row -> op.action != Action.duplicate || row.courseId() != op.courseId())
                .filter(row -> !reconcile || !op.priorIds.contains(row.courseId())).toList();
        if (rows.size() > MAX_CANDIDATES) throw new Problem("course_busy", "Too many identically named courses to verify.");
        var matches = new ArrayList<Snapshot>();
        for (var row : rows) {
            var course = library.get(op.account, row.courseId(), remaining(deadline)).orElseThrow(
                    () -> new Problem("course_busy", "The owner library changed while checking an equivalent course."));
            if (equal(op.target, course.content(), reconcile, reconcile)) matches.add(course);
        }
        remaining(deadline);
        return matches;
    }

    private static boolean equal(Content expected, Content actual, boolean readBack, boolean supplementElevation) {
        if (Objects.equals(expected, actual)) return true;
        if (!readBack || !Objects.equals(expected.name(), actual.name()) || !Objects.equals(expected.sport(), actual.sport())
                || !Objects.equals(expected.privacy(), actual.privacy())
                || !Objects.equals(emptyAsNull(expected.description()), emptyAsNull(actual.description()))
                || !expected.coursePoints().equals(actual.coursePoints())
                || expected.segments().size() != actual.segments().size()) return false;
        for (int i = 0; i < expected.segments().size(); i++) {
            var before = expected.segments().get(i);
            var after = actual.segments().get(i);
            if (before.size() != after.size()) return false;
            for (int j = 0; j < before.size(); j++) {
                Point point = before.get(j);
                Point saved = after.get(j);
                if (Double.compare(point.latitude(), saved.latitude()) != 0
                        || Double.compare(point.longitude(), saved.longitude()) != 0
                        || (!supplementElevation || point.elevationMeters() != null)
                            && !Objects.equals(point.elevationMeters(), saved.elevationMeters())
                        || !Objects.equals(point.timestamp(), saved.timestamp())) return false;
            }
        }
        return true;
    }

    private ToolResult saved(Operation op, String status, Snapshot course) {
        var data = summary(course.summary());
        data.put("revision", revision(course));
        if (op.action == Action.duplicate) data.put("sourceCourseId", op.courseId());
        return state(op, status, data);
    }

    private ToolResult state(Operation op, String status, Object details) {
        var data = values("operationId", op.id, "action", op.action, "expiresAt", op.expiry());
        if (details instanceof Map<?, ?> map) map.forEach((key, value) -> data.put(String.valueOf(key), value));
        else if (details != null) data.put("details", details);
        return new ToolResult(status, data);
    }

    public ToolResult export(Long courseId, Format format, Integer offsetBytes, String hash) {
        return bounded(() -> {
            validateId(courseId);
            if (format == null) throw new Problem("invalid_input");
            int offset = integer(offsetBytes, 0, 0, MAX_EXPORT_BYTES);
            if (offset > 0 && (hash == null || !hash.matches("[a-f0-9]{64}"))) throw new Problem("course_export_changed");
            ExportFile file;
            String sha;
            if (offset > 0) {
                sha = hash;
                file = get(exportKey(courseId, format, sha), ExportFile.class, "course_export_changed");
                requireCourse(account(), courseId);
            } else {
                long account = account();
                var course = requireCourse(account, courseId);
                Export output = nativeExport(account, course, format).orElseGet(() -> codec.reconstruct(course, format));
                sha = digest(output.bytes());
                if (hash != null && !sha.equals(hash)) throw new Problem("course_export_changed");
                file = new ExportFile(output, now().plus(TTL));
                put(exportKey(courseId, format, sha), file);
            }
            byte[] bytes = file.export.bytes();
            if (offset >= bytes.length) throw new Problem("invalid_input");
            int end = Math.min(offset + EXPORT_WINDOW, bytes.length);
            return ToolResult.ok(values("courseId", courseId, "link", link(courseId), "fileName", "garmin-course-" + courseId + "." + format,
                    "format", format, "mimeType", format.mimeType(), "origin", file.export.origin(), "warnings", file.export.warnings(),
                    "totalBytes", bytes.length, "sha256", sha, "offsetBytes", offset, "nextOffsetBytes", end == bytes.length ? null : end,
                    "expiresAt", file.expiry(), "contentBase64", Base64.getEncoder().encodeToString(Arrays.copyOfRange(bytes, offset, end))));
        });
    }

    private Optional<Export> nativeExport(long account, Snapshot course, Format format) {
        if (format != Format.gpx && format != Format.fit || !library.capabilities().nativeExports().contains(format)) return Optional.empty();
        Optional<byte[]> bytes;
        try {
            bytes = library.export(account, course.summary().courseId(), format);
        } catch (GarminFailure failure) {
            if (!"course_response_too_large".equals(failure.status())) throw failure;
            return Optional.empty();
        }
        if (bytes.isEmpty()) return Optional.empty();
        try {
            var decoded = codec.readNativeExport(format, bytes.get());
            var warnings = new ArrayList<>(decoded.losses());
            warnings.addAll(course.warnings());
            if (!decoded.coursePoints().equals(course.content().coursePoints())) {
                warnings.add("Garmin export navigation points differ from the course detail.");
            }
            if (!decoded.segments().equals(course.content().segments())) {
                warnings.add("Garmin export geometry or point measurements differ from the course detail.");
            }
            return Optional.of(new Export(bytes.get(), "garmin_export", warnings));
        } catch (Problem | IllegalArgumentException | java.time.DateTimeException invalid) {
            return Optional.empty();
        }
    }

    private static String exportKey(long courseId, Format format, String sha) {
        return courseId + ":" + format + ":" + sha;
    }

    private Trace source(Source source) {
        if (source.kind() == null) throw new Problem("invalid_input");
        var supplied = new HashSet<String>();
        if (source.format() != null) supplied.add("format");
        if (source.fileName() != null) supplied.add("fileName");
        if (source.text() != null) supplied.add("text");
        if (source.contentBase64() != null) supplied.add("contentBase64");
        if (source.selectionIndex() != null) supplied.add("selectionIndex");
        if (source.points() != null) supplied.add("points");
        if (source.geometry() != null) supplied.add("geometry");
        if (source.activityId() != null) supplied.add("activityId");
        if (source.planId() != null) supplied.add("planId");
        return switch (source.kind()) {
            case "plan" -> {
                fields(supplied, "planId");
                if (source.planId() == null) throw new Problem("invalid_input");
                yield get(source.planId(), Plan.class, "plan_expired").trace;
            }
            case "points" -> {
                fields(supplied, "points");
                if (source.points() == null || source.points().stream().anyMatch(Objects::isNull)) throw new Problem("invalid_input");
                var segments = List.of(source.points());
                yield new Trace(segments, List.of(), null, null, null, "client_points", CourseGeometry.metrics(segments), List.of(), List.of());
            }
            case "geojson" -> {
                fields(supplied, "geometry");
                if (source.geometry() == null) throw new Problem("invalid_input");
                yield codec.readGeoJson(source.geometry());
            }
            case "activity" -> {
                fields(supplied, "activityId");
                validateId(source.activityId());
                try {
                    yield codec.readActivity(archive.activityArchive(source.activityId(), MAX_SOURCE_BYTES));
                } catch (GarminFailure failure) {
                    throw new Problem("fit_limit_exceeded".equals(failure.status()) ? "course_source_too_large" : failure.status());
                }
            }
            case "file" -> {
                fields(supplied, "format", "fileName", "text", "contentBase64", "selectionIndex");
                Format format = source.format();
                if (format == null || (source.text() == null) == (source.contentBase64() == null)
                        || format == Format.fit && source.text() != null) throw new Problem("invalid_input");
                if (source.fileName() != null && !source.fileName().toLowerCase(Locale.ROOT).endsWith("." + format)) {
                    throw new Problem("invalid_input", "The file extension must agree with the format.");
                }
                byte[] bytes;
                if (source.text() != null) {
                    if (source.text().length() > MAX_SOURCE_BYTES) throw new Problem("course_source_too_large");
                    bytes = source.text().getBytes(StandardCharsets.UTF_8);
                } else {
                    if (source.contentBase64().length() > 4 * ((MAX_SOURCE_BYTES + 2) / 3)) {
                        throw new Problem("course_source_too_large");
                    }
                    bytes = Base64.getDecoder().decode(source.contentBase64());
                }
                yield codec.readFile(format, bytes, source.selectionIndex());
            }
            default -> throw new Problem("invalid_input");
        };
    }

    private static void fields(Set<String> supplied, String... allowed) {
        if (!Set.of(allowed).containsAll(supplied)) throw new Problem("invalid_input");
    }

    private static Map<String, Object> changes(Content before, Content after) {
        var changed = new LinkedHashMap<String, Object>();
        diff(changed, "name", before.name(), after.name());
        diff(changed, "description", emptyAsNull(before.description()), emptyAsNull(after.description()));
        diff(changed, "sport", before.sport(), after.sport());
        diff(changed, "source", before.segments().equals(after.segments()) ? null : values("pointCount", count(before.segments())),
                before.segments().equals(after.segments()) ? null : values("pointCount", count(after.segments()), "replacement", true));
        diff(changed, "coursePoints", before.coursePoints(), after.coursePoints());
        return changed;
    }

    private static void diff(Map<String, Object> changes, String name, Object before, Object after) {
        if (!Objects.equals(before, after)) changes.put(name, values("before", before, "after", after));
    }

    private static Map<String, Object> summary(Summary row) {
        return values("courseId", row.courseId(), "name", row.name(), "sport", row.sport(), "privacy", row.privacy(),
                "distanceMeters", row.distanceMeters(), "ascentMeters", row.ascentMeters(), "descentMeters", row.descentMeters(),
                "metricsSource", "garmin", "modifiedAt", row.modifiedAt(), "link", link(row.courseId()));
    }

    private static String link(long id) {
        return "https://connect.garmin.com/modern/course/" + id;
    }

    private static String revision(Snapshot course) {
        return digest((course.summary().courseId() + "|" + course.version() + "|" + course.content())
                .getBytes(StandardCharsets.UTF_8));
    }

    private static String digest(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private Snapshot requireCourse(long account, long id) {
        return library.get(account, id).orElseThrow(() -> new Problem("course_not_found"));
    }

    private Library completeLibrary(long account, long deadline) {
        var read = library.list(account, remaining(deadline));
        if (!read.complete() || read.items().size() > MAX_LIBRARY_SIZE) {
            throw new Problem("course_busy", "A complete owner library is required to verify a creation.");
        }
        return read;
    }

    private static Duration remaining(long deadline) {
        long nanos = deadline - System.nanoTime();
        if (nanos <= 0) throw new Problem("course_busy", "The equivalent-course lookup timed out.");
        return Duration.ofNanos(nanos);
    }

    private long account() {
        var recent = activities.search(Map.of(), 0, 1, ActivityReader.SearchView.SUMMARY);
        if (recent.isEmpty() || recent.getFirst().id() <= 0) throw new Problem("course_account_unverified");
        return recent.getFirst().id();
    }

    private <T> T bounded(Supplier<T> work) {
        if (!processing.tryAcquire()) throw new Problem("course_busy");
        try {
            return work.get();
        } finally {
            processing.release();
        }
    }

    private void put(String id, Cached item) {
        putAll(Map.of(id, item));
    }

    private void putAll(Map<String, Cached> additions) {
        synchronized (cache) {
            clean();
            int bytes = cacheBytes;
            int size = cache.size();
            for (var entry : additions.entrySet()) {
                var old = cache.get(entry.getKey());
                if (old == null) size++;
                bytes += entry.getValue().bytes - (old == null ? 0 : old.bytes);
            }
            if (size > MAX_CACHE_ITEMS || bytes > MAX_CACHE_BYTES) {
                throw new Problem("course_busy");
            }
            cache.putAll(additions);
            cacheBytes = bytes;
        }
    }

    private <T extends Cached> T get(String id, Class<T> type, String expired) {
        synchronized (cache) {
            clean();
            if (!type.isInstance(cache.get(id))) throw new Problem(expired);
            return type.cast(cache.get(id));
        }
    }

    private void clean() {
        var iterator = cache.values().iterator();
        Instant now = now();
        while (iterator.hasNext()) {
            var item = iterator.next();
            if (!now.isBefore(item.expiresAt) && (!(item instanceof Operation op) || !op.active)) {
                cacheBytes -= item.bytes;
                iterator.remove();
            }
        }
    }

    private Instant now() {
        return clock.instant();
    }

    private static String token() {
        return UUID.randomUUID().toString();
    }

    private static int contentBytes(Content content) {
        return traceBytes(content.segments(), content.coursePoints());
    }

    private static int traceBytes(List<List<Point>> segments, List<NavigationPoint> points) {
        return count(segments) * 224 + points.size() * 768;
    }

    private static int count(List<List<Point>> segments) {
        return segments.stream().mapToInt(List::size).sum();
    }

    private static String fileName(Source source) {
        if (source.fileName() == null) return null;
        return source.fileName().substring(0, source.fileName().lastIndexOf('.'));
    }

    private static String nonblank(String first, String fallback) {
        return first == null || first.isBlank() ? fallback : first;
    }

    private static String emptyAsNull(String text) {
        return text == null || text.isEmpty() ? null : text;
    }

    private static String millisecondTime(String value, List<String> losses) {
        if (value == null) return null;
        var source = Instant.parse(value);
        var projected = source.truncatedTo(ChronoUnit.MILLIS);
        if (!source.equals(projected)) {
            String loss = "Garmin stores timestamps to millisecond precision.";
            if (!losses.contains(loss)) losses.add(loss);
        }
        return projected.toString();
    }

    private static String checkedText(String text, int limit, boolean required) {
        if (text == null || required && text.isBlank()) {
            if (required) throw new Problem("invalid_input", "A course name is required.");
            return null;
        }
        if (text.codePointCount(0, text.length()) > limit || text.codePoints().anyMatch(c -> c < 32 && c != 9 && c != 10
                && c != 13 || c >= 0xD800 && c <= 0xDFFF || c == 0xFFFE || c == 0xFFFF)) throw new Problem("invalid_input");
        return required ? text.strip() : text;
    }

    private static String sport(String requested) {
        String value = requested.strip().toLowerCase(Locale.ROOT);
        if ("walking".equals(value)) return "hiking";
        if ("cycling".equals(value)) return "road_biking";
        if (!SPORTS.contains(value)) throw new Problem("invalid_input", values("sports", SPORTS));
        return value;
    }

    private static String search(String text) {
        return text == null ? null : Normalizer.normalize(text.strip(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT);
    }

    private static int integer(Integer value, int fallback, int min, int max) {
        if (value == null) return fallback;
        if (value < min || value > max) throw new Problem("invalid_input");
        return value;
    }

    private static void validateId(Long id) {
        if (id == null || id <= 0) throw new Problem("invalid_input");
    }

    private static Map<String, Object> values(Object... pairs) {
        var map = new LinkedHashMap<String, Object>();
        for (int i = 0; i < pairs.length; i += 2) map.put((String) pairs[i], pairs[i + 1]);
        return map;
    }
}
