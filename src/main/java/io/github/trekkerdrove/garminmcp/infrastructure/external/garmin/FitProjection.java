package io.github.trekkerdrove.garminmcp.infrastructure.external.garmin;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.DoubleSummaryStatistics;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.garmin.fit.ActivityMesg;
import com.garmin.fit.DateTime;
import com.garmin.fit.LapMesg;
import com.garmin.fit.Mesg;
import com.garmin.fit.MesgNum;
import com.garmin.fit.RecordMesg;
import com.garmin.fit.SessionMesg;
import com.garmin.fit.SetMesg;
import com.garmin.fit.SetType;
import io.github.trekkerdrove.garminmcp.application.dto.FitRequest;
import io.github.trekkerdrove.garminmcp.application.dto.GarminFailure;
import io.github.trekkerdrove.garminmcp.application.port.out.FitDecoder;
import io.github.trekkerdrove.garminmcp.application.service.FitRecordAnalysis;

final class FitProjection {
    private final FitRequest request;
    private final List<Map<String, Object>> rows = new ArrayList<>();
    private final FitRecordAnalysis analysis;
    private final Map<String, DoubleSummaryStatistics> measured = new LinkedHashMap<>();
    private int messages;
    private int developerMetadata;
    private int records;
    private int matched;
    private Instant start;
    private Instant end;
    private Instant firstActivityTime;
    private ZoneOffset localOffset;

    FitProjection(FitRequest request) {
        this.request = request;
        this.analysis = new FitRecordAnalysis(request.view(), this::add);
    }

    void accept(Mesg message) {
        if (++messages > 120_000) {
            throw new GarminFailure("fit_limit_exceeded");
        }
        switch (message.getNum()) {
            case MesgNum.DEVELOPER_DATA_ID, MesgNum.FIELD_DESCRIPTION -> {
                // The FIT SDK retains these definitions until the end of the stream.
                if (++developerMetadata > 512) {
                    throw new GarminFailure("fit_limit_exceeded");
                }
            }
            case MesgNum.RECORD -> record(new RecordMesg(message));
            case MesgNum.SESSION -> session(new SessionMesg(message));
            case MesgNum.ACTIVITY -> {
                var activity = new ActivityMesg(message);
                coverage(instant(activity.getTimestamp()));
                if (instant(activity.getTimestamp()) != null && activity.getLocalTimestamp() != null) {
                    long delta = activity.getLocalTimestamp() - activity.getTimestamp().getTimestamp();
                    if (Math.abs(delta) <= 18 * 3600) {
                        localOffset = ZoneOffset.ofTotalSeconds((int) delta);
                    }
                }
            }
            case MesgNum.LAP -> {
                var lap = new LapMesg(message);
                var from = instant(lap.getStartTime());
                var to = instant(lap.getTimestamp());
                startTime(from);
                coverage(to);
                if (request.view() == FitRequest.View.laps) {
                    var row = new LinkedHashMap<String, Object>();
                    put(row, "startUtc", from);
                    put(row, "endUtc", to);
                    put(row, "sport", lap.getSport());
                    put(row, "elapsedSeconds", lap.getTotalElapsedTime());
                    put(row, "timerSeconds", lap.getTotalTimerTime());
                    put(row, "distanceM", lap.getTotalDistance());
                    put(row, "averagePowerW", lap.getAvgPower());
                    put(row, "averageHeartRateBpm", lap.getAvgHeartRate());
                    add(row);
                }
            }
            case MesgNum.SET -> {
                var set = new SetMesg(message);
                var from = instant(set.getStartTime());
                var timestamp = instant(set.getTimestamp());
                startTime(from);
                coverage(timestamp);
                if (request.view() == FitRequest.View.strength) {
                    var row = new LinkedHashMap<String, Object>();
                    put(row, "startUtc", from);
                    put(row, "timestampUtc", timestamp);
                    put(row, "durationSeconds", set.getDuration());
                    put(row, "type", SetType.getStringFromValue(set.getSetType()));
                    put(row, "repetitions", set.getRepetitions());
                    put(row, "weightKg", set.getWeight());
                    add(row);
                }
            }
            default -> { }
        }
    }

    private void record(RecordMesg record) {
        records++;
        Instant time = instant(record.getTimestamp());
        startTime(time);
        if (time == null) {
            return;
        }
        if (request.view() == FitRequest.View.samples) {
            var row = new LinkedHashMap<String, Object>();
            for (var metric : request.metrics()) {
                Number value = switch (metric) {
                    case heartRateBpm -> record.getHeartRate();
                    case cadenceRpm -> record.getCadence();
                    case powerW -> record.getPower();
                    case speedMps -> record.getEnhancedSpeed() != null ? record.getEnhancedSpeed() : record.getSpeed();
                    case altitudeM -> altitude(record);
                    case distanceM -> record.getDistance();
                    case temperatureC -> record.getTemperature();
                };
                put(row, metric.name(), value);
            }
            if (!row.isEmpty()) {
                row.put("timestampUtc", time);
                add(row);
            }
        } else if (request.view() == FitRequest.View.climbs) {
            analysis.altitude(time, altitude(record));
        } else if (request.view() == FitRequest.View.cardiac_drift) {
            analysis.powerAndHeartRate(time, record.getPower(), record.getHeartRate());
        } else if (request.view() == FitRequest.View.power) {
            measure("averagePowerW", record.getPower());
        } else if (request.view() == FitRequest.View.cycling_dynamics) {
            measure("averageLeftPlatformOffsetMm", record.getLeftPco());
            measure("averageRightPlatformOffsetMm", record.getRightPco());
            measure("averageLeftTorqueEffectivenessPct", record.getLeftTorqueEffectiveness());
            measure("averageRightTorqueEffectivenessPct", record.getRightTorqueEffectiveness());
            measure("averageLeftPedalSmoothnessPct", record.getLeftPedalSmoothness());
            measure("averageRightPedalSmoothnessPct", record.getRightPedalSmoothness());
        }
    }

    private void measure(String name, Number number) {
        if (number != null && Double.isFinite(number.doubleValue())) {
            measured.computeIfAbsent(name, unused -> new DoubleSummaryStatistics()).accept(number.doubleValue());
        }
    }

    private void session(SessionMesg session) {
        Instant from = instant(session.getStartTime());
        Instant to = instant(session.getTimestamp());
        startTime(from);
        coverage(to);
        var row = new LinkedHashMap<String, Object>();
        switch (request.view()) {
            case multisport -> {
                put(row, "sport", session.getSport());
                put(row, "subSport", session.getSubSport());
                put(row, "elapsedSeconds", session.getTotalElapsedTime());
                put(row, "timerSeconds", session.getTotalTimerTime());
                put(row, "distanceM", session.getTotalDistance());
            }
            case power -> {
                put(row, "averagePowerW", session.getAvgPower());
                put(row, "maxPowerW", session.getMaxPower());
                put(row, "normalizedPowerW", session.getNormalizedPower());
                put(row, "totalWorkJ", session.getTotalWork());
            }
            case cycling_dynamics -> {
                put(row, "averageLeftPlatformOffsetMm", session.getAvgLeftPco());
                put(row, "averageRightPlatformOffsetMm", session.getAvgRightPco());
                put(row, "averageLeftTorqueEffectivenessPct", session.getAvgLeftTorqueEffectiveness());
                put(row, "averageRightTorqueEffectivenessPct", session.getAvgRightTorqueEffectiveness());
                put(row, "averageLeftPedalSmoothnessPct", session.getAvgLeftPedalSmoothness());
                put(row, "averageRightPedalSmoothnessPct", session.getAvgRightPedalSmoothness());
            }
            default -> { return; }
        }
        if (!row.isEmpty()) {
            put(row, "sport", session.getSport());
            put(row, "startUtc", from);
            put(row, "endUtc", to);
            row.put("source", "FIT session");
            add(row);
        }
    }

    private void coverage(Instant time) {
        if (time != null) {
            if (start == null || time.isBefore(start)) {
                start = time;
            }
            if (end == null || time.isAfter(end)) {
                end = time;
            }
        }
    }

    private void startTime(Instant time) {
        coverage(time);
        if (time != null && (firstActivityTime == null || time.isBefore(firstActivityTime))) {
            firstActivityTime = time;
        }
    }

    private static Float altitude(RecordMesg record) {
        return record.getEnhancedAltitude() != null ? record.getEnhancedAltitude() : record.getAltitude();
    }

    private static Instant instant(DateTime time) {
        return time == null || time.getTimestamp() < DateTime.MIN ? null : time.getInstant();
    }

    private static void put(Map<String, Object> row, String key, Object value) {
        if (value != null && !"".equals(value) && (!(value instanceof Number number) || Double.isFinite(number.doubleValue()))) {
            row.put(key, value);
        }
    }

    private void add(Map<String, Object> row) {
        if (!row.isEmpty()) {
            matched++;
            if (rows.size() < request.limit()) {
                rows.add(row);
            }
        }
    }

    FitDecoder.Decoded finish() {
        analysis.finish();
        if (!measured.isEmpty()) {
            var row = new LinkedHashMap<String, Object>();
            var counts = new LinkedHashMap<String, Long>();
            measured.forEach((name, stats) -> {
                row.put(name, stats.getAverage());
                counts.put(name, stats.getCount());
            });
            if (measured.containsKey("averagePowerW")) {
                row.put("maxPowerW", measured.get("averagePowerW").getMax());
            }
            row.put("sampleCounts", counts);
            row.put("source", "FIT records");
            row.put("scope", "activity");
            row.put("method", "Arithmetic mean of available time-stamped samples across the activity");
            add(row);
        }
        var date = firstActivityTime == null || localOffset == null ? null : firstActivityTime.atOffset(localOffset).toLocalDate();
        return new FitDecoder.Decoded(rows, matched, records, start, end, date);
    }
}
