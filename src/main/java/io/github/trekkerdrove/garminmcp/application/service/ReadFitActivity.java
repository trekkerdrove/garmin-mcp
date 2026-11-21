package io.github.trekkerdrove.garminmcp.application.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import io.github.trekkerdrove.garminmcp.application.dto.FitRequest;
import io.github.trekkerdrove.garminmcp.application.dto.GarminFailure;
import io.github.trekkerdrove.garminmcp.application.dto.ToolResult;
import io.github.trekkerdrove.garminmcp.application.port.out.FitDecoder;
import io.github.trekkerdrove.garminmcp.application.port.out.GarminArchive;
import io.github.trekkerdrove.garminmcp.application.port.out.GarminWeight;

public final class ReadFitActivity {
    public static final int MAX_ARCHIVE_BYTES = 4 * 1024 * 1024;
    private final GarminArchive garmin;
    private final FitDecoder decoder;
    private final GarminWeight weights;
    private final Semaphore slot = new Semaphore(1);

    public ReadFitActivity(GarminArchive garmin, FitDecoder decoder, GarminWeight weights) {
        this.garmin = garmin;
        this.decoder = decoder;
        this.weights = weights;
    }

    public ToolResult read(FitRequest request) {
        if (!slot.tryAcquire()) {
            throw new GarminFailure("fit_busy");
        }
        try {
            var archive = garmin.activityArchive(request.activityId(), MAX_ARCHIVE_BYTES);
            var result = decoder.read(archive, request);
            var data = new LinkedHashMap<String, Object>();
            data.put("activityId", request.activityId());
            data.put("source", "Garmin Personal API original activity FIT");
            data.put("view", request.view());
            data.put("startUtc", result.startUtc());
            data.put("endUtc", result.endUtc());
            data.put("recordCount", result.records());
            data.put("matchedRows", result.matched());
            data.put("truncated", result.rows().size() < result.matched());
            if (request.view() == FitRequest.View.samples) {
                data.put("requestedMetrics", request.metrics());
            }
            List<Map<String, Object>> rows = result.rows();
            if (request.includeWattsPerKg() && !rows.isEmpty()) {
                rows = powerPerKg(result, data);
            }
            data.put("rows", rows);
            if (result.matched() == 0) {
                return ToolResult.empty(data);
            }
            return rows.size() < result.matched() ? ToolResult.truncated(data) : ToolResult.ok(data);
        } finally {
            slot.release();
        }
    }

    private List<Map<String, Object>> powerPerKg(FitDecoder.Decoded result, Map<String, Object> data) {
        if (result.localStartDate() == null) {
            data.put("weightStatus", "local_activity_date_absent");
            return result.rows();
        }
        data.put("weightLookupThrough", result.localStartDate());
        try {
            var kg = weights.kilogramsOnOrBefore(result.localStartDate());
            if (kg.isPresent() && Double.isFinite(kg.getAsDouble()) && kg.getAsDouble() > 0) {
                data.put("weightStatus", "available");
                return result.rows().stream().map(row -> {
                    Map<String, Object> enriched = new LinkedHashMap<>(row);
                    if (row.get("averagePowerW") instanceof Number watts) {
                        enriched.put("averagePowerWPerKg", watts.doubleValue() / kg.getAsDouble());
                    }
                    return enriched;
                }).toList();
            }
            data.put("weightStatus", "no_data");
        } catch (GarminFailure failure) {
            data.put("weightStatus", failure.status());
        }
        return result.rows();
    }
}
