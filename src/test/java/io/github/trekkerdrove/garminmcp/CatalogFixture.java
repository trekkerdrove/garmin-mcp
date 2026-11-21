package io.github.trekkerdrove.garminmcp;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.trekkerdrove.garminmcp.infrastructure.external.garmin.GarminFitDecoderTest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/** The service paths are spelled independently of the production route table. */
final class CatalogFixture {
    static final String DAY = "2025-04-01";
    static final String LOT_1 = """
            running_distance compare_sleep_by_training_day garmin_search_activities garmin_get_activity
            garmin_get_activity_types garmin_get_activity_workouts garmin_get_wellness_sessions
            garmin_get_daily_summary garmin_get_wellness_trends garmin_get_sleep garmin_get_heart_rate garmin_get_hrv""";
    static final String LOT_2 = """
            garmin_get_activity_splits garmin_get_activity_zones garmin_get_activity_strength_summary
            garmin_get_steps garmin_get_stress garmin_get_hydration garmin_get_body_battery garmin_get_respiration
            garmin_get_spo2 garmin_get_training_effect garmin_get_vo2max garmin_get_fitness_age garmin_get_heart_rate_zones
            garmin_summarize_activities garmin_get_power_curve garmin_get_best_efforts garmin_get_weight
            garmin_get_weight_goals garmin_get_nutrition garmin_get_cycle garmin_get_profile_settings""";
    static final String LOT_3 = "garmin_analyze_activity_file garmin_get_fit_samples";
    static final Set<String> CATALOG = new LinkedHashSet<>();
    static {
        for (String lot : new String[]{"garmin_check_connection", LOT_1, LOT_2, LOT_3}) {
            CATALOG.addAll(java.util.List.of(lot.split("\\s+")));
        }
    }
    static final Set<String> ROUTES = java.util.concurrent.ConcurrentHashMap.newKeySet();
    static final AtomicInteger REQUESTS = new AtomicInteger();

    static void register(HttpServer server, String token) {
        route(server, token, "/activity-service/activity/", """
                {"activityId":42,"summaryDTO":{"startTimeLocal":"2025-04-01T19:30:00","distance":5000,"duration":1800,"trainingEffect":2.1},
                "activityTypeDTO":{"typeKey":"running"},"splitSummaries":[{"splitType":"RUN","distance":5000}]}""");
        route(server, token, "/activity-service/activity/activityTypes/simple", "[{\"key\":\"running\"}]");
        route(server, token, "/activity-service/activity/42/workouts", "[{\"workoutName\":\"Easy run\",\"steps\":[]}]");
        route(server, token, "/wellnessactivity-service/activity/summary/range/", """
                [{"calendarDate":{"localDate":"2025-04-01"},"wellnessActivityType":"HEALTH_MONITORING",
                  "summaryTypeDataList":[{"summaryType":"HEART_RATE","avgValue":64.5}],
                  "timeInZoneList":[{"zoneNumber":1,"millisInZone":3000}]}]""");
        route(server, token, "/usersummary-service/usersummary/daily", "{\"calendarDate\":\"2025-04-01\",\"totalSteps\":0}");
        route(server, token, "/usersummary-service/v2/dailysummaries", "{\"data\":[]}");
        route(server, token, "/usersummary-service/stats/steps/daily/", "[{\"calendarDate\":\"2025-04-01\",\"totalSteps\":0}]");
        route(server, token, "/usersummary-service/stats/stress/daily/", "[]");
        route(server, token, "/usersummary-service/stats/hydration/daily/", "[]");
        route(server, token, "/sleep-service/sleep/dailySleep", "{\"calendarDate\":\"2025-04-01\",\"sleepTimeSeconds\":25000}");
        route(server, token, "/sleep-service/sleep/dailySleepSummariesByDate", "[{\"calendarDate\":\"2025-04-02\",\"sleepTimeSeconds\":24000}]");
        route(server, token, "/wellness-service/wellness/dailyHeartRate", "{\"calendarDate\":\"2025-04-01\",\"restingHeartRate\":58}");
        route(server, token, "/wellness-service/wellness/bodyBattery/reports/daily", "[]");
        route(server, token, "/wellness-service/wellness/daily/spo2acclimation/", "{}");
        route(server, token, "/hrv-service/hrv/", "{}");
        route(server, token, "/hrv-service/hrv/daily/", "{\"hrvSummaries\":[]}");
        route(server, token, "/biometric-service/heartRateZones/v2", "[]");
        route(server, token, "/fitnessage-service/fitnessage/v2/", "[]");
        route(server, token, "/metrics-service/metrics/maxmet/daily/", "[]");
        route(server, token, "/weight-service/weight/all/", """
                [{"calendarDate":"2025-04-01","weight":71.5},{"calendarDate":"2025-04-01","weight":72.0}]""");
        route(server, token, "/weight-service/weight/range/", """
                {"dailyWeightSummaries":[{"summaryDate":"2025-04-01","numOfWeightEntries":2,"latestWeight":{"weight":72.0}}]}""");
        route(server, token, "/goal-service/goal/user/effective/weightgoal/", "{\"userGoals\":[{\"goalValue\":71000}]}");
        route(server, token, "/nutrition-service/food/logs/", """
                {"mealDate":"2025-04-01","dailyNutritionContent":{"calories":300,"protein":15},"mealDetails":[
                {"meal":{"mealId":7,"mealName":"Breakfast"},"mealNutritionContent":{"calories":200},"loggedFoods":[{"servingQty":1}]},
                {"meal":{"mealId":9,"mealName":"Dinner"},"mealNutritionContent":{"calories":100},"loggedFoods":[{"servingQty":1}]}]}""");
        route(server, token, "/periodichealth-service/menstrualcycle/dayview/", """
                {"daySummary":{"dayInCycle":2},"dayLog":{"flow":"light","userProfilePk":1234,"notes":"PRIVATE_FIELD"}}""");
        route(server, token, "/periodichealth-service/menstrualcycle/summary/", "{\"dayInCycle\":2}");
        route(server, token, "/userprofile-service/userprofile/settings", """
                {"timeZone":"Europe/Paris","measurementSystem":"metric","displayName":"PRIVATE_FIELD"}""");
        server.createContext("/download-service/files/activity/", ex -> {
            if (allowed(ex, token)) {
                ROUTES.add("/download-service/files/activity/");
                if (ex.getRequestURI().getPath().endsWith("/413")) {
                    bytes(ex, 200, "application/x-zip-compressed", new byte[4 * 1024 * 1024 + 1]);
                    return;
                }
                if (ex.getRequestURI().getPath().endsWith("/414")) {
                    ex.sendResponseHeaders(200, 0);
                    try (var stream = ex.getResponseBody()) {
                        byte[] block = new byte[8192];
                        for (int i = 0; i < 513; i++) stream.write(block);
                    }
                    return;
                }
                bytes(ex, 200, "application/x-zip-compressed", GarminFitDecoderTest.activityArchive());
            }
        });
    }

    private static void route(HttpServer server, String token, String path, String json) {
        server.createContext(path, ex -> {
            if (allowed(ex, token)) {
                ROUTES.add(path);
                String uri = ex.getRequestURI().getPath();
                int status = switch (uri.substring(uri.lastIndexOf('/') + 1)) {
                    case "401" -> 401;
                    case "403" -> 403;
                    case "404" -> 404;
                    case "429" -> 429;
                    default -> 200;
                };
                bytes(ex, status, "application/json", (status == 200 ? json : "PRIVATE_FIELD").getBytes(StandardCharsets.UTF_8));
            }
        });
    }

    static boolean allowed(HttpExchange ex, String token) throws IOException {
        REQUESTS.incrementAndGet();
        if (ex.getRequestMethod().equals("GET") && ("Bearer " + token).equals(ex.getRequestHeaders().getFirst("Authorization"))) {
            return true;
        }
        bytes(ex, 403, "application/json", new byte[0]);
        return false;
    }

    static void bytes(HttpExchange ex, int status, String type, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(status, body.length);
        try (var stream = ex.getResponseBody()) {
            stream.write(body);
        }
    }
}
