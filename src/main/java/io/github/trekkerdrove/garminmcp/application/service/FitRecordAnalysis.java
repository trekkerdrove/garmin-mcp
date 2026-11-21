package io.github.trekkerdrove.garminmcp.application.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import io.github.trekkerdrove.garminmcp.application.dto.FitRequest;

public final class FitRecordAnalysis {
    private final FitRequest.View view;
    private final Consumer<Map<String, Object>> emit;
    private final List<Pair> pairs = new ArrayList<>();
    private Instant climbStart;
    private Instant peakTime;
    private Instant lastTime;
    private double baseMeters;
    private double peakMeters;

    public FitRecordAnalysis(FitRequest.View view, Consumer<Map<String, Object>> emit) {
        this.view = view;
        this.emit = emit;
    }

    public void altitude(Instant time, Float meters) {
        if (meters == null || !Float.isFinite(meters)) {
            finishClimb();
            return;
        }
        if (lastTime != null && (!time.isAfter(lastTime) || time.getEpochSecond() - lastTime.getEpochSecond() > 60
                || meters < peakMeters - 3)) {
            finishClimb();
        }
        if (climbStart == null || meters <= baseMeters) {
            climbStart = time;
            peakTime = time;
            baseMeters = peakMeters = meters;
        } else if (meters > peakMeters) {
            peakTime = time;
            peakMeters = meters;
        }
        lastTime = time;
    }

    private void finishClimb() {
        if (climbStart != null) {
            double gain = peakMeters - baseMeters;
            long seconds = peakTime.getEpochSecond() - climbStart.getEpochSecond();
            if (gain >= 30 && seconds >= 120) {
                emit.accept(Map.of("startUtc", climbStart, "endUtc", peakTime, "gainM", gain,
                        "elapsedSeconds", seconds, "ascentMetersPerHour", 3600 * gain / seconds,
                        "method", "Continuous recorded ascent of at least 30 m and 120 s, allowing 3 m descent and at most 60 s between records"));
            }
        }
        climbStart = null;
        lastTime = null;
    }

    public void powerAndHeartRate(Instant time, Integer watts, Short bpm) {
        pairs.add(new Pair(time.getEpochSecond(), watts == null ? -1 : watts, bpm == null ? 0 : bpm));
    }

    public void finish() {
        if (view == FitRequest.View.climbs) {
            finishClimb();
        }
        if (view != FitRequest.View.cardiac_drift || pairs.size() < 4) {
            return;
        }
        long start = pairs.getFirst().second();
        long end = pairs.getLast().second();
        if (end <= start) return;
        double midpoint = start + (end - start) / 2d;
        var first = new Half();
        var second = new Half();
        for (int i = 0; i < pairs.size(); i++) {
            var pair = pairs.get(i);
            if (pair.valid()) {
                (pair.second() < midpoint ? first : second).samples++;
            }
            if (i + 1 == pairs.size()) break;
            var next = pairs.get(i + 1);
            long interval = next.second() - pair.second();
            if (interval < 0) return;
            if (interval > 60 || !pair.valid() || !next.valid()) continue;
            first.add(pair, Math.max(0, Math.min(next.second(), midpoint) - pair.second()));
            second.add(pair, Math.max(0, next.second() - Math.max(pair.second(), midpoint)));
        }
        double minimumCoverage = (end - start) / 4d;
        if (first.samples >= 2 && second.samples >= 2 && first.seconds >= minimumCoverage
                && second.seconds >= minimumCoverage && first.watts > 0) {
            double firstRatio = first.watts / first.bpm;
            double secondRatio = second.watts / second.bpm;
            emit.accept(Map.of("firstHalfPairs", first.samples, "secondHalfPairs", second.samples,
                    "firstWattsPerBpm", firstRatio, "secondWattsPerBpm", secondRatio,
                    "changePct", 100 * (secondRatio / firstRatio - 1),
                    "firstCoveredSeconds", first.seconds, "secondCoveredSeconds", second.seconds,
                    "method", "Power/HR ratio weighted by observed seconds in each elapsed-time half. Excludes missing pairs and gaps over 60 s; requires 50% coverage in each half. Negative change means lower power per beat"));
        }
    }

    private record Pair(long second, int watts, int bpm) {
        boolean valid() { return watts >= 0 && bpm > 0; }
    }
    private static final class Half {
        double watts;
        double bpm;
        double seconds;
        int samples;

        void add(Pair pair, double duration) {
            seconds += duration;
            watts += pair.watts() * duration;
            bpm += pair.bpm() * duration;
        }
    }
}
