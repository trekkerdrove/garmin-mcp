package io.github.trekkerdrove.garminmcp.application.dto;

import java.util.List;

public record FitRequest(long activityId, View view, List<Metric> metrics, int limit, boolean includeWattsPerKg) {
    public enum View { laps, multisport, power, climbs, cardiac_drift, cycling_dynamics, strength, samples }

    public enum Metric {
        heartRateBpm, cadenceRpm, powerW, speedMps, altitudeM, distanceM, temperatureC
    }

    public FitRequest {
        if (activityId <= 0 || view == null || limit < 1 || limit > 200
                || (includeWattsPerKg && view != View.power)) {
            throw new IllegalArgumentException();
        }
        if (metrics == null || metrics.size() > Metric.values().length || metrics.stream().anyMatch(m -> m == null)
                || metrics.stream().distinct().count() != metrics.size() || (view == View.samples && metrics.isEmpty())) {
            throw new IllegalArgumentException();
        }
        metrics = List.copyOf(metrics);
    }
}
