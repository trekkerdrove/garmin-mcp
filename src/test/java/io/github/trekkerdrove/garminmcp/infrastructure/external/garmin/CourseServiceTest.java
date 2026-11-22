package io.github.trekkerdrove.garminmcp.infrastructure.external.garmin;

import com.garmin.fit.BufferEncoder;
import com.garmin.fit.CourseMesg;
import com.garmin.fit.CoursePoint;
import com.garmin.fit.CoursePointMesg;
import com.garmin.fit.DateTime;
import com.garmin.fit.File;
import com.garmin.fit.FileIdMesg;
import com.garmin.fit.Fit;
import com.garmin.fit.RecordMesg;
import com.garmin.fit.Sport;
import com.garmin.fit.SubSport;
import io.github.trekkerdrove.garminmcp.application.dto.CourseData.*;
import io.github.trekkerdrove.garminmcp.application.dto.CourseGeometry;
import io.github.trekkerdrove.garminmcp.application.dto.ToolResult;
import io.github.trekkerdrove.garminmcp.application.port.out.ActivityReader;
import io.github.trekkerdrove.garminmcp.application.port.out.CourseLibrary;
import io.github.trekkerdrove.garminmcp.application.service.CourseService;
import io.github.trekkerdrove.garminmcp.application.service.CourseService.Action;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CourseServiceTest {
    private static final Instant START = Instant.parse("2026-04-02T18:00:00Z");
    private static final String GPX = """
            <gpx xmlns="http://www.topografix.com/GPX/1/1" version="1.1" creator="test">
              <wpt lat="47" lon="2"><name>Eau fraîche</name><type>water</type></wpt>
              <trk><name>Crête</name><type>trail_running</type><trkseg>
                <trkpt lat="47" lon="2"><ele>0</ele><time>2026-04-02T20:00:00+02:00</time></trkpt>
                <trkpt lat="47.01" lon="2.01"><ele>20</ele><time>2026-04-02T18:10:00Z</time></trkpt>
              </trkseg></trk></gpx>
            """;
    private static final String TCX = """
            <TrainingCenterDatabase xmlns="http://www.garmin.com/xmlschemas/TrainingCenterDatabase/v2">
              <Courses><Course><Name>Vallon</Name>
                <Track><Trackpoint><Time>2026-04-02T18:00:00Z</Time><Position>
                  <LatitudeDegrees>47</LatitudeDegrees><LongitudeDegrees>2</LongitudeDegrees>
                </Position><AltitudeMeters>10</AltitudeMeters></Trackpoint>
                <Trackpoint><Time>2026-04-02T18:10:00Z</Time><Position>
                  <LatitudeDegrees>47.01</LatitudeDegrees><LongitudeDegrees>2.01</LongitudeDegrees>
                </Position></Trackpoint></Track>
                <CoursePoint><Name>Danger</Name><Time>2026-04-02T18:10:00Z</Time><Position>
                 <LatitudeDegrees>47.01</LatitudeDegrees><LongitudeDegrees>2.01</LongitudeDegrees>
                </Position><PointType>Danger</PointType></CoursePoint>
              </Course></Courses>
            </TrainingCenterDatabase>
            """;
    private static final List<Point> LINE = List.of(new Point(47.0, 2.0, 0.0, null), new Point(47.01, 2.01, 15.0, null));
    private final CourseFormats formats = new CourseFormats();
    private final FixtureLibrary library = new FixtureLibrary();
    private final CourseService service = service(library, formats);

    @Test
    void previewsAndConfirmsAllSourceKindsWithoutLosingGeometry() throws Exception {
        var route = new Trace(List.of(LINE), List.of(), null, "trail_running", null, "openrouteservice",
                new Metrics(1_500d, 15d, 0d, 1d, "openrouteservice"), List.of(), List.of());
        var plan = service.plan(request -> List.of(new Route(route, LINE.getFirst(), 15d)),
                new PlanRequest("trail_running", "loop", Map.of(), List.of(), null, 2_000d, 100d, List.of(), 1));
        assertThat(plan.status()).isEqualTo("target_not_met");
        String planId = (String) ((List<Map<String, Object>>) data(plan).get("items")).getFirst().get("planId");

        var sources = List.of(file(Format.gpx, GPX), file(Format.gpx,
                        "<gpx version=\"1.0\"><rte><rtept lat=\"47\" lon=\"2\"/><rtept lat=\"47.01\" lon=\"2\"/></rte></gpx>"),
                file(Format.tcx, TCX), new Source("file", Format.fit, "course.fit", null,
                        Base64.getEncoder().encodeToString(fit(File.COURSE)), null, null, null, null, null),
                new Source("geojson", null, null, null, null, null, null,
                        Map.of("type", "LineString", "coordinates", List.of(List.of(2, 47, 0), List.of(2.01, 47.01))), null, null),
                points(LINE), new Source("activity", null, null, null, null, null, null, null, 42L, null),
                new Source("plan", null, null, null, null, null, null, null, null, planId));
        for (int i = 0; i < sources.size(); i++) {
            var preview = service.prepare(Action.create, null, new Changes("Parcours " + i, null, null, sources.get(i), null, false));
            assertThat(preview.status()).as("source %s: %s", i, preview).isEqualTo("preview");
            assertThat(library.created).hasValue(i);
            String operationId = operationId(preview);
            var created = service.confirm(Action.create, operationId);
            assertThat(created.status()).isEqualTo("created");
            var record = library.rows.get((Long) data(created).get("courseId"));
            assertThat(record.content().segments().getFirst()).hasSize(2);
            assertThat(record.content().privacy()).isEqualTo("private");
            assertThat(data(created).get("link")).asString().endsWith("/" + record.summary().courseId());
            assertThat(service.confirm(Action.create, operationId)).isEqualTo(created);
        }
        assertThat(library.created).hasValue(sources.size());
    }

    @Test
    void validatesFormatsSegmentsAndLossesBeforeGrantingAToken() {
        var gpx = formats.readFile(Format.gpx, GPX.getBytes(StandardCharsets.UTF_8), null);
        assertThat(gpx.segments().getFirst().getFirst().elevationMeters()).isZero();
        assertThat(gpx.metrics().ascentMeters()).isEqualTo(20);
        assertThat(gpx.coursePoints().getFirst().name()).isEqualTo("Eau fraîche");
        var tcx = formats.readFile(Format.tcx, TCX.getBytes(StandardCharsets.UTF_8), null);
        assertThat(tcx.metrics().ascentMeters()).isNull();
        assertThat(tcx.metrics().elevationCoverage()).isEqualTo(0.5);
        assertThatThrownBy(() -> formats.readFile(Format.gpx, ("""
                <!DOCTYPE gpx [<!ENTITY x SYSTEM "file:///etc/passwd">]>
                <gpx version="1.1"><trk><name>&x;</name></trk></gpx>
                """).getBytes(StandardCharsets.UTF_8), null)).hasMessage("invalid_input");
        byte[] corrupt = fit(File.COURSE);
        corrupt[corrupt.length - 1] ^= 1;
        assertThatThrownBy(() -> formats.readFile(Format.fit, corrupt, null)).hasMessage("invalid_input");
        assertThatThrownBy(() -> formats.readFile(Format.fit, fit(File.WORKOUT), null)).hasMessage("invalid_input");
        assertThatThrownBy(() -> formats.readFile(Format.gpx, new byte[4 * 1024 * 1024 + 1], null)).hasMessage("course_source_too_large");
        String multiple = "<gpx version=\"1.1\"><rte><name>One</name><rtept lat=\"1\" lon=\"1\"/><rtept lat=\"2\" lon=\"2\"/></rte>"
                + "<rte><name>Two</name><rtept lat=\"1\" lon=\"1\"/><rtept lat=\"2\" lon=\"2\"/></rte></gpx>";
        assertThatThrownBy(() -> formats.readFile(Format.gpx, multiple.getBytes(StandardCharsets.UTF_8), null))
                .isInstanceOfSatisfying(Problem.class, problem -> assertThat(problem.details().toString()).contains("One", "Two"));
        assertThat(formats.readFile(Format.gpx, multiple.getBytes(StandardCharsets.UTF_8), 1).name()).isEqualTo("Two");

        var gap = new Source("geojson", null, null, null, null, null, null,
                Map.of("type", "MultiLineString", "coordinates",
                        List.of(List.of(List.of(2, 47), List.of(2, 48)), List.of(List.of(2, 49), List.of(2, 50)))), null, null);
        assertThatThrownBy(() -> service.prepare(Action.create, null, new Changes("Gap", null, null, gap, null, true)))
                .hasMessage("course_no_geometry");
        var unsupported = List.of(new NavigationPoint("Unverified", "unknown", null, null, 0d, null));
        var request = new Changes("Loop", null, null, points(LINE), unsupported, false);
        var loss = service.prepare(Action.create, null, request);
        assertThat(loss.status()).isEqualTo("course_data_loss");
        assertThat(data(loss)).doesNotContainKey("operationId");
        assertThat(service.prepare(Action.create, null, new Changes("Loop", null, null, points(LINE), unsupported, true)).status())
                .isEqualTo("preview");
        assertThat(library.created).hasValue(0);
    }

    @Test
    void renamesCopiesPagesAndDeletesAnExactRevision() throws Exception {
        var line = new ArrayList<Point>();
        for (int i = 0; i < 10_000; i++) line.add(new Point(47.0, 2.0 + i / 100_000.0, 10.0, START.plusSeconds(i).toString()));
        var preview = service.prepare(Action.create, null, new Changes("Crête", "Conservée", "hiking", points(line),
                List.of(new NavigationPoint("Eau", "water", null, null, 0d, START.toString())), false));
        var created = service.confirm(Action.create, operationId(preview));
        long id = (long) data(created).get("courseId");
        var detail = service.getCourse(id, "detail", null, null, null);
        var oldRevision = (String) data(detail).get("revision");
        var first = service.getCourse(id, "geometry", null, 2, null);
        assertThat(data(first)).containsEntry("nextOffset", 2);
        assertThatThrownBy(() -> service.getCourse(id, "geometry", 2, 2, null)).hasMessage("invalid_input");

        var pendingDelete = service.prepare(Action.delete, id, null);
        var rename = service.prepare(Action.update, id, new Changes("Crête Est", null, null, null, null, null));
        var updated = service.confirm(Action.update, operationId(rename));
        assertThat(updated.status()).isEqualTo("updated");
        assertThat(data(updated)).containsEntry("courseId", id);
        assertThat(library.rows.get(id).content().description()).isEqualTo("Conservée");
        assertThat(library.rows.get(id).content().segments().getFirst()).containsExactlyElementsOf(line);
        assertThat(service.confirm(Action.delete, operationId(pendingDelete)).status()).isEqualTo("course_changed");
        assertThatThrownBy(() -> service.getCourse(id, "geometry", 2, 2, oldRevision)).hasMessage("course_changed");

        var duplicate = service.prepare(Action.duplicate, id, new Changes("Crête Ouest", null, null, null, null, null));
        var copy = service.confirm(Action.duplicate, operationId(duplicate));
        assertThat(copy.status()).isEqualTo("created");
        assertThat(data(copy)).containsEntry("sourceCourseId", id);
        assertThat(data(copy).get("courseId")).isNotEqualTo(id);

        var results = service.list("crete", "hiking", 1, null);
        String cursor = (String) data(results).get("nextCursor");
        assertThat(cursor).isNotBlank();
        assertThatThrownBy(() -> service.list("east", "hiking", 1, cursor)).hasMessage("course_cursor_invalid");
        assertThat(data(service.list("CRÊTE", "hiking", 1, cursor)).get("nextCursor")).isNull();

        var bytes = new ByteArrayOutputStream();
        var page = data(service.export(id, Format.geojson, null, null));
        String sha = (String) page.get("sha256");
        assertThat(page.get("nextOffsetBytes")).isNotNull();
        assertThatThrownBy(() -> service.export(id, Format.geojson, 256 * 1024, "0".repeat(64))).hasMessage("course_export_changed");
        while (true) {
            bytes.write(Base64.getDecoder().decode((String) page.get("contentBase64")));
            var next = (Integer) page.get("nextOffsetBytes");
            if (next == null) break;
            page = data(service.export(id, Format.geojson, next, sha));
        }
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()))).isEqualTo(sha);
        assertThat(formats.readGeoJson(tools.jackson.databind.json.JsonMapper.builder().build().readValue(bytes.toByteArray(),
                new tools.jackson.core.type.TypeReference<Map<String, Object>>() {})).segments().getFirst()).isEqualTo(line);

        var delete = service.prepare(Action.delete, id, null);
        assertThat(service.confirm(Action.delete, operationId(delete)).status()).isEqualTo("deleted");
        assertThat(service.prepare(Action.delete, id, null).status()).isEqualTo("already_absent");
        assertThat(library.rows).doesNotContainKey(id);
    }

    @Test
    void replacesTheTraceOnTheSameIdAndRequiresExplicitAcceptanceForLostAnchors() {
        var newCourse = service.prepare(Action.create, null, new Changes("Crête", "Ancienne", "hiking", points(LINE),
                List.of(new NavigationPoint("Eau", "water", null, null, 0d, null)), false));
        long id = (long) data(service.confirm(Action.create, operationId(newCourse))).get("courseId");
        var targetLine = List.of(LINE.getFirst(), new Point(47.02, 2.01, 22.0, null));
        var replacement = points(targetLine);
        var edited = service.prepare(Action.update, id, new Changes(null, "", null, replacement, null, false));
        assertThat(data(edited)).containsEntry("name", "Crête");
        assertThat(service.confirm(Action.update, operationId(edited)).status()).isEqualTo("updated");
        var content = library.rows.get(id).content();
        assertThat(content.segments()).containsExactly(targetLine);
        assertThat(content.sport()).isEqualTo("hiking");
        assertThat(content.description()).isNull();
        assertThat(content.coursePoints()).extracting(NavigationPoint::name).containsExactly("Eau");

        var elsewhere = points(List.of(new Point(42.0, 2.0, 0.0, null), new Point(42.02, 2.01, 22.0, null)));
        var loss = service.prepare(Action.update, id, new Changes(null, null, null, elsewhere, null, false));
        assertThat(loss.status()).isEqualTo("course_data_loss");
        var clear = service.prepare(Action.update, id, new Changes(null, null, null, elsewhere, List.of(), false));
        assertThat(service.confirm(Action.update, operationId(clear)).status()).isEqualTo("updated");
        assertThat(library.rows.get(id).content().coursePoints()).isEmpty();
    }

    @Test
    void canonicalizesTimestampedNavigationReadBackFromWireJsonAndAnchorsSignedZero() {
        library.wireNavigation = JsonMapper.builder().build().readValue("""
                {"name":"Eau","type":"water","latitude":0,"longitude":0,"distanceMeters":0,
                 "timestamp":"2026-04-02T18:00:00.123000+00:00"}
                """, NavigationPoint.class);
        var geometry = points(List.of(new Point(-0.0, -0.0, null, "2026-04-02T18:00:00.123Z"), LINE.getLast()));
        var coursePoints = List.of(new NavigationPoint("Eau", "water", -0.0, -0.0, -0.0, "2026-04-02T20:00:00.123+02:00"));
        var preview = service.prepare(Action.create, null, new Changes("Loop", null, null, geometry, coursePoints, false));
        assertThat(service.confirm(Action.create, operationId(preview)).status()).isEqualTo("created");
    }

    @Test
    void traversesTheFullOwnerLibraryWithOneItemPerPage() {
        for (long id = 1; id <= 2_000; id++) library.store(id, content("Loop", List.of(LINE)));
        String cursor = null;
        for (long id = 2_000; id >= 1; id--) {
            var page = data(service.list(null, null, 1, cursor));
            var item = (Map<?, ?>) ((List<?>) page.get("items")).getFirst();
            assertThat(item.get("courseId")).isEqualTo(id);
            cursor = (String) page.get("nextCursor");
        }
        assertThat(cursor).isNull();
        assertThat(library.listReads).hasValue(1);
    }

    @Test
    void validatesNativeExportsAboveTheImportLimitAndFallsBackForInvalidBytes() {
        library.store(7, content("Loop", List.of(LINE)));
        library.nativeExport = GPX.replace("</gpx>", "<!--" + "x".repeat(5 * 1024 * 1024) + "--></gpx>")
                .getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> formats.readFile(Format.gpx, library.nativeExport, null)).hasMessage("course_source_too_large");
        assertThat(data(service.export(7L, Format.gpx, null, null)))
                .containsEntry("origin", "garmin_export").containsEntry("totalBytes", library.nativeExport.length);
        library.nativeExport = "<html>upstream error</html>".getBytes(StandardCharsets.UTF_8);
        assertThat(data(service.export(7L, Format.gpx, null, null))).containsEntry("origin", "reconstructed");
    }

    @Test
    void preservesAnOmittedNameAndRefusesToVerifyLossOfUntouchedGarminFields() {
        for (String missing : List.of("settings", "navigation")) {
            var storage = new FixtureLibrary();
            storage.store(7, content("  Crête  ", List.of(LINE)));
            var subject = service(storage, formats);
            var preview = subject.prepare(Action.update, 7L, new Changes(null, "New", null, null, null, false));
            assertThat(data(preview)).containsEntry("name", "  Crête  ");
            storage.droppedField = missing;
            assertThat(subject.confirm(Action.update, operationId(preview)).status()).isEqualTo("course_write_unconfirmed");
            assertThat(storage.rows.get(7L).content().name()).isEqualTo("  Crête  ");
        }
    }

    @Test
    void rejectsMalformedExplicitPointsAndGeoJsonAndDisclosesFlattenedCopies() {
        var malformed = List.of(new NavigationPoint("Eau", "water", 47.0, null, null, null));
        assertThatThrownBy(() -> service.prepare(Action.create, null, new Changes("Loop", null, null, points(LINE), malformed, true)))
                .hasMessage("invalid_input");
        assertThatThrownBy(() -> formats.readFile(Format.geojson, "null".getBytes(StandardCharsets.UTF_8), null)).hasMessage("invalid_input");
        library.store(7, content("Loop", List.of(List.of(LINE.getFirst()), LINE)));
        assertThatThrownBy(() -> service.export(7L, Format.geojson, null, null)).hasMessage("course_export_unsupported");
        var copied = service.prepare(Action.duplicate, 7L, new Changes("Copy", null, null, null, null, false));
        assertThat(data(copied).get("warnings").toString()).contains("flattened");
        var withExtensions = GPX.replace("</wpt>", "<extensions><demo xmlns=\"urn:test\"/></extensions></wpt>");
        assertThat(formats.readFile(Format.gpx, withExtensions.getBytes(StandardCharsets.UTF_8), null).losses())
                .anySatisfy(loss -> assertThat(loss).contains("waypoint", "extensions"));
    }

    @Test
    void retriesPreflightQuotaAndVanishedCandidateWithoutSendingAnEarlyWrite() {
        var timestamp = new AtomicReference<>(START);
        var service = service(library, formats, new Clock() {
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant(), zone); }
            @Override public Instant instant() { return timestamp.get(); }
        });
        library.store(99, content("Loop", List.of(LINE)));
        var preview = service.prepare(Action.create, null, new Changes("Loop", null, null, points(LINE), null, false));
        String token = operationId(preview);
        library.quota = true;
        var quota = service.confirm(Action.create, token);
        assertThat(quota.status()).isEqualTo("garmin_rate_limited");
        assertThat(data(quota)).containsEntry("retryAfterSeconds", 47);
        timestamp.set(START.plusSeconds(46));
        assertThat(service.confirm(Action.create, token)).isEqualTo(quota);
        timestamp.set(START.plusSeconds(47));
        library.vanished = true;
        assertThat(service.confirm(Action.create, token).status()).isEqualTo("course_busy");
        assertThat(library.created).hasValue(0);
        timestamp.set(START.plusSeconds(49));
        assertThat(service.confirm(Action.create, token).status()).isEqualTo("created");
        assertThat(library.created).hasValue(1);
    }

    @Test
    void reservesAlternativesTogetherAfterAllAreValidated() {
        var good = new Route(new Trace(List.of(LINE), List.of(), null, "running", null, "router",
                CourseGeometry.metrics(List.of(LINE)), List.of(), List.of()), null, null);
        var bad = new Route(new Trace(List.of(List.of(LINE.getFirst())), List.of(), null, "running", null, "router",
                CourseGeometry.metrics(List.of(List.of(LINE.getFirst()))), List.of(), List.of()), null, null);
        var request = new PlanRequest("running", "point_to_point", null, null, null, null, null, null, null);
        for (int i = 0; i < 130; i++) {
            assertThatThrownBy(() -> service.plan(input -> List.of(good, bad), request)).hasMessage("course_no_geometry");
        }
        for (int i = 0; i < 42; i++) service.plan(input -> List.of(good, good, good), request);
        assertThatThrownBy(() -> service.plan(input -> List.of(good, good, good), request)).hasMessage("course_busy");
        assertThat(service.plan(input -> List.of(good, good), request).status()).isEqualTo("ok");
    }

    private static Content content(String name, List<List<Point>> segments) {
        return new Content(name, "running", null, "private", segments, List.of());
    }

    @Test
    void reservesOneMutationAndReconcilesAResponseLostAfterCreation() throws Exception {
        library.lost = true;
        library.pause = new CountDownLatch(1);
        var changes = new Changes("Loop", null, null, points(LINE), null, null);
        var preview = service.prepare(Action.create, null, changes);
        String id = operationId(preview);
        var answer = new java.util.concurrent.FutureTask<>(() -> service.confirm(Action.create, id));
        Thread.startVirtualThread(answer);
        try {
            assertThat(library.entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(service.confirm(Action.create, id).status()).isEqualTo("in_progress");
        } finally {
            library.pause.countDown();
        }
        assertThat(answer.get(5, TimeUnit.SECONDS).status()).isEqualTo("course_write_unconfirmed");
        assertThat(service.confirm(Action.create, id).status()).isEqualTo("course_write_unconfirmed");
        String distinctId = operationId(service.prepare(Action.create, null, changes));
        var pending = service.confirm(Action.create, distinctId);
        assertThat(pending.status()).isEqualTo("course_busy");
        assertThat(data(pending)).containsEntry("pendingOperationId", id);
        library.hidden = false;
        assertThat(service.operation(id).status()).isEqualTo("created");
        assertThat(service.confirm(Action.create, id).status()).isEqualTo("created");
        assertThat(service.confirm(Action.create, distinctId).status()).isEqualTo("already_exists");
        assertThat(library.created).hasValue(1);
        assertThatThrownBy(() -> service(new FixtureLibrary(), formats).confirm(Action.create, id)).hasMessage("operation_expired");
    }

    static CourseService service(FixtureLibrary library, CourseFormats formats) {
        return service(library, formats, Clock.fixed(START, ZoneOffset.UTC));
    }

    private static CourseService service(FixtureLibrary library, CourseFormats formats, Clock clock) {
        ActivityReader activity = mock(ActivityReader.class);
        when(activity.search(Map.of(), 0, 1, ActivityReader.SearchView.SUMMARY)).thenReturn(List.of(
                new ActivityReader.Activity(42, null, LocalDate.parse("2026-04-02"), "running", Map.of(), Map.of(), Map.of(), null, Map.of(), Map.of())));
        return new CourseService(library, activity, (id, limit) -> archive(), formats, clock);
    }

    private static Source points(List<Point> points) {
        return new Source("points", null, null, null, null, null, points, null, null, null);
    }

    private static Source file(Format format, String body) {
        return new Source("file", format, "example." + format, body, null, null, null, null, null, null);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(ToolResult result) {
        return (Map<String, Object>) result.data();
    }

    private static String operationId(ToolResult preview) {
        assertThat(preview.status()).as(preview.toString()).isEqualTo("preview");
        return (String) data(preview).get("operationId");
    }

    private static byte[] fit(File type) {
        var encoder = new BufferEncoder(Fit.ProtocolVersion.V2_0);
        var file = new FileIdMesg();
        file.setType(type);
        encoder.write(file);
        if (type == File.COURSE) {
            var course = new CourseMesg();
            course.setName("Sous-bois");
            course.setSport(Sport.RUNNING);
            course.setSubSport(SubSport.TRAIL);
            encoder.write(course);
        }
        for (int i = 0; i < 2; i++) {
            var record = new RecordMesg();
            record.setPositionLat(560_732_645 + i * 120_000);
            record.setPositionLong(23_860_929 + i * 120_000);
            record.setTimestamp(new DateTime(START.plusSeconds(600L * i)));
            record.setAltitude(20f * i);
            encoder.write(record);
        }
        if (type == File.COURSE) {
            var point = new CoursePointMesg();
            point.setName("Eau");
            point.setType(CoursePoint.WATER);
            point.setDistance(0f);
            point.setTimestamp(new DateTime(START));
            encoder.write(point);
        }
        return encoder.close();
    }

    private static byte[] archive() {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("activity.fit"));
            zip.write(fit(File.ACTIVITY));
        } catch (IOException error) {
            throw new AssertionError(error);
        }
        return bytes.toByteArray();
    }

    static final class FixtureLibrary implements CourseLibrary {
        final Map<Long, Snapshot> rows = new LinkedHashMap<>();
        final AtomicInteger created = new AtomicInteger();
        final AtomicInteger listReads = new AtomicInteger();
        final CountDownLatch entered = new CountDownLatch(1);
        volatile boolean lost;
        volatile boolean hidden;
        CountDownLatch pause;
        byte[] nativeExport;
        NavigationPoint wireNavigation;
        String droppedField;
        boolean quota;
        boolean vanished;

        @Override public Capabilities capabilities() {
            return new Capabilities(Set.of("running", "hiking", "trail_running"),
                    Set.of("name", "description", "sport", "source", "coursePoints"), Set.of("water", "danger"),
                    true, true, Set.of(Format.gpx));
        }

        @Override public Library list(long accountActivityId) {
            assertThat(accountActivityId).isEqualTo(42);
            listReads.incrementAndGet();
            return new Library(hidden ? List.of() : rows.values().stream().map(Snapshot::summary).toList(), true);
        }

        @Override public Library list(long accountActivityId, Duration timeout) {
            assertThat(timeout).isPositive().isLessThanOrEqualTo(Duration.ofSeconds(15));
            if (quota) {
                quota = false;
                throw new Problem("garmin_rate_limited", Map.of("retryAfterSeconds", 47));
            }
            return list(accountActivityId);
        }

        @Override public Optional<Snapshot> get(long accountActivityId, long id) {
            assertThat(accountActivityId).isEqualTo(42);
            return Optional.ofNullable(hidden ? null : rows.get(id));
        }

        @Override public Optional<Snapshot> get(long accountActivityId, long id, Duration timeout) {
            assertThat(timeout).isPositive().isLessThanOrEqualTo(Duration.ofSeconds(15));
            if (vanished) {
                vanished = false;
                rows.remove(id);
            }
            return get(accountActivityId, id);
        }

        @Override public boolean verifyAbsent(long accountActivityId, long id) {
            return get(accountActivityId, id).isEmpty();
        }

        @Override public Mutation create(long accountActivityId, Content content) {
            entered.countDown();
            if (pause != null) {
                try {
                    if (!pause.await(5, TimeUnit.SECONDS)) throw new AssertionError("Test did not release mutation");
                } catch (InterruptedException error) {
                    throw new AssertionError(error);
                }
            }
            long id = created.incrementAndGet();
            if (wireNavigation != null) {
                content = new Content(content.name(), content.sport(), content.description(), content.privacy(),
                        content.segments(), List.of(wireNavigation));
            }
            store(id, content);
            hidden = lost;
            return lost ? new Mutation("course_write_unconfirmed", null, null) : new Mutation("accepted", id, null);
        }

        @Override public Mutation update(long accountActivityId, Snapshot current, Content target, Set<String> fields) {
            long id = current.summary().courseId();
            store(id, target);
            return new Mutation("accepted", id, null);
        }

        @Override public Mutation delete(long accountActivityId, Snapshot current) {
            long id = current.summary().courseId();
            rows.remove(id);
            return new Mutation("accepted", id, null);
        }

        @Override public Optional<byte[]> export(long accountActivityId, long id, Format format) {
            return Optional.ofNullable(nativeExport);
        }

        private void store(long id, Content content) {
            var metrics = CourseGeometry.metrics(content.segments());
            var summary = new Summary(id, content.name(), content.sport(), content.privacy(), metrics.distanceMeters(),
                    metrics.ascentMeters(), metrics.descentMeters(), START);
            rows.put(id, new Snapshot(summary, content, Long.toString(id),
                    "settings".equals(droppedField) ? "lost" : "stable-settings",
                    "navigation".equals(droppedField) ? "lost" : "stable-navigation", List.of(), List.of()));
        }
    }
}
