package io.github.trekkerdrove.garminmcp.infrastructure.external.garmin;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import io.github.trekkerdrove.garminmcp.application.dto.GarminFailure;
import io.github.trekkerdrove.garminmcp.application.dto.ToolResult;
import io.github.trekkerdrove.garminmcp.application.port.out.GarminPersonalData.CycleView;
import io.github.trekkerdrove.garminmcp.application.port.out.GarminPersonalData.WeightView;
import io.github.trekkerdrove.garminmcp.application.service.PersonalTrackingService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class PersonalTrackingTest {
    private final Stub http = new Stub();
    private final PersonalTrackingGateway gateway = new PersonalTrackingGateway(http);
    private final PersonalTrackingService service = new PersonalTrackingService(gateway, Clock.systemUTC());

    @Test
    void slicesDateRangesAndDoesNotReportAnInterruptedRangeAsEmpty() {
        assertThat(service.weight("2024-01-01", "2024-12-31", null).status()).isEqualTo("no_data");
        assertThat(http.paths).containsExactly("H16[2024-01-01, 2024-12-29]", "H16[2024-12-30, 2024-12-31]");
        http.failAt = 4;
        assertThat(ToolResult.run(() -> service.weight("2024-01-01", "2024-12-31", null)).status())
                .isEqualTo("garmin_rate_limited");
        assertThat(http.paths).hasSize(4);
    }

    @Test
    void capsRowsAndRetainsTheUnitAndZeros() {
        http.body = "[" + String.join(",", java.util.Collections.nCopies(257,
                "{\"calendarDate\":\"2025-04-01\",\"weight\":73,\"bodyFat\":0}")) + "]";
        var report = service.weight("2025-04-01", null,
                io.github.trekkerdrove.garminmcp.application.port.out.GarminPersonalData.WeightView.composition);
        assertThat(report.status()).isEqualTo("truncated");
        assertThat(Stub.JSON.valueToTree(report.data()).path("rows").size()).isEqualTo(256);
        assertThat(Stub.JSON.valueToTree(report.data()).path("rows").path(0).path("bodyFat").asInt(-1)).isZero();
    }

    @Test
    void wattsPerKilogramUsesOnlyValidSameDayOrRecentPrecedingMeasurements() {
        var date = LocalDate.parse("2025-04-01");
        http.body = """
                {"previousDateWeight":{"calendarDate":"2025-03-30","weight":70},
                 "nextDateWeight":{"calendarDate":"2025-04-02","weight":90},
                 "dailyWeightSummaries":[{"latestWeight":{"calendarDate":"2025-04-01","weight":75}}]}""";
        assertThat(gateway.kilogramsOnOrBefore(date)).hasValue(75);
        assertThat(gateway.kilogramsOnOrBefore(date.minusDays(1))).hasValue(70);
        assertThat(gateway.kilogramsOnOrBefore(date.minusMonths(1))).isEmpty();
        assertThat(gateway.kilogramsOnOrBefore(date.plusYears(2))).hasValue(70);
    }

    @Test
    void normalizesGramAndKilogramMeasurementsAcrossReadsAndHistoricalPowerLookup() {
        var date = LocalDate.of(2025, 4, 1);
        http.body = """
                [{"calendarDate":"2025-04-01","weight":81500},{"calendarDate":"2025-04-01","weight":81.25}]""";
        for (var view : List.of(WeightView.measurements, WeightView.composition)) {
            assertThat(gateway.weights(date, date, view)).extracting(row -> row.get("weightKg"))
                    .containsExactly(81.5, 81.25);
        }
        http.body = """
                {"dailyWeightSummaries":[{"summaryDate":"2025-04-01","latestWeight":{"calendarDate":"2025-04-01","weight":81500}}],
                 "previousDateWeight":{"calendarDate":"2025-03-30","weight":81250}}""";
        assertThat(gateway.weights(date, date, WeightView.summary).getFirst()).containsEntry("latestWeightKg", 81.5);
        assertThat(gateway.kilogramsOnOrBefore(date)).hasValue(81.5);
        assertThat(gateway.kilogramsOnOrBefore(date.minusDays(1))).hasValue(81.25);
    }

    @Test
    void bothCycleViewsReportNoDataWhenGarminReturnsNoContent() {
        http.body = "null";
        for (var view : CycleView.values()) {
            var result = service.cycle("2025-04-01", null, view, false, false);
            assertThat(result.status()).isEqualTo("no_data");
            assertThat(Stub.JSON.valueToTree(result.data()).path("missingDates").get(0).asString())
                    .isEqualTo("2025-04-01");
        }
    }

    @Test
    void distinguishesAnEmptyNutritionLogFromALoggedZero() {
        var date = LocalDate.parse("2025-04-01");
        http.body = """
                {"mealDate":"2025-04-01","dailyNutritionContent":{"calories":0},"mealDetails":[{"meal":{"mealId":7},
                 "mealNutritionContent":{"calories":0},"loggedFoods":[]}]}""";
        assertThat(service.nutrition(date.toString(), null, null).status()).isEqualTo("no_data");
        http.body = http.body.replace("\"loggedFoods\":[]", "\"loggedFoods\":[{\"servingQty\":1}]");
        assertThat(gateway.nutrition(date, 7L, true).nutrients()).containsEntry("calories", 0);
        assertThat(service.nutrition(date.toString(), null, 8L).status()).isEqualTo("no_data");
        http.body = "{}";
        assertThat(ToolResult.run(() -> service.nutrition(date.toString(), null, null)).status())
                .isEqualTo("garmin_unavailable");
    }

    @Test
    void cycleAnalysisAndSensitiveFieldsAreOptIn() {
        http.body = """
                {"daySummary":{"dayInCycle":2,"analysis":{"perimenopauseDetected":false}},
                 "dayLog":{"flow":"light","sexualActivity":"protected","notes":"private","userProfilePk":9}}""";
        var date = LocalDate.parse("2025-04-01");
        var basic = gateway.cycle(date, CycleView.day, false, false);
        assertThat(basic).containsOnlyKeys("dayInCycle");
        var detailed = gateway.cycle(date, CycleView.day, true, true);
        assertThat(detailed.toString()).contains("analysis", "protected").doesNotContain("private", "userProfilePk");
    }

    private static final class Stub extends GarminHttpClient {
        static final JsonMapper JSON = JsonMapper.builder().build();
        String body = "[]";
        List<String> paths = new ArrayList<>();
        int failAt;

        Stub() {
            super("synthetic", "http://localhost");
        }

        @Override
        public JsonNode get(GarminRoute route, Map<String, ?> query, Object... values) {
            paths.add(route + Arrays.toString(values));
            if (paths.size() == failAt) {
                throw new GarminFailure("garmin_rate_limited");
            }
            return JSON.readTree(body);
        }
    }
}
