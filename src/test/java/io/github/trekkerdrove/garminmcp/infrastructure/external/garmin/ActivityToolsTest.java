package io.github.trekkerdrove.garminmcp.infrastructure.external.garmin;

import io.github.trekkerdrove.garminmcp.application.dto.ActivityFilters;
import io.github.trekkerdrove.garminmcp.application.dto.GarminFailure;
import io.github.trekkerdrove.garminmcp.application.dto.ToolResult;
import io.github.trekkerdrove.garminmcp.application.service.ActivityService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import static io.github.trekkerdrove.garminmcp.infrastructure.external.garmin.GarminRoute.*;
import static org.assertj.core.api.Assertions.assertThat;

class ActivityToolsTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-15T23:15:00Z"), ZoneOffset.UTC);
    private static final String TYPES = """
            [{"key":"running"},{"key":"trail_running","parent":"running"},{"key":"multisport"},{"key":"walking"}]
            """;

    private static ActivityService service(BiFunction<GarminRoute, Map<String, ?>, String> fixture) {
        var http = new GarminHttpClient("unused", "http://127.0.0.1") {
            @Override public JsonNode get(GarminRoute route, Map<String, ?> query, Object... values) {
                return JSON.readTree(fixture.apply(route, query));
            }
        };
        return new ActivityService(new GarminActivities(http), CLOCK);
    }

    @Test
    void countsRunningOnlyOnceAcrossPagesAndMultisportUsingParisCalendar() {
        String run = activity(11, 10,"trail_running","2026-11-09", 1500);
        String parentRun = activity(20, 0,"running","2026-11-15", 2000);
        var service = service((route, query) -> {
            if (route == A4) return TYPES;
            assertThat(route).isEqualTo(A1);
            assertThat(query.get("startDate").toString()).isEqualTo("2026-11-09");
            assertThat(query.get("endDate").toString()).isEqualTo("2026-11-15");
            assertThat(query.get("excludeChildren")).isEqualTo(false);
            if (query.get("start").equals(0)) return "[" + (run + ",").repeat(49) + activity(10, 0,"multisport","2026-11-09", 9000) + "]";
            return "[" + parentRun + "," + activity(21, 20,"running","2026-11-15", 1000) + "," + run + "]";
        });
        var result = service.running(null, null);
        assertThat(result.status()).isEqualTo("ok");
        var resultData = JSON.valueToTree(result.data());
        assertThat(resultData.path("distanceKm").asDouble()).isEqualTo(3.5);
        assertThat(resultData.path("runCount").asInt()).isEqualTo(2);
    }

    @Test
    void includesLastNightAndCountsMeasuredZeroAndMissingSeparately() {
        var service = service((route, query) -> {
            if (route == A1) return "[" + activity(1, 0,"walking","2026-11-01", 10) + "]";
            assertThat(route).isEqualTo(H7);
            assertThat(query.get("startDate").toString()).isEqualTo("2026-11-02");
            assertThat(query.get("endDate").toString()).isEqualTo("2026-11-04");
            return """
                    [{"calendarDate":"2026-11-02","sleepTimeSeconds":0,"napTimeSeconds":100},
                     {"calendarDate":"2026-11-04","sleepTimeSeconds":3600}]
                    """;
        });
        var data = JSON.valueToTree(service.compareSleep("2026-11-01","2026-11-03").data());
        assertThat(data.at("/training/meanSleepSeconds").asDouble()).isZero();
        assertThat(data.at("/rest/missingNights").asInt()).isOne();
        assertThat(data.at("/rest/measuredNights").asInt()).isOne();
        assertThat(data.path("trainingMinusRestSeconds").asDouble()).isEqualTo(-3600);
    }

    @Test
    void incompleteOrFailedReadsNeverEmitTotals() {
        var service = service((route, query) -> route == A4 ? TYPES : "[" +
                String.join(",", java.util.Collections.nCopies(50, activity(1, 0,"running","2026-11-09", 20))) + "]");
        var incomplete = service.running(null, null);
        assertThat(incomplete.status()).isEqualTo("truncated");
        assertThat(JSON.valueToTree(incomplete.data()).has("distanceKm")).isFalse();
        var failed = service((route, query) -> {
            if (route == A1) return "[]";
            throw new GarminFailure("garmin_rate_limited");
        });
        assertThat(ToolResult.run(() -> failed.compareSleep(null, null))).isEqualTo(new ToolResult("garmin_rate_limited", null));
    }

    @Test
    void rejectsBadInputsAndNeverCallsUpstream() {
        var service = service((route, query) -> { throw new AssertionError("unexpected Garmin call"); });
        assertThat(ToolResult.run(() -> service.wellness("2026-01-01", "2026-12-31", 101)).status()).isEqualTo("invalid_input");
        assertThat(ToolResult.run(() -> service.search("2026-02-30", null, null, null, null)).status()).isEqualTo("invalid_input");
        assertThat(ToolResult.run(() -> service.performance("2026-01-01","2026-01-02", null,"totals", List.of("secret"))).status()).isEqualTo("invalid_input");
        assertThat(ToolResult.run(() -> service.zones(42,"any")).status()).isEqualTo("invalid_input");
    }

    @Test
    void choosesPowerAndEffortsAcrossActivitiesAndPreservesSourceId() {
        String base = activity(1, 0,"running","2026-11-01", 10000);
        String second = activity(2, 0,"running","2026-11-02", 9000);
        var service = service((route, query) -> route == A4 ? TYPES : "[" +
                base.substring(0, base.length()-1) + ",\"maxAvgPower_60\":250,\"fastestSplit_1000\":240}," +
                second.substring(0, second.length()-1) + ",\"maxAvgPower_60\":300,\"fastestSplit_1000\":300}]");
        var power = JSON.valueToTree(service.performance("2026-11-01","2026-11-02","running","power", null).data());
        assertThat(power.at("/efforts/60s/activityId").asLong()).isEqualTo(2);
        var efforts = JSON.valueToTree(service.performance("2026-11-01","2026-11-02","running","best", null).data());
        assertThat(efforts.at("/efforts/fastestSplit_1000/value").asDouble()).isEqualTo(240);
        assertThat(efforts.at("/efforts/fastestSplit_1000/activityId").asLong()).isEqualTo(1);
        var totals = JSON.valueToTree(service.performance("2026-11-01","2026-11-02", null,"totals", List.of("distanceMeters")).data());
        assertThat(totals.at("/bySport/running/totalsOfMeasuredActivities/distanceMeters").asDouble()).isEqualTo(19000);
    }

    @Test
    void joinsZonesAndStrengthOnExactIdAndKeepsPrivateFieldsOut() {
        var service = service((route, query) -> {
            if (route == A2) return """
                    {"activityId":3,"summaryDTO":{"startTimeLocal":"2026-11-01T10:00:00.000","startLatitude":50.0}}
                    """;
            assertThat(query.get("excludeChildren")).isEqualTo(false);
            return """
                    [{"activityId":2,"activityType":{"typeKey":"running"},"startTimeLocal":"2026-11-01 08:00:00","hrTimeInZone_1":30},
                     {"activityId":3,"activityType":{"typeKey":"running"},"startTimeLocal":"2026-11-01 10:00:00",
                      "ownerDisplayName":"private owner","hrTimeInZone_1":0,"powerTimeInZone_2":100,"totalReps":2,
                      "summarizedExerciseSets":[{"duration":2000,"reps":2,"maxWeight":5000}]}]
                    """;
        });
        var zones = JSON.valueToTree(service.zones(3,"heart_rate").data());
        assertThat(zones.at("/heartRateSecondsPerZone/1").asDouble()).isZero();
        assertThat(zones.has("powerSecondsPerZone")).isFalse();
        var sets = JSON.valueToTree(service.searchDetails(3,"strength").data());
        assertThat(sets.at("/exercises/0/maxWeightGrams").asInt()).isEqualTo(5000);
        assertThat(sets.toString()).doesNotContain("private owner","startLatitude");
    }

    @Test
    void returnsGarminRepeatInstructionsWithoutExpandingThem() {
        var service = service((route, query) -> {
            assertThat(route).isEqualTo(A5);
            return """
                    [{"workoutName":"Track","sport":"RUNNING","steps":[
                      {"stepIndex":0,"durationType":"DISTANCE","durationValue":400},
                      {"stepIndex":1,"durationType":"REPEAT_UNTIL_STEPS_CMPLT","durationValue":0,"targetValue":5}]}]
                    """;
        });
        var result = service.workouts(4, 1);
        var data = JSON.valueToTree(result.data());
        assertThat(result.status()).isEqualTo("ok");
        assertThat(data.at("/workouts/0/steps").size()).isEqualTo(2);
        assertThat(data.at("/workouts/0/steps/1/repeatFromStepIndex").asInt()).isZero();
        assertThat(data.at("/workouts/0/steps/1/repeatCount").asInt()).isEqualTo(5);
    }

    @Test
    void distinguishesUnconfirmedAbsenceFromConfirmedEmptyAndZeroWatts() {
        var missing = service((route, query) -> route == A4 ? TYPES : "null");
        assertThat(ToolResult.run(() -> missing.running(null, null)).status()).isEqualTo("garmin_unavailable");
        var empty = service((route, query) -> route == A4 ? TYPES : "[]");
        assertThat(JSON.valueToTree(empty.running(null, null).data()).path("distanceKm").asDouble()).isZero();
        String run = activity(8, 0, "running", "2026-11-09", 20);
        var zero = service((route, query) -> route == A4 ? TYPES
                : "[" + run.substring(0, run.length() - 1) + ",\"maxAvgPower_60\":0}]");
        var power = JSON.valueToTree(zero.performance("2026-11-09", "2026-11-09", null, "power", null).data());
        assertThat(power.at("/efforts/60s/value").isNumber()).isTrue();
        assertThat(power.at("/efforts/60s/value").asDouble()).isZero();
    }

    @Test
    void incompleteAndCyclicSportFamiliesCannotYieldZeroOrPartialTotals() {
        for (String taxonomy : List.of(
                """
                [{"key":"running","parent":null}]
                """,
                """
                [{"key":"running","parent":null},{"key":"new_sport","parent":"missing"}]
                """,
                """
                [{"key":"running","parent":null},{"key":"new_sport","parent":"other"},{"key":"other","parent":"new_sport"}]
                """,
                """
                [{"key":"running","parent":null},{"key":"new_sport","parent":"running"},{"key":"new_sport","parent":null}]
                """)) {
            var service = service((route, query) -> route == A4 ? taxonomy :
                    "[" + activity(1, 0, "running", "2026-11-09", 1000) + ","
                            + activity(2, 0, "new_sport", "2026-11-09", 1000) + "]");
            assertThat(ToolResult.run(() -> service.running(null, null))).isEqualTo(new ToolResult("garmin_unavailable", null));
            assertThat(ToolResult.run(() -> service.performance("2026-11-09", "2026-11-09", "running", "totals", null)))
                    .isEqualTo(new ToolResult("garmin_unavailable", null));
        }
    }

    @Test
    void searchUsesDocumentedSportKeysAndCaseInsensitiveSort() {
        var service = service((route, query) -> {
            assertThat(route).isEqualTo(A1);
            assertThat(query.get("activityType")).isEqualTo("running");
            assertThat(query.get("activitySubType")).isEqualTo("trail_running");
            assertThat(query.get("sortOrder")).isEqualTo("desc");
            return "[]";
        });
        var filters = JSON.readValue("""
                {"sortOrder":"DEsC","activityType":"running","activitySubType":"trail_running"}
                """, ActivityFilters.class);
        assertThat(service.search(null, null, null, null, filters).status()).isEqualTo("no_data");
        var bad = JSON.readValue("""
                {"sortOrder":"sideways"}
                """, ActivityFilters.class);
        assertThat(ToolResult.run(() -> service.search(null, null, null, null, bad)).status()).isEqualTo("invalid_input");
    }

    private static String activity(int id, int parent, String sport, String date, int distance) {
        return """
                {"activityId":%s,"parentId":%s,"activityType":{"typeKey":"%s"},"startTimeLocal":"%s 23:15:00","distance":%s}"""
                .formatted(id, parent, sport, date, distance);
    }
}
