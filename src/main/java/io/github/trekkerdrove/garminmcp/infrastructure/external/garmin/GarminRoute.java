package io.github.trekkerdrove.garminmcp.infrastructure.external.garmin;

/** Documented Garmin Personal API operations, keyed by the project catalogue. */
public enum GarminRoute {
    A1("/activitylist-service/activities/search/activities", Shape.ARRAY),
    A2("/activity-service/activity/{activityId}", Shape.OBJECT),
    A3("/download-service/files/activity/{activityId}", Shape.BINARY),
    A4("/activity-service/activity/activityTypes/simple", Shape.ARRAY),
    A5("/activity-service/activity/{activityId}/workouts", Shape.ARRAY),
    A6("/wellnessactivity-service/activity/summary/range/{fromDate}/{untilDate}", Shape.ARRAY),
    H1("/usersummary-service/usersummary/daily", Shape.OBJECT),
    H2("/usersummary-service/v2/dailysummaries", Shape.OBJECT),
    H3("/usersummary-service/stats/steps/daily/{startDate}/{endDate}", Shape.ARRAY),
    H4("/usersummary-service/stats/stress/daily/{startDate}/{endDate}", Shape.ARRAY),
    H5("/usersummary-service/stats/hydration/daily/{startDate}/{endDate}", Shape.ARRAY),
    H6("/sleep-service/sleep/dailySleep", Shape.OBJECT),
    H7("/sleep-service/sleep/dailySleepSummariesByDate", Shape.ARRAY),
    H8("/wellness-service/wellness/dailyHeartRate", Shape.OBJECT),
    H9("/wellness-service/wellness/bodyBattery/reports/daily", Shape.ARRAY),
    H10("/wellness-service/wellness/daily/spo2acclimation/{calendarDate}", Shape.OBJECT),
    H11("/hrv-service/hrv/{calendarDate}", Shape.OBJECT),
    H12("/hrv-service/hrv/daily/{fromDate}/{toDate}", Shape.OBJECT),
    H13("/biometric-service/heartRateZones/v2", Shape.ARRAY),
    H14("/fitnessage-service/fitnessage/v2/{calendarDate}", Shape.ARRAY),
    H15("/metrics-service/metrics/maxmet/daily/{startDate}/{endDate}", Shape.ARRAY),
    H16("/weight-service/weight/all/{startDate}/{endDate}", Shape.ARRAY),
    H17("/weight-service/weight/range/{startDate}/{endDate}", Shape.OBJECT),
    H18("/goal-service/goal/user/effective/weightgoal/{startDate}/{endDate}", Shape.OBJECT),
    H19("/nutrition-service/food/logs/{date}", Shape.OBJECT),
    H20("/periodichealth-service/menstrualcycle/dayview/{calendarDate}", Shape.OBJECT),
    H21("/periodichealth-service/menstrualcycle/summary/{calendarDate}", Shape.OBJECT),
    P1("/userprofile-service/userprofile/settings", Shape.OBJECT);

    enum Shape { ARRAY, OBJECT, BINARY }

    final String path;
    final Shape shape;

    GarminRoute(String path, Shape shape) {
        this.path = path;
        this.shape = shape;
    }
}
