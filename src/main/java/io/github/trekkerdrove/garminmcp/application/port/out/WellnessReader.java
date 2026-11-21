package io.github.trekkerdrove.garminmcp.application.port.out;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public interface WellnessReader {
    enum View {
        SUMMARY(1), TRENDS(365), SLEEP(31), HEART_RATE(1), HRV(366), STEPS(28), STRESS(28), STRESS_DETAILS(365),
        HYDRATION(31), BATTERY(31), RESPIRATION(365), SPO2(1), VO2(366), FITNESS_AGE(1), ZONES(1);

        public final int windowDays;

        View(int windowDays) {
            this.windowDays = windowDays;
        }
    }

    enum Metric { movement, calories, intensityMinutes, heartRate, stress, bodyBattery, hydration, respiration, pulseOx }

    record Options(List<Metric> metrics, int samples, int chunks, boolean detail, boolean events,
                   boolean defaults, String sport, boolean hourly) {
        public static final Options BASIC = new Options(List.of(), 0, 0, false, false, false, null, false);
    }

    record Observation(LocalDate date, Map<String, Object> values) {}

    List<Observation> read(View view, LocalDate start, LocalDate end, Options options);
}
