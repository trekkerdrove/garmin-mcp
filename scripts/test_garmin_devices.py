from contextlib import nullcontext
from copy import deepcopy
import fcntl
import io
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

from requests.exceptions import ConnectTimeout, ReadTimeout

import garmin_courses_bridge as courses
import garmin_workout_bridge as bridge
from test_garmin_courses_bridge import course, response


DEVICE_ID = "9007199254740993"
MESSAGE_PATH = "https://connect.example/device-service/devicemessage/messages"


class DeviceBridgeTest(unittest.TestCase):
    def setUp(self):
        self.session = Mock()
        self.session.request.return_value = nullcontext(SimpleNamespace(status_code=204))
        client = Mock(_connectapi="https://connect.example")
        client._fresh_api_session.side_effect = lambda: nullcontext(self.session)
        client._token_expires_soon.return_value = False
        client.get_api_headers.return_value = {"Authorization": "fixture"}
        self.garmin = Mock(client=client, garmin_connect_activities="/activities", garmin_workouts="/workout-service")
        self.garmin.get_activities.return_value = [{"activityId": 7}]
        self.garmin.get_devices.return_value = [
            {"deviceId": int(DEVICE_ID), "displayName": "Watch", "serialNumber": "private"},
            {"deviceId": "123", "displayName": "", "productDisplayName": "Bike computer", "userDeviceId": 999},
        ]
        self.workouts = {}
        self.garmin.get_workouts.side_effect = lambda **kwargs: list(self.workouts.values())
        self.garmin.get_workout_by_id.side_effect = self.read_workout
        self.garmin.upload_workout.side_effect = self.save_workout
        self.garmin.client.put.side_effect = lambda _, path, json: self.workouts.update({int(path.split("/")[-1]): json})

    def read_workout(self, workout_id):
        if workout_id not in self.workouts:
            raise bridge.GarminConnectConnectionError("API Error 404 - raw detail")
        return self.workouts[workout_id]

    def save_workout(self, payload):
        workout_id = 42 + len(self.workouts)
        self.workouts[workout_id] = dict(deepcopy(payload), workoutId=workout_id)
        return {"workoutId": workout_id}

    def send(self, content_id, *, course=False, **args):
        id_key = "courseId" if course else "workoutId"
        request = {"operation": "send_to_device", "expectedActivityId": 7, id_key: content_id,
                   "deviceId": DEVICE_ID, **args}
        return bridge.device_operation(self.garmin, request, vars(courses) if course else None)

    def assert_send(self, content_id, name, *, course=False):
        count = self.session.request.call_count
        id_key = "courseId" if course else "workoutId"
        expected = {id_key: content_id, "name": name, "deviceId": DEVICE_ID, "deviceName": "Watch"}
        self.assertEqual(self.send(content_id, course=course), {"status": "preview", "data": expected})
        self.assertEqual(self.session.request.call_count, count)
        self.assertEqual(self.send(content_id, course=course, confirm=True), {"status": "queued", "data": expected})
        self.assertEqual(self.session.request.call_count, count + 1)
        self.session.request.assert_called_with(
            "POST", MESSAGE_PATH, headers={"Authorization": "fixture"}, timeout=(4, 10), stream=True,
            allow_redirects=False, json=[{
                "deviceId": int(DEVICE_ID), "messageName": name, "groupName": None, "fileType": "FIT",
                "metaDataId": content_id, "messageType": "courses" if course else "workouts",
                "priority": 0 if course else 1,
                "messageUrl": (f"course-service/course/fit/{content_id}/{DEVICE_ID}?elevation=true" if course
                               else f"workout-service/workout/FIT/{content_id}"),
            }])

    def test_device_list_preserves_large_id_and_projects_only_names(self):
        request = {"operation": "list_devices", "expectedActivityId": 7}
        self.assertEqual(bridge.device_operation(self.garmin, request), {"status": "ok", "data": {"devices": [
            {"deviceId": DEVICE_ID, "name": "Watch"}, {"deviceId": "123", "name": "Bike computer"}]}})
        self.garmin.get_devices.return_value = []
        self.assertEqual(bridge.device_operation(self.garmin, request), {"status": "no_data", "data": {"devices": []}})
        self.session.request.assert_not_called()

    def test_bad_rows_do_not_hide_valid_devices_and_duplicate_ids_are_excluded(self):
        long_name = "x" * 513
        self.garmin.get_devices.return_value.extend([
            None, {"displayName": "Missing ID"}, {"deviceId": "invalid", "displayName": "Bad ID"},
            {"deviceId": "00123", "displayName": "Duplicate"},
            {"deviceId": 222, "displayName": long_name}, {"deviceId": "222", "displayName": "Also duplicate"},
            {"deviceId": 333, "displayName": long_name, "productDisplayName": "Fallback"},
            {"deviceId": 444, "displayName": long_name},
        ])
        self.assertEqual(bridge.device_operation(self.garmin, {"operation": "list_devices", "expectedActivityId": 7}),
                         {"status": "ok", "data": {"devices": [
                             {"deviceId": DEVICE_ID, "name": "Watch"}, {"deviceId": "333", "name": "Fallback"}]}})
        self.workouts[42] = {"workoutId": 42, "workoutName": "Run"}
        for device_id in ("123", "00123", "222", "444"):
            self.assertEqual(self.send(42, confirm=True, deviceId=device_id), {"status": "device_not_found"})
        self.session.request.assert_not_called()
        self.assert_send(42, "Run")

    def test_content_names_must_fit_the_reply_before_sending(self):
        for name in ("🏃" * 513, "Invalid\ud800"):
            with self.subTest(name_length=len(name)):
                self.workouts[42] = {"workoutId": 42, "workoutName": name}
                self.assertEqual(self.send(42, confirm=True), {"status": "workout_service_unavailable"})
                current = {**course(42), "courseName": name}
                with patch.object(courses, "request", side_effect=[[{"activityId": 7}],
                                                                     {"coursesForUser": [current]}, current]):
                    self.assertEqual(self.send(42, course=True, confirm=True), {"status": "garmin_unavailable"})
        self.session.request.assert_not_called()
        name = "🏃" * 512
        self.workouts[42] = {"workoutId": 42, "workoutName": name}
        self.assert_send(42, name)

    def test_saved_workout_ids_from_all_four_producers_work_in_the_common_send(self):
        draft = {"name": "Run", "sport": "running", "steps": [{"kind": "warmup", "durationSeconds": 60}]}
        strength = {"name": "Strength", "steps": [
            {"kind": "set", "category": "SQUAT", "exerciseName": "BARBELL_BACK_SQUAT", "reps": 8}]}
        running_id = 42
        for operation, args, expected_id, name in (
                ("create", {"workout": draft}, running_id, "Run"),
                ("create_strength", {"workout": strength}, 43, "Strength"),
                ("duplicate", {"workoutId": running_id, "name": "Copy"}, 44, "Copy"),
                ("update", {"workoutId": running_id, "name": "Updated"}, running_id, "Updated")):
            with self.subTest(operation=operation):
                attempts = self.session.request.call_count
                saved = bridge.publish(self.garmin, {"operation": operation, "expectedActivityId": 7,
                                                     "confirm": True, **args})
                self.assertEqual(saved["status"], "updated" if operation == "update" else "created")
                saved_id = saved.get("data", saved)["workoutId"]
                self.assertEqual(saved_id, expected_id)
                self.assertEqual(self.session.request.call_count, attempts)
                self.assert_send(saved_id, name)

    def test_course_create_copy_and_update_can_send_the_saved_id(self):
        owned = {}

        def course_request(_, method, path, *, payload=None, params=None):
            if path == "/activities":
                return [{"activityId": 7}]
            if path == courses.OWNER:
                return {"coursesForUser": list(owned.values())}
            if method == "POST":
                self.assertEqual(path, courses.ROOT)
                course_id = 42 + len(owned)
                owned[course_id] = dict(payload, courseId=course_id)
                return {"courseId": course_id}
            course_id = int(path.split("/")[-1])
            if method == "PUT":
                owned[course_id] = payload
                return {}
            return owned.get(course_id)

        with patch.object(courses, "request", side_effect=course_request):
            content = courses.snapshot(course())["content"]
            original_id = 42
            for operation, args, expected_id, name in (
                    ("course_create", {"content": content}, original_id, "Crête"),
                    ("course_create", {"content": {**content, "name": "Crête copie"}}, 43, "Crête copie"),
                    ("course_update", {"courseId": original_id,
                                       "content": {"name": "Crête est"}, "fields": ["name"]}, original_id, "Crête est")):
                if operation == "course_update":
                    args["version"] = courses.revision(owned[original_id])
                attempts = self.session.request.call_count
                saved = courses.publish(self.garmin, {"operation": operation, "expectedActivityId": 7, **args})
                self.assertEqual(saved, {"status": "accepted", "data": {"courseId": expected_id}})
                self.assertEqual(self.session.request.call_count, attempts)
                self.assert_send(saved["data"]["courseId"], name, course=True)

    def test_confirm_rechecks_account_content_and_device(self):
        self.workouts[42] = {"workoutId": 42, "workoutName": "Run"}
        self.assertEqual(self.send(42)["status"], "preview")
        devices = self.garmin.get_devices.return_value
        self.garmin.get_devices.return_value = []
        self.assertEqual(self.send(42, confirm=True), {"status": "device_not_found"})
        self.garmin.get_devices.return_value = devices
        self.garmin.get_workouts.side_effect = None
        self.garmin.get_workouts.return_value = []
        self.garmin.get_workout_by_id.reset_mock()
        self.assertEqual(self.send(42, confirm=True), {"status": "workout_not_found"})
        self.garmin.get_workout_by_id.assert_called_once_with(42)
        self.workouts.clear()
        self.assertEqual(self.send(42, confirm=True), {"status": "workout_not_found"})
        self.garmin.get_activities.return_value = [{"activityId": 9}]
        self.assertEqual(self.send(42, confirm=True), {"status": "workout_account_unverified"})
        self.session.request.assert_not_called()

        with patch.object(courses, "request", side_effect=[
                [{"activityId": 7}], {"coursesForUser": [course()]}, course(42)]) as request:
            self.assertEqual(self.send(42, course=True, confirm=True), {"status": "course_not_found"})
            self.assertEqual([attempt.args[2] for attempt in request.call_args_list], ["/activities", courses.OWNER])
            self.session.request.assert_not_called()

    def test_explicit_rejection_and_uncertain_reply_do_not_replay(self):
        self.workouts[42] = {"workoutId": 42, "workoutName": "Run"}
        for code, expected in ((200, "queued"), (400, "device_send_rejected"), (401, "device_send_rejected"),
                               (429, "garmin_rate_limited"), (307, "device_send_unconfirmed"),
                               (408, "device_send_unconfirmed"), (503, "device_send_unconfirmed")):
            with self.subTest(code=code):
                self.session.request.reset_mock()
                self.session.request.return_value = nullcontext(SimpleNamespace(status_code=code, text="raw secret"))
                reply = self.send(42, confirm=True)
                self.assertEqual(reply["status"], expected)
                if code != 200:
                    self.assertEqual(reply, {"status": expected})
                self.session.request.assert_called_once()
        for error, expected in ((ConnectTimeout, "workout_service_unavailable"), (ReadTimeout, "device_send_unconfirmed"),
                                (TimeoutError, "device_send_unconfirmed")):
            with self.subTest(error=error.__name__):
                self.session.request.reset_mock()
                self.session.request.side_effect = error("raw secret")
                self.assertEqual(self.send(42, confirm=True), {"status": expected})
                self.session.request.assert_called_once()
        self.garmin.client._refresh_session.assert_not_called()

    def test_preflight_failure_and_invalid_arguments_never_queue(self):
        self.assertEqual(self.send(42, confirm="true"), {"status": "invalid_input"})
        self.assertEqual(self.send(42, confirm=True, courseId=42), {"status": "invalid_input"})
        self.assertEqual(self.send(42, confirm=True, deviceId="9223372036854775808"), {"status": "invalid_input"})
        self.garmin.get_activities.assert_not_called()
        self.workouts[42] = {"workoutId": 42, "workoutName": "Run"}
        self.garmin.get_devices.side_effect = bridge.GarminConnectConnectionError("API Error 429 - raw secret")
        self.assertEqual(self.send(42, confirm=True), {"status": "garmin_rate_limited"})
        self.garmin.get_devices.side_effect = None
        self.garmin.client.get_api_headers.side_effect = ValueError("raw secret")
        self.assertEqual(self.send(42, confirm=True), {"status": "workout_service_unavailable"})
        self.session.request.assert_not_called()

    def test_device_confirmation_runs_inside_the_shared_session_lock(self):
        self.workouts[42] = {"workoutId": 42, "workoutName": "Run"}
        with (tempfile.TemporaryDirectory() as directory,
              patch.object(bridge, "Garmin", return_value=self.garmin),
              patch.object(bridge.tempfile, "gettempdir", return_value=directory),
              patch.object(bridge, "save_session", side_effect=PermissionError) as save):
            token_file = Path(directory) / "garmin_tokens.json"
            token_file.write_text("{}")
            token_file.chmod(0o600)
            with bridge.session_lock(token_file) as other_worker:
                course_replies = {"/activities": [{"activityId": 7}], courses.OWNER: {"coursesForUser": [course(42)]},
                                  f"{courses.ROOT}/42": course(42)}

                def verify_locked(method, url, **kwargs):
                    with self.assertRaises(BlockingIOError):
                        fcntl.flock(other_worker, fcntl.LOCK_EX | fcntl.LOCK_NB)
                    if method == "POST":
                        self.assertEqual(url, MESSAGE_PATH)
                        return nullcontext(response(204, {}))
                    return nullcontext(response(200, course_replies[url.removeprefix("https://connect.example")]))

                self.session.request.side_effect = verify_locked
                for course_mode, id_key, name, message_type in ((False, "workoutId", "Run", "workouts"),
                                                                (True, "courseId", "Crête", "courses")):
                    with self.subTest(mode=message_type):
                        self.session.request.reset_mock()
                        self.garmin.client.dumps.side_effect = ["{}", "{}" if course_mode else '{"refreshed": true}']
                        request = {"operation": "send_to_device", "expectedActivityId": 7, id_key: 42,
                                   "deviceId": DEVICE_ID, "confirm": True}
                        expected = {id_key: 42, "name": name, "deviceId": DEVICE_ID, "deviceName": "Watch"}
                        with patch.object(bridge.sys, "stdin", SimpleNamespace(buffer=io.BytesIO(json.dumps(request).encode()))):
                            self.assertEqual(bridge.main(directory, courses=course_mode), {"status": "queued", "data": expected})
                        methods = [attempt.args[0] for attempt in self.session.request.call_args_list]
                        self.assertEqual(methods, ["GET"] * 3 + ["POST"] if course_mode else ["POST"])
                        self.assertEqual(self.session.request.call_args.kwargs["json"][0]["messageType"], message_type)
                save.assert_called_once_with(token_file, '{"refreshed": true}')
                self.assertEqual(token_file.read_text(), "{}")

                self.garmin.client.dumps.side_effect = ["{}", "{}"]
                self.garmin.get_devices.return_value = [
                    {"deviceId": index, "displayName": "🏃" * 512} for index in range(1, 41)]
                request = {"operation": "list_devices", "expectedActivityId": 7}
                self.session.request.reset_mock()
                with patch.object(bridge.sys, "stdin", SimpleNamespace(buffer=io.BytesIO(json.dumps(request).encode()))):
                    self.assertEqual(bridge.main(directory, courses=True), {"status": "course_response_too_large"})
                self.session.request.assert_called_once()
                self.assertEqual(self.session.request.call_args.args[0], "GET")
