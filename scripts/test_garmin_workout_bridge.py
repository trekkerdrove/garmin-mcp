import json
import unittest
from copy import deepcopy
from unittest.mock import Mock, call

from garmin_workout_bridge import (GarminConnectConnectionError, bounded_output, build_payload, build_strength_payload,
                                   publish, strength_shape)


class WorkoutBridgeTest(unittest.TestCase):
    def setUp(self):
        self.garmin = Mock(garmin_workouts="/workout-service")
        self.garmin.get_activities.return_value = [{"activityId": 7}]
        self.workout = dict(build_payload({"name": "Original", "sport": "running",
                                           "steps": [{"kind": "warmup", "durationSeconds": 60}]}),
                            workoutId=42, description="Keep this note")
        self.garmin.get_workout_by_id.return_value = self.workout
        self.garmin.get_workouts.return_value = [self.workout]

    def invoke(self, operation, **args):
        return publish(self.garmin, {"expectedActivityId": 7, "workoutId": 42, "confirm": False,
                                     **args, "operation": operation})

    def test_preview_and_wrong_account_never_write(self):
        self.assertEqual(self.invoke("delete"), {"status": "preview", "workoutId": 42, "name": "Original"})
        self.assertEqual(self.invoke("update", name="New")["status"], "preview")
        self.garmin.get_activities.return_value = []
        self.assertEqual(self.invoke("delete", confirm=True)["status"], "workout_account_unverified")
        self.garmin.delete_workout.assert_not_called()
        self.garmin.client.put.assert_not_called()

    def test_only_library_entries_can_be_changed(self):
        self.garmin.get_workouts.return_value = []
        self.assertEqual(self.invoke("delete", confirm=True)["status"], "workout_not_found")
        self.garmin.delete_workout.assert_not_called()

    def test_reuse_compares_both_stored_pace_bounds(self):
        draft = {"name": "Original", "sport": "running", "steps": [
            {"kind": "interval", "distanceMeters": 1000, "paceSecondsPerKm": {"fast": 265, "slow": 275}}]}
        saved = dict(build_payload(draft), workoutId=42)
        step = saved["workoutSegments"][0]["workoutSteps"][0]
        self.assertEqual(step["targetType"], {"workoutTargetTypeId": 6, "workoutTargetTypeKey": "pace.zone"})
        self.assertAlmostEqual(step["targetValueOne"], 1000 / 265)
        self.assertAlmostEqual(step["targetValueTwo"], 1000 / 275)
        self.garmin.get_workout_by_id.return_value = saved
        step["targetValueOne"] = round(step["targetValueOne"], 6)
        self.assertEqual(self.invoke("create", workout=draft)["status"], "already_exists")
        for field in ("targetValueOne", "targetValueTwo"):
            with self.subTest(field=field):
                changed = deepcopy(saved)
                changed["workoutSegments"][0]["workoutSteps"][0][field] = 1000 / 270
                self.garmin.get_workout_by_id.return_value = changed
                self.assertEqual(self.invoke("create", workout=draft)["status"], "workout_name_conflict")
        self.garmin.upload_workout.assert_not_called()

    def test_strength_write_compares_charge_units_exercises_and_all_rests(self):
        plan = {"name": "Strength", "sport": "strength_training", "steps": [
            {"kind": "set", "category": "SQUAT", "exerciseName": "BARBELL_BACK_SQUAT", "reps": 8, "weightKg": 62.5},
            {"kind": "rest", "durationSeconds": 120},
            {"kind": "set", "category": "PLANK", "exerciseName": "PLANK", "durationSeconds": 45, "weightKg": None},
        ]}
        payload = build_strength_payload(plan)
        saved = deepcopy(dict(payload, workoutId=123))
        steps = saved["workoutSegments"][0]["workoutSteps"]
        self.assertEqual(saved["sportType"]["sportTypeId"], 5)
        self.assertEqual(steps[0]["weightValue"], 62.5)
        self.assertEqual(steps[0]["weightUnit"]["unitKey"], "kilogram")
        self.assertEqual(steps[1]["stepType"]["stepTypeKey"], "rest")
        self.assertEqual(steps[2]["endCondition"]["conditionTypeKey"], "time")
        self.assertNotIn("weightValue", steps[2])
        self.garmin.get_workouts.return_value = []
        self.garmin.upload_workout.return_value = {"workoutId": 123}
        self.garmin.get_workout_by_id.return_value = saved
        self.assertEqual(self.invoke("create_strength", workout=plan), {"status": "created", "workoutId": 123})
        self.garmin.upload_workout.assert_called_once_with(payload)
        self.garmin.get_workouts.return_value = [saved]
        self.assertEqual(self.invoke("create_strength", workout=plan), {"status": "already_exists", "workoutId": 123})
        self.garmin.get_workouts.return_value = [saved, {**saved, "workoutId": 124}]
        self.assertEqual(self.invoke("create_strength", workout=plan)["status"], "workout_name_conflict")
        self.garmin.get_workouts.return_value = [saved]
        steps[1]["endConditionValue"] = 121
        self.assertEqual(self.invoke("create_strength", workout=plan)["status"], "workout_name_conflict")
        self.garmin.upload_workout.assert_called_once()
        steps[1]["endConditionValue"] = 120
        self.garmin.get_workouts.return_value = []
        steps[0]["weightUnit"]["unitKey"] = "pound"
        self.assertEqual(self.invoke("create_strength", workout=plan)["status"], "workout_write_unconfirmed")
        self.garmin.get_workout_by_id.return_value = dict(deepcopy(payload), workoutId=124)
        self.assertEqual(self.invoke("create_strength", workout=plan)["status"], "workout_write_unconfirmed")

    def test_strength_repeat_normalizes_only_the_effective_rest_sequence(self):
        exercise = {"kind": "set", "category": "SQUAT", "exerciseName": "BARBELL_BACK_SQUAT", "reps": 8}
        rest = {"kind": "rest", "durationSeconds": 90}
        flat = build_strength_payload({"name": "Strength", "steps": [exercise, rest, exercise]})
        grouped = deepcopy(flat)
        children = grouped["workoutSegments"][0]["workoutSteps"][:2]
        group = {"type": "RepeatGroupDTO", "stepOrder": 1, "numberOfIterations": 2, "skipLastRestStep": True,
                 "stepType": {"stepTypeKey": "repeat", "stepTypeId": 6},
                 "endCondition": {"conditionTypeKey": "iterations", "conditionTypeId": 7}, "workoutSteps": children}
        grouped["workoutSegments"][0]["workoutSteps"] = [group]
        children[1]["stepType"] = {"stepTypeKey": "recovery", "stepTypeId": 4}
        self.assertEqual(strength_shape(grouped), strength_shape(flat))
        group["skipLastRestStep"] = False
        self.assertNotEqual(strength_shape(grouped), strength_shape(flat))
        group["numberOfIterations"] = 26
        self.assertIsNone(strength_shape(grouped))

    def test_update_rejects_dropped_pace_and_cycling_target(self):
        steps = [{"kind": "interval", "durationSeconds": 60, "paceSecondsPerKm": {"fast": 270, "slow": 270}}]
        saved = dict(build_payload({"name": "Original", "sport": "running", "steps": steps}), workoutId=42)
        del saved["workoutSegments"][0]["workoutSteps"][0]["targetValueTwo"]
        self.garmin.get_workout_by_id.side_effect = [self.workout, saved]
        self.assertEqual(self.invoke("update", steps=steps, confirm=True)["status"], "workout_write_unconfirmed")
        self.garmin.get_workout_by_id.side_effect = None
        self.workout["sportType"]["sportTypeKey"] = "cycling"
        self.garmin.client.put.reset_mock()
        self.assertEqual(self.invoke("update", steps=steps, confirm=True)["status"], "workout_unsupported")
        self.garmin.client.put.assert_not_called()

    def test_rename_preserves_other_fields_and_requires_readback(self):
        renamed = {**self.workout, "workoutName": "New"}
        self.garmin.get_workout_by_id.side_effect = [self.workout, renamed]
        self.assertEqual(self.invoke("update", name="New", confirm=True),
                         {"status": "updated", "workoutId": 42, "name": "New"})
        self.garmin.client.put.assert_called_once_with("connectapi", "/workout-service/workout/42", json=renamed)

    def test_step_replacement_requires_matching_steps_after_put(self):
        self.assertEqual(self.invoke("update", steps=[{"kind": "interval", "distanceMeters": 600}],
                                     confirm=True)["status"], "workout_write_unconfirmed")
        sent = self.garmin.client.put.call_args.kwargs["json"]
        self.assertEqual(sent["description"], "Keep this note")
        self.assertEqual(sent["workoutSegments"][0]["workoutSteps"][0]["endConditionValue"], 600)

    def test_delete_distinguishes_absence_from_service_failure(self):
        for code, expected in [(404, "deleted"), (503, "workout_write_unconfirmed")]:
            with self.subTest(status=code):
                self.garmin.get_workout_by_id.side_effect = [self.workout, GarminConnectConnectionError(f"API Error {code}")]
                self.assertEqual(self.invoke("delete", confirm=True)["status"], expected)

    def test_name_collision_never_writes(self):
        self.garmin.get_workouts.return_value.append({"workoutId": 43, "workoutName": "Taken"})
        self.assertEqual(self.invoke("update", name="Taken", confirm=True)["status"], "workout_name_conflict")
        self.garmin.client.put.assert_not_called()


    def test_search_across_pages_ignores_accents_and_sorts_by_last_update(self):
        first = {**self.workout, "workoutName": "Fractionné ancien", "updateDate": "2026-11-18"}
        last = {**self.workout, "workoutId": 43, "workoutName": "FRACTIONNÉ récent", "updateDate": "2026-11-20"}
        filler = [{"workoutId": i, "workoutName": "Easy"} for i in range(1000, 1099)]
        self.garmin.get_workouts.side_effect = [[first, *filler], [last]]
        result = self.invoke("search", query="fractionne", limit=1)
        self.assertEqual(result["status"], "truncated")
        self.assertEqual([w["workoutId"] for w in result["data"]["workouts"]], [43])
        self.garmin.get_workouts.assert_called_with(start=101, limit=100)

    def test_detail_and_copy_keep_nested_targets_without_copying_ids(self):
        steps = self.workout["workoutSegments"][0]["workoutSteps"]
        interval = build_payload({"name": "Intervals", "sport": "running", "steps": [
            {"kind": "interval", "distanceMeters": 400, "paceSecondsPerKm": {"fast": 265, "slow": 275}}]
        })["workoutSegments"][0]["workoutSteps"][0]
        steps.append({"type": "RepeatGroupDTO", "stepOrder": 2, "stepId": 88, "numberOfIterations": 6,
                      "workoutSteps": [{**interval, "stepOrder": 3, "stepId": 99}]})
        self.workout["ownerId"] = 9
        detail = self.invoke("get")["data"]["workout"]
        self.assertNotIn("ownerId", detail)
        args = {"name": "Variation", "distanceChanges": [{"stepOrder": 3, "distanceMeters": 600}]}
        preview = self.invoke("duplicate", **args)
        proposed = preview["data"]["workout"]
        proposed_group = proposed["workoutSegments"][0]["workoutSteps"][1]
        self.assertEqual(proposed_group["numberOfIterations"], 6)
        self.assertNotIn("stepId", proposed_group["workoutSteps"][0])
        self.assertEqual(proposed_group["workoutSteps"][0]["endConditionValue"], 600)
        self.assertEqual(proposed_group["workoutSteps"][0]["targetValueOne"], interval["targetValueOne"])
        self.assertEqual(steps[1]["workoutSteps"][0]["endConditionValue"], 400)
        self.garmin.upload_workout.assert_not_called()
        self.garmin.upload_workout.return_value = {"workoutId": 123}
        self.garmin.get_workout_by_id.side_effect = [self.workout, {**proposed, "workoutId": 123}]
        actual = self.invoke("duplicate", confirm=True, **args)
        self.assertEqual(actual["data"]["workoutId"], 123)
        self.garmin.upload_workout.assert_called_once_with(proposed)
        self.garmin.client.put.assert_not_called()

    def test_copy_rejects_reuse_of_source_name_and_invalid_distance_changes(self):
        self.assertEqual(self.invoke("duplicate", name="Original", confirm=True)["status"], "workout_name_conflict")
        result = self.invoke("duplicate", name="Copy", confirm=True,
                             distanceChanges=[{"stepOrder": 1, "distanceMeters": 600}])
        self.assertEqual(result["status"], "invalid_input")
        self.garmin.upload_workout.assert_not_called()


class CoachBridgeTest(unittest.TestCase):
    UUID = "123e4567-e89b-42d3-a456-426614174000"

    def setUp(self):
        self.garmin = Mock()
        self.garmin.get_activities.return_value = [{"activityId": 7}]

    def invoke(self, start="2026-11-22", end="2026-11-28"):
        return publish(self.garmin, {"operation": "coach_workouts", "expectedActivityId": 7,
                                     "startDate": start, "endDate": end})

    def details(self, uuid=UUID, start="2026-11-23", end="2026-11-29"):
        return publish(self.garmin, {"operation": "coach_workout_details", "expectedActivityId": 7,
                                     "startDate": start, "endDate": end, "workoutUuid": uuid})

    def threshold(self, **overrides):
        return {"scheduleDate": "2026-11-23", "workoutName": "Seuil", "workoutType": "running",
                "workoutPhrase": "LACTATE_THRESHOLD", "estimatedDurationInSecs": 2580,
                "workoutUuid": self.UUID, **overrides}

    @staticmethod
    def plan(rows, **overrides):
        return {"trainingPlanId": 11, "planName": "Garmin Run Coach", "trainingPlanClassification": "FBT_ADAPTIVE",
                "trainingPlanDetailsDTO": {"trainingType": "RUNNING", "planCompleted": False,
                                           "workoutsPerWeek": 4, "remainingAtpWorkoutsForWeek": 0,
                                           "athleteRace": {"raceName": "Paris", "raceDay": "2027-04-04"}},
                "workoutScheduleSummaries": rows, **overrides}

    @staticmethod
    def result(*plans):
        return {"data": {"trainingPlanScalar": {"trainingPlanWorkoutScheduleDTOS": list(plans)}}}

    def test_reads_each_week_once_and_bounds_chronological_entries_with_plan_ids(self):
        shortened_workout = {"scheduleDate": "2026-11-22", "workoutName": "Intervals",
                             "workoutType": {"sportTypeKey": "running", "other": "ignored"},
                             "trainingPlanId": 99, "tpPlanName": "Reported plan name",
                             "scheduledWorkoutId": 33, "workoutId": 44,
                             "workoutUuid": "coach-workout-uuid", "fbtAdaptivePlanId": 22, "tpType": "COACH",
                             "workoutPhrase": "ANAEROBIC_SPEED", "estimatedDurationInSecs": 1800,
                             "estimatedDistanceInMeters": 4000, "workoutSteps": ["sensitive raw data"]}
        rows = [{"scheduleDate": "2026-11-28", "workoutName": "Long Run"},
                {"scheduleDate": "2026-11-23", "workoutName": "Base"}, shortened_workout,
                {"scheduleDate": "2026-11-21"}, {"scheduleDate": "2026-11-29"}]
        bike = self.plan([{"scheduleDate": "2026-11-23", "workoutName": "Ride"}],
                         trainingPlanId=12, planName="Garmin Cycling Coach",
                         trainingPlanDetailsDTO={"trainingType": "CYCLING"})
        completed = self.plan([{"scheduleDate": "2026-11-24", "workoutName": "Old plan"}], trainingPlanId=13,
                              trainingPlanDetailsDTO={"trainingType": "RUNNING", "planCompleted": True})
        response = self.result(self.plan(rows), bike, completed)
        self.garmin.query_garmin_graphql.side_effect = [response, response]
        result = self.invoke()
        self.assertEqual(result["status"], "ok")
        data = result["data"]
        self.assertEqual((data["startDate"], data["endDate"]), ("2026-11-22", "2026-11-28"))
        self.assertEqual([(w["weekStartDate"], w["status"]) for w in data["weeks"]],
                         [("2026-11-16", "published"), ("2026-11-23", "published")])
        self.assertEqual(data["weeks"][0]["plans"], [
            {"trainingPlanId": 11, "name": "Garmin Run Coach", "classification": "FBT_ADAPTIVE",
             "trainingType": "RUNNING", "planCompleted": False, "workoutsPerWeek": 4,
             "remainingWorkoutsThisWeek": 0, "raceName": "Paris", "raceDay": "2027-04-04"}])
        self.assertEqual([(row["date"], row["trainingPlanId"]) for row in data["entries"]],
                         [("2026-11-22", 99), ("2026-11-23", 11), ("2026-11-28", 11)])
        self.assertEqual(data["entries"][0],
                         {"date": "2026-11-22", "name": "Intervals", "sport": "running", "workoutType": "ANAEROBIC_SPEED",
                          "trainingPlanId": 99, "planName": "Reported plan name", "scheduledWorkoutId": 33, "workoutId": 44,
                          "workoutUuid": "coach-workout-uuid", "fbtAdaptivePlanId": 22, "tpType": "COACH",
                          "estimatedDurationSeconds": 1800, "estimatedDistanceMeters": 4000})
        self.assertEqual(self.garmin.query_garmin_graphql.call_args_list, [
            call({"query": 'query{trainingPlanScalar(calendarDate:"2026-11-22", lang:"en-US", firstDayOfWeek:"monday")}'}),
            call({"query": 'query{trainingPlanScalar(calendarDate:"2026-11-23", lang:"en-US", firstDayOfWeek:"monday")}'})])
        self.garmin.get_training_plans.assert_not_called()
        self.garmin.client.put.assert_not_called()

    def test_keeps_first_week_on_empty_schedule_or_failed_read(self):
        first = self.result(self.plan([{"scheduleDate": "2026-11-22", "workoutName": "Base"}]))
        for second, status, coverage, error in [
                (self.result(), "ok", "empty", None),
                (self.result(self.plan(None)), "ok", "empty", None),
                (TimeoutError("Private request details"), "partial", "error", "coach_read_failed"),
                (GarminConnectConnectionError("API Error 403"), "partial", "error", "garmin_access_denied"),
                ({**first, "errors": [{"message": "Private error details"}]}, "partial", "error", "coach_read_failed")]:
            with self.subTest(coverage=coverage, error=error):
                self.garmin.query_garmin_graphql.side_effect = [first, second]
                result = self.invoke()
                self.assertEqual(result["status"], status)
                self.assertEqual(result["data"]["weeks"][1]["status"], coverage)
                self.assertEqual(result["data"]["weeks"][1].get("error"), error)
                self.assertEqual([row["date"] for row in result["data"]["entries"]], ["2026-11-22"])
                self.garmin.get_training_plans.assert_not_called()

    def test_keeps_explicit_rest_and_race_days_and_omits_completed_workouts(self):
        rows = [{"scheduleDate": "2026-11-24", "isRestDay": True},
                {"scheduleDate": "2026-11-25", "race": True, "isRestDay": False},
                {"scheduleDate": "2026-11-26", "workoutType": "RUNNING", "associatedActivityId": 999},
                {"scheduleDate": "2026-11-27", "workoutType": "CYCLING"}]
        self.garmin.query_garmin_graphql.return_value = self.result(
            self.plan(rows, trainingPlanDetailsDTO={"athleteRace": ["unexpected optional value"]}))
        result = self.invoke("2026-11-23", "2026-11-29")
        self.assertEqual(result["status"], "ok")
        self.assertEqual(result["data"]["entries"], [
            {"date": "2026-11-24", "trainingPlanId": 11, "planName": "Garmin Run Coach", "isRestDay": True},
            {"date": "2026-11-25", "trainingPlanId": 11, "planName": "Garmin Run Coach",
             "isRaceDay": True, "isRestDay": False}])
        self.garmin.query_garmin_graphql.assert_called_once()

    def test_inherits_running_plan_across_weeks_and_rejects_mixed_endurance_inference(self):
        unknown = {"planName": "Garmin Coach", "trainingPlanDetailsDTO": {}}
        first = self.plan([{"scheduleDate": "2026-11-22", "workoutType": "RUNNING", "associatedActivityId": 999}])
        second = self.plan([{"scheduleDate": "2026-11-23", "isRestDay": True},
                            {"scheduleDate": "2026-11-24", "race": True},
                            {"scheduleDate": "2026-11-25", "workoutType": "STRENGTH_TRAINING"}], **unknown)
        mixed_first = self.plan([{"scheduleDate": "2026-11-22", "workoutType": "RUNNING"}],
                                trainingPlanId=12, **unknown)
        mixed_second = self.plan([{"scheduleDate": "2026-11-23", "workoutType": "SWIMMING"},
                                  {"scheduleDate": "2026-11-25", "workoutType": "CYCLING"}],
                                 trainingPlanId=12, **unknown)
        self.garmin.query_garmin_graphql.side_effect = [self.result(first, mixed_first), self.result(second, mixed_second)]
        result = self.invoke()
        self.assertEqual(result["status"], "ok")
        self.assertEqual([week["status"] for week in result["data"]["weeks"]], ["published", "published"])
        self.assertEqual([[plan["trainingPlanId"] for plan in week["plans"]] for week in result["data"]["weeks"]],
                         [[11], [11]])
        self.assertEqual([row["date"] for row in result["data"]["entries"]], ["2026-11-23", "2026-11-24"])

    def test_keeps_valid_objects_and_contains_an_oversized_week(self):
        first = self.result(None, self.plan([None, {"scheduleDate": "invalid"},
                                              {"scheduleDate": "2026-11-22", "workoutName": "Base"}]),
                            self.plan({"not": "an array"}, trainingPlanId=12),
                            self.plan([{"scheduleDate": "2026-11-22", "workoutName": "Intervals"}], trainingPlanId=13))
        second = self.result(self.plan([{"scheduleDate": "2026-11-23", "workoutName": "é" * 20_000}]))
        self.garmin.query_garmin_graphql.side_effect = [first, second]
        result = self.invoke()
        self.assertEqual(result["status"], "partial")
        self.assertEqual([(week["status"], week["error"]) for week in result["data"]["weeks"]],
                         [("published", "coach_malformed_data"), ("error", "workout_detail_limit")])
        self.assertEqual([row["name"] for row in result["data"]["entries"]], ["Base", "Intervals"])

    def test_distinguishes_confirmed_no_plan_valid_empty_and_failed_read(self):
        self.garmin.query_garmin_graphql.return_value = self.result()
        for plan_list, expected in [({"trainingPlanList": []}, "no_active_plan"),
                                    ({"trainingPlanList": [{"trainingPlanId": 11}]}, "no_data"),
                                    ({}, "no_data")]:
            with self.subTest(status=expected, plan_list=plan_list):
                self.garmin.get_training_plans.return_value = plan_list
                result = self.invoke()
                self.assertEqual(result["status"], expected)
                self.assertEqual(result["data"]["entries"], [])
        self.garmin.get_training_plans.side_effect = TimeoutError("Private request details")
        self.assertEqual(self.invoke()["status"], "no_data")
        self.garmin.get_training_plans.reset_mock()
        self.garmin.query_garmin_graphql.return_value = {"data": None}
        result = self.invoke()
        self.assertEqual(result["status"], "coach_read_failed")
        self.assertEqual([week["status"] for week in result["data"]["weeks"]], ["error", "error"])
        self.garmin.get_training_plans.assert_not_called()

    def test_wrong_account_never_queries_coach(self):
        self.garmin.get_activities.return_value = [{"activityId": 8}]
        self.assertEqual(self.invoke(), {"status": "workout_account_unverified"})
        self.garmin.query_garmin_graphql.assert_not_called()
        self.garmin.get_training_plans.assert_not_called()

    def test_rejects_unbounded_or_noncanonical_dates_before_query(self):
        self.assertEqual(self.invoke(end="2026-12-01"), {"status": "invalid_input"})
        self.assertEqual(self.invoke(start="20261122"), {"status": "invalid_input"})
        self.garmin.query_garmin_graphql.assert_not_called()

    def test_reads_the_selected_threshold_workout_without_library_or_write_calls(self):
        self.garmin.query_garmin_graphql.return_value = self.result(self.plan([self.threshold(workoutUuid=self.UUID.upper())]))
        steps = build_payload({"name": "Seuil", "sport": "running", "steps": [
            {"kind": "warmup", "durationSeconds": 600}, {"kind": "interval", "durationSeconds": 300},
            {"kind": "recovery", "durationSeconds": 120}, {"kind": "cooldown", "durationSeconds": 300},
        ]})["workoutSegments"][0]["workoutSteps"]
        warmup, effort, recovery, cooldown = steps
        effort.update(targetType={"workoutTargetTypeKey": "pace.zone", "ownerId": "excluded"},
                      targetValueOne=3.5, targetValueTwo=4, stepId=123)
        cooldown["stepOrder"] = 5
        repeat = {"type": "RepeatGroupDTO", "stepType": {"stepTypeKey": "repeat"}, "stepOrder": 2,
                  "numberOfIterations": 4, "skipLastRestStep": False, "workoutSteps": [recovery, effort]}
        self.garmin.connectapi.return_value = {
            "workoutUuid": self.UUID.upper(), "ownerId": 1, "author": {"name": "excluded"},
            "workoutSegments": [{"segmentOrder": 1, "sportType": {"sportTypeKey": "running", "ownerId": 1},
                                 "workoutSteps": [cooldown, repeat, warmup]}],
        }

        schedule = self.invoke("2026-11-23", "2026-11-29")["data"]
        selected, = (entry for entry in schedule["entries"] if entry["date"] == schedule["startDate"])
        result = self.details(selected["workoutUuid"])

        self.assertEqual(result["status"], "ok")
        data = result["data"]
        self.assertEqual({key: value for key, value in data.items() if key != "workout"}, {
            "workoutUuid": self.UUID, "name": "Seuil", "sport": "running", "workoutType": "LACTATE_THRESHOLD",
            "estimatedDurationSeconds": 2580,
        })
        segment, = data["workout"]["workoutSegments"]
        self.assertEqual(segment["sportType"], {"sportTypeKey": "running"})
        first, group, last = segment["workoutSteps"]
        self.assertEqual([step["stepType"]["stepTypeKey"] for step in (first, group, last)],
                         ["warmup", "repeat", "cooldown"])
        self.assertEqual([first["endConditionValue"], last["endConditionValue"]], [600, 300])
        self.assertEqual(group["numberOfIterations"], 4)
        self.assertIs(group["skipLastRestStep"], False)
        projected_effort, projected_recovery = group["workoutSteps"]
        self.assertEqual([projected_effort["endConditionValue"], projected_recovery["endConditionValue"]], [300, 120])
        self.assertEqual(projected_effort["paceSecondsPerKm"], {"fast": 250, "slow": 286})
        self.assertEqual(projected_recovery["targetType"]["workoutTargetTypeKey"], "no.target")
        self.assertNotIn("ownerId", json.dumps(data))
        self.assertNotIn("author", json.dumps(data))
        self.assertNotIn("stepId", json.dumps(data))
        self.assertEqual([method for method, _, _ in self.garmin.mock_calls],
                         ["get_activities", "query_garmin_graphql", "get_activities", "query_garmin_graphql", "connectapi"])
        self.garmin.connectapi.assert_called_once_with(f"workout-service/fbt-adaptive/{self.UUID}")

    def test_preserves_nested_steps_conditions_and_ambiguous_units(self):
        self.garmin.query_garmin_graphql.return_value = self.result(self.plan([self.threshold()]))
        distance = {"stepOrder": 8, "stepType": {"stepTypeKey": "future.step"},
                    "endCondition": {"conditionTypeKey": "distance"}, "endConditionValue": 400,
                    "preferredEndConditionUnit": {"unitKey": "meter"},
                    "targetType": {"workoutTargetTypeKey": "heart.rate.zone"}, "zoneNumber": 3,
                    "secondaryTargetType": {"workoutTargetTypeKey": "pace.zone"},
                    "secondaryTargetValueOne": 16, "secondaryTargetValueTwo": 16,
                    "secondaryTargetValueUnit": {"unitKey": "m/s"}}
        manual = {"stepOrder": 8, "endCondition": {"conditionTypeKey": "lap.button"},
                  "description": "Press Lap when ready", "targetType": {"workoutTargetTypeKey": "pace.zone"},
                  "targetValueOne": 3.5, "targetValueTwo": 4, "targetValueUnit": {"unitKey": "unknown"}}
        inner = {"stepOrder": 5, "numberOfIterations": 2, "workoutSteps": [manual, distance]}
        outer = {"stepOrder": 1, "numberOfIterations": 3, "workoutSteps": [inner]}
        pace = {"stepOrder": 1.5, "targetType": {"workoutTargetTypeKey": "pace.zone"},
                "targetValueOne": 4, "targetValueTwo": 3.5, "targetValueUnit": {"unitKey": "meters_per_second"}}
        missing_bound = {"stepOrder": 0, "targetType": {"workoutTargetTypeKey": "pace.zone"}, "targetValueOne": 4}
        nonpositive = {**missing_bound, "targetValueTwo": 0}
        self.garmin.connectapi.return_value = {"workoutSegments": [
            {"segmentOrder": 2, "workoutSteps": [outer]}, {"segmentOrder": 1, "workoutSteps": [pace, missing_bound, nonpositive]},
        ]}

        first, second = self.details()["data"]["workout"]["workoutSegments"]
        self.assertEqual([first["segmentOrder"], second["segmentOrder"]], [1, 2])
        self.assertEqual(first["workoutSteps"][0]["paceSecondsPerKm"], {"fast": 250, "slow": 286})
        for step in first["workoutSteps"][1:]:
            self.assertNotIn("paceSecondsPerKm", step)
        manual_detail, distance_detail = second["workoutSteps"][0]["workoutSteps"][0]["workoutSteps"]
        self.assertEqual(manual_detail, manual)
        self.assertEqual(distance_detail, {**distance, "secondaryPaceSecondsPerKm": {"fast": 63, "slow": 63}})

    def test_rejects_invalid_uuid_wrong_account_and_explicit_rest_before_detail(self):
        for invalid in (None, "", 42, "42", "1-1-1-1-1", " " + self.UUID, self.UUID + "\n"):
            with self.subTest(uuid=invalid):
                self.assertEqual(self.details(invalid), {"status": "invalid_input"})
        self.garmin.get_activities.assert_not_called()
        self.garmin.get_activities.return_value = [{"activityId": 8}]
        self.assertEqual(self.details(), {"status": "workout_account_unverified"})
        self.garmin.query_garmin_graphql.assert_not_called()
        self.garmin.get_activities.return_value = [{"activityId": 7}]
        self.garmin.query_garmin_graphql.return_value = self.result(self.plan([self.threshold(isRestDay=True)]))
        self.assertEqual(self.details(), {"status": "coach_workout_not_found"})
        self.garmin.connectapi.assert_not_called()

    def test_requires_membership_and_reports_unreadable_or_unpublished_schedule(self):
        wrong_uuid = "123e4567-e89b-42d3-a456-426614174001"
        schedule = self.result(self.plan([self.threshold(workoutUuid=wrong_uuid)]))
        self.garmin.query_garmin_graphql.side_effect = [
            TimeoutError("excluded"), schedule, GarminConnectConnectionError("API Error 403"), schedule,
        ]
        self.assertEqual(self.details(start="2026-11-22", end="2026-11-28"), {"status": "coach_read_failed"})
        self.assertEqual(self.details(start="2026-11-22", end="2026-11-28"), {"status": "garmin_access_denied"})
        self.garmin.query_garmin_graphql.side_effect = None
        self.garmin.query_garmin_graphql.return_value = schedule
        self.assertEqual(self.details(), {"status": "coach_read_failed"})
        complete_rows = [self.threshold(scheduleDate=f"2026-11-{day}", workoutUuid=wrong_uuid) for day in range(23, 30)]
        self.garmin.query_garmin_graphql.return_value = self.result(self.plan(complete_rows))
        self.assertEqual(self.details(), {"status": "coach_workout_not_found"})
        complete_rows[0].pop("workoutUuid")
        self.assertEqual(self.details(), {"status": "coach_read_failed"})
        self.garmin.query_garmin_graphql.return_value = self.result()
        self.garmin.get_training_plans.return_value = {"trainingPlanList": []}
        self.assertEqual(self.details(), {"status": "coach_workout_not_found"})
        self.garmin.get_training_plans.side_effect = GarminConnectConnectionError("API Error 429")
        self.assertEqual(self.details(), {"status": "garmin_rate_limited"})
        self.garmin.connectapi.assert_not_called()

    def test_accepts_matching_entry_when_another_week_fails_and_keeps_summary_without_steps(self):
        self.garmin.query_garmin_graphql.side_effect = [
            TimeoutError("excluded"), self.result(self.plan([self.threshold()])),
        ]
        self.garmin.connectapi.return_value = {"workoutUuid": self.UUID, "workoutSegments": [{"workoutSteps": []}]}
        result = self.details(start="2026-11-22", end="2026-11-28")
        self.assertEqual(result, {"status": "no_data", "data": {
            "workoutUuid": self.UUID, "name": "Seuil", "sport": "running", "workoutType": "LACTATE_THRESHOLD",
            "estimatedDurationSeconds": 2580, "workout": {"workoutSegments": []},
        }})

    def test_requires_exclusion_evidence_from_every_plan_and_week(self):
        wrong_uuid = "123e4567-e89b-42d3-a456-426614174001"
        complete_rows = [self.threshold(scheduleDate=f"2026-11-{day}", workoutUuid=wrong_uuid) for day in range(23, 30)]
        incomplete_plan = self.plan([], trainingPlanId=12)
        self.garmin.query_garmin_graphql.return_value = self.result(self.plan(complete_rows), incomplete_plan)
        self.assertEqual(self.details(), {"status": "coach_read_failed"})

        rest_schedule = self.result(self.plan([self.threshold(isRestDay=True)]))
        for error, expected in [(TimeoutError("excluded"), "coach_read_failed"),
                                (GarminConnectConnectionError("API Error 403"), "garmin_access_denied")]:
            with self.subTest(status=expected):
                self.garmin.query_garmin_graphql.side_effect = [error, rest_schedule]
                self.assertEqual(self.details(start="2026-11-22", end="2026-11-28"), {"status": expected})
        self.garmin.connectapi.assert_not_called()

    def test_requires_identifiable_details_and_usable_steps(self):
        self.garmin.query_garmin_graphql.return_value = self.result(self.plan([self.threshold()]))
        for invalid_step in (
            {"targetValueOne": True}, {"stepType": False}, {"stepType": {"stepTypeKey": True}},
            {"targetType": True}, {"targetType": {"workoutTargetTypeId": True}},
            {"secondaryTargetType": True}, {"description": " \n\t "}, {"description": 123},
        ):
            with self.subTest(step=invalid_step):
                self.garmin.connectapi.return_value = {"workoutSegments": [{"workoutSteps": [invalid_step]}]}
                self.assertEqual(self.details(), {"status": "coach_read_failed"})
        step = {"stepType": {"stepTypeKey": "interval"}}
        for invalid_count in (None, True, 0, -1, 2.5, "4"):
            with self.subTest(repeat_count=invalid_count):
                repeat = {"type": "RepeatGroupDTO", "numberOfIterations": invalid_count, "workoutSteps": [step]}
                self.garmin.connectapi.return_value = {"workoutSegments": [{"workoutSteps": [repeat]}]}
                self.assertEqual(self.details(), {"status": "coach_read_failed"})

        segments = [{"workoutSteps": [step]}]
        for invalid_workout in (
            {"workoutSegments": []}, {"workoutSegments": [{"workoutSteps": []}]},
            {"sportType": {"sportTypeKey": "running"}, "workoutSegments": []},
            {"workoutUuid": None, "workoutSegments": segments},
            {"workoutUuid": False, "workoutSegments": segments},
            {"workoutUuid": "", "workoutSegments": segments},
            {"sportType": {"sportTypeKey": "cycling"}, "workoutSegments": segments},
            {"workoutSegments": [{"sportType": "swimming", "workoutSteps": [step]}]},
        ):
            with self.subTest(workout=invalid_workout):
                self.garmin.connectapi.return_value = invalid_workout
                self.assertEqual(self.details(), {"status": "coach_read_failed"})

        self.garmin.connectapi.return_value = {"workoutName": "Seuil", "workoutSegments": []}
        self.assertEqual(self.details()["status"], "no_data")
        self.garmin.connectapi.return_value = {"workoutSegments": segments}
        self.assertEqual(self.details()["status"], "ok")

    def test_maps_detail_errors_and_never_exposes_unusable_payloads(self):
        self.garmin.query_garmin_graphql.return_value = self.result(self.plan([self.threshold()]))
        for error, expected in [(GarminConnectConnectionError("API Error 404"), "coach_workout_not_found"),
                                (GarminConnectConnectionError("API Error 401"), "workout_session_unavailable"),
                                (GarminConnectConnectionError("API Error 403"), "garmin_access_denied"),
                                (GarminConnectConnectionError("API Error 429"), "garmin_rate_limited"),
                                (TimeoutError("excluded"), "coach_read_failed")]:
            with self.subTest(status=expected):
                self.garmin.connectapi.side_effect = error
                self.assertEqual(self.details(), {"status": expected})
        self.garmin.connectapi.side_effect = None
        for malformed in ([], {}, {"error": "excluded"}, {"workoutUuid": "123e4567-e89b-42d3-a456-426614174001"},
                          {"workoutUuid": self.UUID, "workoutSegments": {}},
                          {"workoutSegments": [{}]},
                          {"workoutSegments": [{"workoutSteps": [1]}]},
                          {"workoutSegments": [{"workoutSteps": [{}]}]},
                          {"workoutSegments": [{"workoutSteps": [{"type": "RepeatGroupDTO", "numberOfIterations": 4}]}]},
                          {"workoutSegments": [{"workoutSteps": [{"numberOfIterations": 4, "workoutSteps": []}]}]}):
            with self.subTest(payload=malformed):
                self.garmin.connectapi.return_value = malformed
                self.assertEqual(self.details(), {"status": "coach_read_failed"})
        self.garmin.connectapi.return_value = {"workoutSegments": [{"workoutSteps": [{"description": "é" * 40_000}]}]}
        self.assertEqual(json.loads(bounded_output(self.details(), False)), {"status": "workout_detail_limit"})


if __name__ == "__main__":
    unittest.main()
