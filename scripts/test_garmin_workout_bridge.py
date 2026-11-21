import unittest
from copy import deepcopy
from unittest.mock import Mock

from garmin_workout_bridge import GarminConnectConnectionError, build_payload, publish


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
        saved = build_payload(draft)
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


if __name__ == "__main__":
    unittest.main()
