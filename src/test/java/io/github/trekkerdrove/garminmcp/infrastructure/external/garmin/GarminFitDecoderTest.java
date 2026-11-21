package io.github.trekkerdrove.garminmcp.infrastructure.external.garmin;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalDouble;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import com.garmin.fit.ActivityMesg;
import com.garmin.fit.BufferEncoder;
import com.garmin.fit.DateTime;
import com.garmin.fit.Fit;
import com.garmin.fit.LapMesg;
import com.garmin.fit.RecordMesg;
import com.garmin.fit.SessionMesg;
import com.garmin.fit.SetMesg;
import com.garmin.fit.SetType;
import com.garmin.fit.Sport;
import io.github.trekkerdrove.garminmcp.application.dto.FitRequest;
import io.github.trekkerdrove.garminmcp.application.dto.FitRequest.Metric;
import io.github.trekkerdrove.garminmcp.application.dto.FitRequest.View;
import io.github.trekkerdrove.garminmcp.application.dto.GarminFailure;
import io.github.trekkerdrove.garminmcp.application.dto.ToolResult;
import io.github.trekkerdrove.garminmcp.application.service.ReadFitActivity;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static java.util.zip.ZipEntry.*;

public class GarminFitDecoderTest {
    private static final Instant START = Instant.parse("2026-04-02T23:30:00Z");
    private final GarminFitDecoder decoder = new GarminFitDecoder();

    public static byte[] activityArchive() throws IOException {
        return zip("sample.FIT", fit());
    }

    private static byte[] fit() {
        var encoder = new BufferEncoder(Fit.ProtocolVersion.V2_0);
        for (int i = 0; i < 6; i++) {
            var record = new RecordMesg();
            record.setTimestamp(new DateTime(START.plusSeconds(i * 30L)));
            record.setPower(i == 0 ? 0 : 200);
            record.setHeartRate((short) (100 + i));
            record.setEnhancedAltitude(100f + 10 * i);
            record.setPositionLat(123456);
            encoder.write(record);
        }
        var lap = new LapMesg();
        lap.setStartTime(new DateTime(START));
        lap.setTimestamp(new DateTime(START.plusSeconds(150)));
        lap.setTotalDistance(800f);
        encoder.write(lap);
        for (var sport : List.of(Sport.CYCLING, Sport.RUNNING)) {
            var session = new SessionMesg();
            session.setStartTime(new DateTime(sport == Sport.CYCLING ? START : START.plusSeconds(150)));
            session.setTimestamp(new DateTime(sport == Sport.CYCLING ? START.plusSeconds(150) : START.plusSeconds(240)));
            session.setSport(sport);
            if (sport == Sport.CYCLING) {
                session.setAvgPower(210);
                session.setAvgLeftPco((byte) -2);
            }
            encoder.write(session);
        }
        var set = new SetMesg();
        set.setSetType(SetType.ACTIVE);
        set.setWeight(20f);
        set.setRepetitions(8);
        encoder.write(set);
        var activity = new ActivityMesg();
        var end = new DateTime(START.plusSeconds(240));
        activity.setTimestamp(end);
        activity.setLocalTimestamp(end.getTimestamp() + 7200);
        encoder.write(activity);
        return encoder.close();
    }

    private static byte[] zip(String name, byte[] bytes) throws IOException {
        var output = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry(name));
            zip.write(bytes);
        }
        return output.toByteArray();
    }

    private static FitRequest analysis(View view) {
        return new FitRequest(1, view, List.of(), 50, false);
    }

    @Test
    void handlesSixHoursAtOneHertzAndOmitsDriftWhenTheRecordingIsInterrupted() throws Exception {
        var encoder = new BufferEncoder(Fit.ProtocolVersion.V2_0);
        var rec = new RecordMesg();
        rec.setPower(210);
        rec.setHeartRate((short) 150);
        rec.setLeftPco((byte) 2);
        int records = 6 * 3600;
        for (int i = 0; i < records; i++) {
            rec.setTimestamp(new DateTime(START.plusSeconds(i)));
            encoder.write(rec);
        }
        byte[] sixHours = zip("six-hours.fit", encoder.close());
        var measured = decoder.read(sixHours, analysis(View.cardiac_drift));
        assertThat(measured.records()).isEqualTo(records);
        assertThat(measured.rows().getFirst()).containsEntry("changePct", 0d);
        assertThat(decoder.read(sixHours, analysis(View.power)).rows().getFirst())
                .containsEntry("averagePowerW", 210d).containsEntry("source", "FIT records");
        assertThat(decoder.read(sixHours, analysis(View.cycling_dynamics)).rows().getFirst())
                .containsEntry("averageLeftPlatformOffsetMm", 2d);
        var interrupted = new BufferEncoder(Fit.ProtocolVersion.V2_0);
        for (int second : new int[]{0, 1, 3599, 3600}) {
            rec.setTimestamp(new DateTime(START.plusSeconds(second)));
            interrupted.write(rec);
        }
        assertThat(decoder.read(zip("interrupted.fit", interrupted.close()), analysis(View.cardiac_drift)).matched())
                .isZero();
    }

    @Test
    void weightsDriftByTimeAcrossIrregularIntervalsAndReportsTheSignedChange() throws Exception {
        var encoder = new BufferEncoder(Fit.ProtocolVersion.V2_0);
        // The midpoint at 50 s falls inside the 35–65 s interval.
        for (int[] sample : new int[][]{{0, 100, 100}, {10, 300, 150}, {35, 200, 100},
                {65, 100, 150}, {75, 100, 200}, {100, 150, 120}}) {
            var record = new RecordMesg();
            record.setTimestamp(new DateTime(START.plusSeconds(sample[0])));
            record.setPower(sample[1]);
            record.setHeartRate((short) sample[2]);
            encoder.write(record);
        }
        var row = decoder.read(zip("drift.fit", encoder.close()), analysis(View.cardiac_drift)).rows().getFirst();
        assertThat((double) row.get("firstWattsPerBpm")).isCloseTo(1.84, within(1e-9));
        assertThat((double) row.get("secondWattsPerBpm")).isCloseTo(0.8125, within(1e-9));
        assertThat((double) row.get("changePct")).isCloseTo(-55.84239130435, within(1e-9));
    }

    @Test
    void preservesSessionWorkAndActivityWideRecordPowerInSeparateRows() throws Exception {
        var encoder = new BufferEncoder(Fit.ProtocolVersion.V2_0);
        var lap = new LapMesg();
        lap.setStartTime(new DateTime(START));
        lap.setTimestamp(new DateTime(START.plusSeconds(250)));
        encoder.write(lap);
        var session = new SessionMesg();
        session.setSport(Sport.CYCLING);
        session.setTotalWork(500L);
        session.setStartTime(new DateTime(START.plusSeconds(10)));
        session.setTimestamp(new DateTime(START.plusSeconds(100)));
        encoder.write(session);
        for (int power : new int[]{100, 200}) {
            var record = new RecordMesg();
            record.setTimestamp(new DateTime(START.plusSeconds(power)));
            record.setPower(power);
            encoder.write(record);
        }
        var power = decoder.read(zip("partial-session.fit", encoder.close()), analysis(View.power));
        assertThat(power.rows()).hasSize(2);
        assertThat(power.rows().getFirst()).containsEntry("source", "FIT session").containsEntry("totalWorkJ", 500L)
                .doesNotContainKeys("averagePowerW", "maxPowerW");
        assertThat(power.rows().getLast()).containsEntry("source", "FIT records").containsEntry("scope", "activity")
                .containsEntry("averagePowerW", 150d).containsEntry("maxPowerW", 200d).doesNotContainKeys("sport", "totalWorkJ");
        assertThat(power.startUtc()).isEqualTo(START);
        assertThat(power.endUtc()).isEqualTo(START.plusSeconds(250));
    }

    @Test
    void retainsSetCoverageForEveryViewInStrengthOnlyFiles() throws Exception {
        var encoder = new BufferEncoder(Fit.ProtocolVersion.V2_0);
        var set = new SetMesg();
        set.setStartTime(new DateTime(START));
        set.setTimestamp(new DateTime(START.plusSeconds(30)));
        set.setRepetitions(10);
        encoder.write(set);
        var file = zip("sets.fit", encoder.close());
        for (var view : List.of(View.power, View.strength)) {
            var result = decoder.read(file, analysis(view));
            assertThat(result.startUtc()).isEqualTo(START);
            assertThat(result.endUtc()).isEqualTo(START.plusSeconds(30));
        }
    }

    @Test
    void projectsRecordedMultisportSensorsAndCalculatedViews() throws Exception {
        var archive = activityArchive();
        var sessions = decoder.read(archive, analysis(View.multisport));
        assertThat(sessions.rows()).extracting(row -> row.get("sport"))
                .containsExactly(Sport.CYCLING, Sport.RUNNING);
        assertThat(sessions.localStartDate()).isEqualTo(LocalDate.parse("2026-04-03"));
        assertThat(decoder.read(archive, analysis(View.laps)).rows().getFirst()).containsEntry("distanceM", 800f);
        assertThat(decoder.read(archive, analysis(View.strength)).rows().getFirst()).containsEntry("weightKg", 20f);
        assertThat(decoder.read(archive, analysis(View.power)).rows().getFirst()).containsEntry("averagePowerW", 210);
        assertThat(decoder.read(archive, analysis(View.cycling_dynamics)).rows().getFirst())
                .containsEntry("averageLeftPlatformOffsetMm", (byte) -2);
        assertThat(decoder.read(archive, analysis(View.climbs)).rows().getFirst())
                .containsEntry("gainM", 50d).containsEntry("elapsedSeconds", 150L);
        assertThat(decoder.read(archive, analysis(View.cardiac_drift)).rows().getFirst())
                .containsEntry("firstHalfPairs", 3).containsEntry("secondHalfPairs", 3);
    }

    @Test
    void boundsSamplesAndDistinguishesMissingSensorsFromMeasuredZero() throws Exception {
        var archive = activityArchive();
        var service = new ReadFitActivity((id, limit) -> archive, decoder, day -> OptionalDouble.empty());
        var samples = service.read(new FitRequest(1, View.samples, List.of(Metric.powerW), 2, false));
        assertThat(samples.status()).isEqualTo("truncated");
        var decoded = decoder.read(archive, new FitRequest(1, View.samples, List.of(Metric.powerW), 2, false));
        assertThat(decoded.matched()).isEqualTo(6);
        assertThat(decoded.rows()).hasSize(2);
        assertThat(decoded.rows().getFirst()).containsOnlyKeys("timestampUtc", "powerW").containsEntry("powerW", 0);
        assertThat(service.read(new FitRequest(1, View.samples, List.of(Metric.temperatureC), 1, false)).status())
                .isEqualTo("no_data");
        assertThatThrownBy(() -> new FitRequest(1, View.samples, List.of(Metric.powerW), 201, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void enrichesWithWeightOnTheLocalActivityDayAndPreservesPowerIfWeightFails() throws Exception {
        var archive = activityArchive();
        var request = new FitRequest(1, View.power, List.of(), 1, true);
        var measured = new ReadFitActivity((id, limit) -> archive, decoder, date -> {
            assertThat(date).isEqualTo(LocalDate.parse("2026-04-03"));
            return OptionalDouble.of(70);
        });
        assertThat(measured.read(request).data().toString()).contains("averagePowerWPerKg=3.0");
        var denied = new ReadFitActivity((id, limit) -> archive, decoder, date -> {
            throw new GarminFailure("rate_limited");
        });
        assertThat(denied.read(request).data().toString()).contains("weightStatus=rate_limited", "averagePowerW=210");
        var encoder = new BufferEncoder(Fit.ProtocolVersion.V2_0);
        var session = new SessionMesg();
        session.setAvgPower(200);
        var end = new DateTime(START.plusSeconds(86400));
        session.setTimestamp(end);
        encoder.write(session);
        var completed = new ActivityMesg();
        completed.setTimestamp(end);
        completed.setLocalTimestamp(end.getTimestamp());
        encoder.write(completed);
        var noStart = zip("no-start.fit", encoder.close());
        var withoutDate = new ReadFitActivity((id, limit) -> noStart, decoder, date -> {
            throw new AssertionError("No weigh-in lookup without an activity start");
        });
        assertThat(withoutDate.read(request).data().toString()).contains("weightStatus=local_activity_date_absent");
    }

    @Test
    void rejectsMissingCorruptedAndOversizedInputsWithDistinctStatuses() throws Exception {
        var request = analysis(View.laps);
        assertThatThrownBy(() -> decoder.read(zip("test.gpx", new byte[10]), request)).hasMessage("no_fit_member");
        assertThatThrownBy(() -> decoder.read(new byte[20], request)).hasMessage("invalid_fit");
        byte[] valid = activityArchive();
        assertThatThrownBy(() -> decoder.read(Arrays.copyOf(valid, valid.length - 22), request)).hasMessage("invalid_fit");
        byte[] damaged = fit();
        damaged[damaged.length - 1] ^= 1;
        assertThatThrownBy(() -> decoder.read(zip("broken.fit", damaged), request)).hasMessage("invalid_fit");
        assertThatThrownBy(() -> decoder.read(new byte[ReadFitActivity.MAX_ARCHIVE_BYTES + 1], request))
                .hasMessage("fit_limit_exceeded");
        assertThatThrownBy(() -> decoder.read(zip("ignored.bin", new byte[8 * 1024 * 1024 + 1]), request))
                .hasMessage("fit_limit_exceeded");
        var many = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(many)) {
            for (int i = 0; i < 9; i++) zip.putNextEntry(new ZipEntry(i + ".txt"));
        }
        assertThatThrownBy(() -> decoder.read(many.toByteArray(), request)).hasMessage("fit_limit_exceeded");
    }

    @Test
    void rejectsDamageConfinedToTheZipCentralDirectory() throws Exception {
        byte[] valid = activityArchive();
        var bytes = ByteBuffer.wrap(valid).order(ByteOrder.LITTLE_ENDIAN);
        int central = bytes.getInt(valid.length - ENDHDR + ENDOFF);
        for (int field : new int[]{0, CENOFF, CENCRC, CENSIZ, CENHDR}) {
            byte[] corrupted = valid.clone();
            corrupted[central + field] ^= 1;
            assertThatThrownBy(() -> decoder.read(corrupted, analysis(View.laps))).hasMessage("invalid_fit");
        }
    }

    @Test
    void concurrentRequestsUseOneDownloadSlotAndReleaseItAfterFailure() throws Exception {
        var started = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        var count = new AtomicInteger();
        var service = new ReadFitActivity((id, limit) -> {
            count.incrementAndGet();
            started.countDown();
            try {
                finish.await(3, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            throw new GarminFailure("not_found");
        }, decoder, date -> OptionalDouble.empty());
        var request = analysis(View.power);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var first = executor.submit(() -> ToolResult.run(() -> service.read(request)));
            assertThat(started.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(ToolResult.run(() -> service.read(request)).status()).isEqualTo("fit_busy");
            assertThat(count).hasValue(1);
            finish.countDown();
            assertThat(first.get(3, TimeUnit.SECONDS).status()).isEqualTo("not_found");
            assertThat(ToolResult.run(() -> service.read(request)).status()).isEqualTo("not_found");
        }
    }
}
