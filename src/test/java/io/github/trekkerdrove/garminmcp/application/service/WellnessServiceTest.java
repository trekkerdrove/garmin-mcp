package io.github.trekkerdrove.garminmcp.application.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import io.github.trekkerdrove.garminmcp.application.dto.GarminFailure;
import io.github.trekkerdrove.garminmcp.application.dto.ToolResult;
import io.github.trekkerdrove.garminmcp.application.port.out.WellnessReader.Metric;
import io.github.trekkerdrove.garminmcp.application.port.out.WellnessReader.Options;
import io.github.trekkerdrove.garminmcp.application.port.out.WellnessReader.View;
import io.github.trekkerdrove.garminmcp.infrastructure.external.garmin.GarminHttpClient;
import io.github.trekkerdrove.garminmcp.infrastructure.external.garmin.GarminRoute;
import io.github.trekkerdrove.garminmcp.infrastructure.external.garmin.GarminWellnessReader;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static io.github.trekkerdrove.garminmcp.infrastructure.external.garmin.GarminRoute.*;
import static org.assertj.core.api.Assertions.assertThat;

class WellnessServiceTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final FakeGarmin http = new FakeGarmin();
    private final WellnessService wellness = new WellnessService(new GarminWellnessReader(http));
    private static final String DAY = "2025-01-01";

    @Test
    void validatesDatesRangesAndSampleLimitsBeforeReading() {
        assertThat(report(View.STEPS, "2025-02-30", null, Options.BASIC).status()).isEqualTo("invalid_input");
        assertThat(report(View.STEPS, DAY, "2026-03-01", Options.BASIC).status()).isEqualTo("invalid_input");
        assertThat(report(View.HRV, DAY, "2025-01-02", options(7)).status()).isEqualTo("invalid_input");
        assertThat(report(View.HEART_RATE, DAY, null, options(201)).status()).isEqualTo("invalid_input");
        assertThat(http.calls).isEmpty();
    }

    @Test
    void splitsStepQueriesInto28DayWindowsAndDistinguishesZeroFromMissing() {
        http.fixtures.put(H3, "[{\"calendarDate\":\"2025-01-01\",\"totalSteps\":0,\"profileId\":128}]");
        http.lastWindowEmpty = true;
        var result = wellness.report(View.STEPS, DAY, "2025-01-30", Options.BASIC, true, false);
        assertThat(result.status()).isEqualTo("ok");
        assertThat(http.calls).containsExactly("H3 {} [2025-01-01, 2025-01-28]", "H3 {} [2025-01-29, 2025-01-30]");
        var data = data(result);
        assertThat(data.path("measuredDays").asInt()).isEqualTo(1);
        assertThat(data.path("missingDates").size()).isEqualTo(29);
        assertThat(data.path("weeks").get(0).path("meanPerMeasuredDay").asDouble()).isZero();
        assertThat(data.toString()).doesNotContain("profileId");
    }

    @Test
    void noReadAfterRateLimitAndNoClaimOfCompleteness() {
        http.fixtures.put(H3, "[]");
        http.failOnCall = 2;
        var result = report(View.STEPS, DAY, "2025-04-20", Options.BASIC);
        assertThat(result.status()).isEqualTo("rate_limited");
        assertThat(data(result).path("missingDates").size()).isEqualTo(28);
        assertThat(data(result).path("failedFrom").asString()).isEqualTo("2025-01-29");
        assertThat(http.calls).hasSize(2);
    }

    @Test
    void sleepDetailProjectsNapsScoresAndSafeEnrichmentWithExactDates() {
        http.fixtures.put(H6, """
                {"calendarDate":"2025-01-01","sleepTimeSeconds":18000,"userNote":"private",
                 "sleepScores":{"overall":{"value":77}},"dailyNapDTOS":[{"napTimeSec":20,"deviceId":999}],
                 "averageRespirationValue":15}
                """);
        http.fixtures.put(H11, "{\"hrvSummary\":{\"calendarDate\":\"2025-01-01\",\"lastNightAvg\":53}}");
        http.fixtures.put(H8, "{\"calendarDate\":{\"localDate\":\"2025-01-01\"},\"restingHeartRate\":56}");
        var result = wellness.report(View.SLEEP, DAY,null,new Options(List.of(),0,0,true,false,false,null,false),false,true);
        assertThat(result.status()).isEqualTo("ok");
        assertThat(data(result).toString()).contains("nightHrvMs","restingHeartRateBpm","18000","napTimeSec","scores")
                .doesNotContain("private","userNote","deviceId");
        http.fixtures.put(H11, "{\"hrvSummary\":{\"calendarDate\":\"2025-01-02\",\"lastNightAvg\":53}}");
        var partial = wellness.report(View.SLEEP,DAY,null,Options.BASIC,false,true);
        assertThat(data(partial).toString()).contains("enrichmentUnavailable","18000").doesNotContain("nightHrvMs");
    }

    @Test
    void selectedV2MetricsAndWeeklyWeightedIntensityUseOnlyCompleteDays() {
        http.fixtures.put(H2, """
                {"data":[{"calendarDate":"2025-01-01","calories":{"burnedTotal":2000},"intensityMinutes":{"moderate":5,"vigorous":4}},
                         {"calendarDate":"2025-01-02","intensityMinutes":{"moderate":8}}]}
                """);
        var options = new Options(List.of(Metric.intensityMinutes),0,0,false,false,false,null,false);
        var data = data(wellness.report(View.TRENDS, DAY, "2025-01-02", options, true, false));
        var week = data.path("weeks").get(0);
        assertThat(week.path("weightedIntensityMinutes").asDouble()).isEqualTo(13);
        assertThat(week.path("measuredDays").asInt()).isEqualTo(1);
        assertThat(data.toString()).doesNotContain("burnedTotal");
    }

    @Test
    void samplesUseDescriptorsRejectInvalidReadingsAndExposeTruncation() {
        http.fixtures.put(H8, """
                {"calendarDate":"2025-01-01","heartRateValueDescriptors":[{"index":1,"key":"timestamp"},{"index":0,"key":"heartrate"}],
                 "heartRateValues":[[0,1000],[72,2000],[80,3000],[-1,4000]]}
                """);
        var result = report(View.HEART_RATE, DAY, null, options(1));
        assertThat(result.status()).isEqualTo("truncated");
        var values = values(result);
        assertThat(values.path("samples").get(0).path("timestamp").asInt()).isEqualTo(2000);
        assertThat(values.path("meanOfValidReadingsBpm").asDouble()).isEqualTo(76);
        http.fixtures.put(H10, """
                {"averageSpO2":98,"spO2HourlyAveragesDescriptorList":[{"spo2ValueDescIndex":0,"spo2ValueDescKey":"timestamp"},
                {"spo2ValueDescIndex":1,"spo2ValueDescKey":"spo2Level"}],"spO2HourlyAverages":[[42,99,123456]]}
                """);
        assertThat(values(report(View.SPO2, DAY, null,new Options(List.of(),1,0,false,false,false,null,true))).toString())
                .contains("spo2Percent","99").doesNotContain("123456");
    }

    @Test
    void hrvTreats204AsMissingAndAveragesOnlyMeasuredNights() {
        http.fixtures.put(H11, "null");
        assertThat(report(View.HRV, DAY, null,Options.BASIC).status()).isEqualTo("no_data");
        http.fixtures.put(H12, """
                {"hrvSummaries":[{"calendarDate":"2025-01-01","lastNightAvg":30},
                                 {"calendarDate":"2025-01-03","lastNightAvg":50}]}
                """);
        var result = report(View.HRV, DAY, "2025-01-03", Options.BASIC);
        assertThat(data(result).path("meanOfMeasuredNightlyAveragesMs").asDouble()).isEqualTo(40);
        assertThat(data(result).path("missingDates").toString()).contains("2025-01-02");
    }

    @Test
    void respirationRetainsDaytimeAndNighttimeScopeAndStressUsesDocumentedDurationUnits() {
        http.fixtures.put(H1, "{\"avgWakingRespirationValue\":15,\"highestRespirationValue\":19,\"maxStressLevel\":75}");
        http.fixtures.put(H6, "{\"calendarDate\":\"2025-01-01\",\"averageRespirationValue\":12,\"sleepTimeSeconds\":18000}");
        var values = values(report(View.RESPIRATION, DAY,null,Options.BASIC));
        assertThat(values.toString()).contains("awake","sleep","daily","12","15").doesNotContain("sleepTimeSeconds");
        http.fixtures.put(H4,"[{\"calendarDate\":\"2025-01-01\",\"values\":{\"overallStressLevel\":22,\"restStressDuration\":1200}}]");
        var stress = wellness.report(View.STRESS, DAY,null, options(0),true,false);
        assertThat(data(stress).toString()).contains("1200","75","meanPerMeasuredDay");
    }

    @Test
    void undatedSleepPlaceholderKeepsSixNightsAndTheirRespiration() {
        String placeholder = """
                {"calendarDate":null,"sleepTimeSeconds":null,"deepSleepSeconds":null,"averageRespirationValue":null,
                 "sleepStartTimestampGMT":null,"sleepEndTimestampGMT":null,
                 "sleepNeed":{"calendarDate":"2025-01-01","baseline":480,"actual":480},
                 "nextSleepNeed":{"calendarDate":"2025-01-02","baseline":480}}
                """;
        var nights = new ArrayList<String>();
        var days = new ArrayList<String>();
        nights.add(placeholder);
        for (LocalDate day = LocalDate.parse(DAY); !day.isAfter(LocalDate.parse("2025-01-07")); day = day.plusDays(1)) {
            days.add("""
                    {"calendarDate":"%s","respiration":{"avgValue":17}}
                    """.formatted(day));
            if (!day.toString().equals(DAY)) {
                nights.add("""
                        {"calendarDate":"%s","sleepTimeSeconds":24000,"averageRespirationValue":13}
                        """.formatted(day));
            }
        }
        http.fixtures.put(H6, placeholder);
        http.fixtures.put(H7, "[" + String.join(",", nights) + "]");
        http.fixtures.put(H2, "{\"data\":[" + String.join(",", days) + "]}");

        var single = report(View.SLEEP, DAY, null, Options.BASIC);
        assertThat(single.status()).isEqualTo("no_data");
        assertThat(data(single).path("missingDates")).isEqualTo(JSON.readTree("[\"2025-01-01\"]"));

        var sleep = report(View.SLEEP, DAY, "2025-01-07", Options.BASIC);
        assertThat(sleep.status()).isEqualTo("ok");
        assertThat(data(sleep).path("measuredDays").asInt()).isEqualTo(6);
        assertThat(data(sleep).path("missingDates")).isEqualTo(JSON.readTree("[\"2025-01-01\"]"));
        assertThat(data(sleep).has("unknownDates")).isFalse();
        assertThat(data(sleep).path("observations")).hasSize(6).allSatisfy(night ->
                assertThat(night.at("/values/sleepTimeSeconds").asInt()).isEqualTo(24000));

        var respiration = report(View.RESPIRATION, DAY, "2025-01-07", Options.BASIC);
        assertThat(respiration.status()).isEqualTo("ok");
        var respirationData = data(respiration);
        assertThat(respirationData.has("enrichmentUnavailable")).isFalse();
        var observations = respirationData.path("observations");
        assertThat(observations).hasSize(7).allSatisfy(day ->
                assertThat(day.at("/values/daily/avgValue").asInt()).isEqualTo(17));
        assertThat(observations.get(0).at("/values/sleep").isMissingNode()).isTrue();
        assertThat(observations).filteredOn(day -> day.at("/values/sleep").isObject()).hasSize(6)
                .allSatisfy(day -> assertThat(day.at("/values/sleep/averageRespirationValue").asInt()).isEqualTo(13));
    }

    @Test
    void vo2CarriesOnlyMeasurementsInRangeAndKeepsSourceDate() {
        http.fixtures.put(H15, """
                [{"cycling":{"calendarDate":"2025-01-01","vo2MaxValue":45}},
                 {"rowing":{"calendarDate":"2025-01-03","vo2MaxValue":47}}]
                """);
        var result = wellness.report(View.VO2, DAY,"2025-01-03", Options.BASIC,false,true);
        var series = data(result).path("dailySeries");
        assertThat(series.size()).isEqualTo(4);
        assertThat(series.get(1).path("carriedForward").asBoolean()).isTrue();
        assertThat(series.get(1).path("measuredDate").asString()).isEqualTo(DAY);
        assertThat(data(result).path("measuredDays").asInt()).isEqualTo(2);
    }

    @Test
    void projectsHydrationBatteryAgeAndZonesWithoutIdentifiers() {
        http.fixtures.put(H5,"[{\"calendarDate\":\"2025-01-01\",\"valueInML\":0,\"sweatLossInML\":500,\"userId\":234}]");
        assertThat(values(report(View.HYDRATION,DAY,null,Options.BASIC)).toString()).contains("valueInML","500").doesNotContain("userId");
        http.fixtures.put(H9,"[{\"date\":\"2025-01-01\",\"charged\":30,\"bodyBatteryActivityEvent\":[{\"eventType\":\"NAP\",\"deviceId\":123}]}]");
        assertThat(values(report(View.BATTERY,DAY,null,Options.BASIC)).toString()).contains("30").doesNotContain("NAP");
        assertThat(values(report(View.BATTERY,DAY,null,new Options(List.of(),0,0,false,true,false,null,false))).toString())
                .contains("NAP").doesNotContain("deviceId");
        http.fixtures.put(H14,"[{\"invalidReason\":\"USER_EMPTY_AGE\",\"components\":{\"restingHeartRate\":{\"value\":50}}}]");
        assertThat(values(report(View.FITNESS_AGE,DAY,null,Options.BASIC)).toString()).contains("USER_EMPTY_AGE").doesNotContain("components");
        http.fixtures.put(H13,"[{\"sport\":\"RUNNING\",\"zone1Floor\":101},{\"sport\":\"CYCLING\",\"zone1Floor\":98}]");
        var zones = report(View.ZONES,DAY,null,new Options(List.of(),0,0,false,false,false,"CYCLING",false));
        assertThat(data(zones).toString()).contains("CYCLING","98").doesNotContain("RUNNING");
        assertThat(http.calls.getLast()).contains("generateDefaults=false");
    }

    @Test
    void fitnessAgeAcceptsBothPersonalApiShapesAndKeepsTheRequestedAsOfDate() {
        String measure = """
                {"fitnessAge":41.5,"lastUpdated":"2024-12-30T09:15:00Z","components":{"rhr":{"value":54}}}""";
        for (String body : List.of(measure, "[" + measure + "]")) {
            http.fixtures.put(H14, body);
            var result = report(View.FITNESS_AGE, DAY, null, options(0));
            assertThat(result.status()).isEqualTo("ok");
            assertThat(data(result).at("/observations/0/date").asString()).isEqualTo(DAY);
            assertThat(values(result).path("fitnessAge").asDouble()).isEqualTo(41.5);
            assertThat(values(result).path("lastUpdated").asString()).isEqualTo("2024-12-30T09:15:00Z");
            assertThat(values(result).at("/components/rhr/value").asInt()).isEqualTo(54);
        }
        for (String body : List.of("42", "[false]", "{\"calendarDate\":\"2025-01-02\",\"fitnessAge\":42}")) {
            http.fixtures.put(H14, body);
            assertThat(report(View.FITNESS_AGE, DAY, null, Options.BASIC).status()).isEqualTo("garmin_unavailable");
        }
    }

    @Test
    void goalsNeverCountAsMeasurementsAndStayAvailable() {
        http.fixtures.put(H1, """
                {"dailyStepGoal":8000,"intensityMinutesGoal":150,"hydrationGoalInML":2000}
                """);
        var goalOnly = report(View.SUMMARY, DAY, null, Options.BASIC);
        assertThat(goalOnly.status()).isEqualTo("no_data");
        assertThat(data(goalOnly).path("goals").get(0).path("values").path("dailyStepGoal").asInt()).isEqualTo(8000);
        assertThat(data(goalOnly).path("missingDates").toString()).contains(DAY);
        http.fixtures.put(H2, """
                {"data":[{"calendarDate":"2025-01-01","movement":{"steps":{"goal":1000}}},
                         {"calendarDate":"2025-01-02","movement":{"steps":{"goal":1200, "value":0}}}]}
                """);
        var trends = data(report(View.TRENDS, DAY, "2025-01-02", Options.BASIC));
        assertThat(trends.path("goals").size()).isEqualTo(2);
        assertThat(trends.path("observations").toString()).contains("2025-01-02","value").doesNotContain("goal");
        assertThat(trends.path("measuredDays").asInt()).isEqualTo(1);
    }

    @Test
    void countsEachDayOnceAndRejectsConflictingDuplicateDays() {
        http.fixtures.put(H3, """
                [{"calendarDate":"2025-01-01","totalSteps":12},{"calendarDate":"2025-01-01","totalSteps":12},
                 {"calendarDate":"2025-01-02","totalSteps":0}]
                """);
        var report = data(wellness.report(View.STEPS, DAY, "2025-01-02", Options.BASIC, true, false));
        var week = report.path("weeks").get(0);
        assertThat(week.path("totalSteps").asInt()).isEqualTo(12);
        assertThat(week.path("meanPerMeasuredDay").asInt()).isEqualTo(6);
        assertThat(report.path("observations").size()).isEqualTo(2);
        http.fixtures.put(H3, """
                [{"calendarDate":"2025-01-01","totalSteps":12},{"calendarDate":"2025-01-01","totalSteps":11}]
                """);
        var conflict = wellness.report(View.STEPS, DAY, null, Options.BASIC, true, false);
        assertThat(conflict.status()).isEqualTo("garmin_unavailable");
        assertThat(data(conflict).path("unknownDates").toString()).contains(DAY);
        assertThat(data(conflict).has("weeks")).isFalse();
    }

    @Test
    void failedHrvOnlyStopsRestingHeartRateOnAnAccessOrQuotaFailure() {
        http.fixtures.put(H6, "{\"calendarDate\":\"2025-01-01\",\"sleepTimeSeconds\":28800}");
        http.fixtures.put(H8, "{\"calendarDate\":\"2025-01-01\",\"restingHeartRate\":62}");
        http.failOnCall = 2;
        http.failStatus = "garmin_unavailable";
        var partial = wellness.report(View.SLEEP, DAY, null, Options.BASIC, false, true);
        assertThat(values(partial).path("restingHeartRateBpm").path("value").asInt()).isEqualTo(62);
        assertThat(data(partial).path("enrichmentUnavailable").get(0).path("from").asString()).isEqualTo(DAY);
        for (String terminal : List.of("garmin_access_denied", "garmin_rate_limited")) {
            http.calls.clear();
            http.failStatus = terminal;
            var blocked = wellness.report(View.SLEEP, DAY, null, Options.BASIC, false, true);
            assertThat(blocked.status()).isEqualTo("ok");
            assertThat(http.calls).hasSize(2);
            assertThat(data(blocked).path("enrichmentUnavailable").toString()).contains(terminal, "not_requested");
        }
    }

    @Test
    void failedRespirationWindowKeepsCompletedNightsAndMarksOnlyUnassessedDates() {
        http.fixtures.put(H2, "{\"data\":[]}");
        http.fixtures.put(H7, "[{\"calendarDate\":\"2025-01-01\",\"averageRespirationValue\":13}]");
        http.failOnCall = 3;
        http.failStatus = "garmin_unavailable";
        var result = report(View.RESPIRATION, DAY, "2025-02-01", Options.BASIC);
        var data = data(result);
        assertThat(result.status()).isEqualTo("ok");
        assertThat(values(result).path("sleep").path("averageRespirationValue").asInt()).isEqualTo(13);
        assertThat(data.path("missingDates").size()).isEqualTo(30);
        assertThat(data.path("unknownDates").size()).isEqualTo(1);
        assertThat(data.path("unknownDates").get(0).asString()).isEqualTo("2025-02-01");
    }

    @Test
    void stressFallbackReportsUnknownCoverageAndPreservesMeasuredPrimaryDays() {
        http.fixtures.put(H4,"[{\"calendarDate\":\"2025-01-01\",\"values\":{\"overallStressLevel\":22}}]");
        http.failOnCall = 2;
        http.failStatus = "garmin_unavailable";
        var result = report(View.STRESS, DAY, "2025-01-03", options(0));
        var data = data(result);
        assertThat(result.status()).isEqualTo("ok");
        assertThat(data.path("measuredDays").asInt()).isEqualTo(1);
        assertThat(data.path("unknownDates").size()).isEqualTo(2);
        assertThat(data.path("missingDates")).isEmpty();
        assertThat(data.path("enrichmentUnavailable").get(0).path("through").asString()).isEqualTo("2025-01-03");
        http.calls.clear();
        http.fixtures.put(H4,"[]");
        var unknown = report(View.STRESS, DAY, "2025-01-03", options(0));
        assertThat(unknown.status()).isEqualTo("garmin_unavailable");
        assertThat(data(unknown).path("unknownDates").size()).isEqualTo(3);
    }

    @Test
    void unconfirmedDatesNeverSupplyAJoinedNightlyMeasurement() {
        http.fixtures.put(H6, "{\"calendarDate\":\"2025-01-01\",\"sleepTimeSeconds\":21000}");
        http.fixtures.put(H11, "{\"hrvSummary\":{\"lastNightAvg\":88}}");
        http.fixtures.put(H8, "{\"calendarDate\":{\"localDate\":\"invalid\"},\"restingHeartRate\":53}");
        var result = wellness.report(View.SLEEP, DAY, null, Options.BASIC, false, true);
        assertThat(result.status()).isEqualTo("ok");
        assertThat(values(result).toString()).contains("21000").doesNotContain("nightHrvMs", "restingHeartRateBpm");
        assertThat(data(result).path("enrichmentUnavailable").size()).isEqualTo(2);
        http.fixtures.put(H2, "{\"data\":[{\"calendarDate\":\"2025-01-01\",\"respiration\":{\"avgValue\":18}}]}");
        http.fixtures.put(H7, "[{\"averageRespirationValue\":12}]");
        var respiration = report(View.RESPIRATION, DAY, "2025-01-02", Options.BASIC);
        assertThat(values(respiration).toString()).contains("daily").doesNotContain("sleep");
        assertThat(data(respiration).path("unknownDates").get(0).asString()).isEqualTo("2025-01-02");
    }

    @Test
    void hrvStatusAloneNeverBecomesAMeasuredDay() {
        http.fixtures.put(H11, "{\"hrvSummary\":{\"calendarDate\":\"2025-01-01\",\"status\":\"NONE\"}}");
        var result = report(View.HRV, DAY, null, Options.BASIC);
        assertThat(result.status()).isEqualTo("no_data");
        assertThat(values(result).path("status").asString()).isEqualTo("NONE");
        assertThat(data(result).path("measuredDays").asInt()).isZero();
        assertThat(data(result).path("missingDates").get(0).asString()).isEqualTo(DAY);
        assertThat(data(result).has("meanOfMeasuredNightlyAveragesMs")).isFalse();
    }

    private static Options options(int samples) {
        return new Options(List.of(),samples,0,true,false,false,null,false);
    }

    private ToolResult report(View view, String start, String end, Options options) {
        return wellness.report(view,start,end,options,false,false);
    }

    private static JsonNode data(ToolResult result) {
        return JSON.valueToTree(result.data());
    }

    private static JsonNode values(ToolResult result) {
        return data(result).path("observations").get(0).path("values");
    }

    static final class FakeGarmin extends GarminHttpClient {
        final Map<GarminRoute, String> fixtures = new EnumMap<>(GarminRoute.class);
        final List<String> calls = new ArrayList<>();
        boolean lastWindowEmpty;
        int failOnCall;
        String failStatus = "rate_limited";

        FakeGarmin() {
            super("fake", "http://localhost");
        }

        @Override
        public JsonNode get(GarminRoute route, Map<String,?> query, Object... pathValues) {
            calls.add(route + " " + query + " " + List.of(pathValues));
            if (calls.size() == failOnCall) throw new GarminFailure(failStatus);
            if (lastWindowEmpty && List.of(pathValues).contains(LocalDate.parse("2025-01-29"))) return JSON.readTree("[]");
            String fixture = fixtures.get(route);
            if (fixture == null) throw new AssertionError("Unexpected Garmin route " + route);
            return JSON.readTree(fixture);
        }
    }
}
