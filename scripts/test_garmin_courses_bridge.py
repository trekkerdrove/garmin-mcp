from contextlib import nullcontext
import fcntl
import io
import json
from pathlib import Path
import runpy
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, call, patch

import garmin_courses_bridge as bridge
import garmin_workout_bridge as shared


def course(course_id=17):
    return {"courseId": course_id, "courseName": "Crête", "activityTypePk": 143, "rulePK": 2,
            "distanceMeter": 1000, "elevationGainMeter": 0, "description": "Conservée",
            "geoPoints": [{"latitude": 47.0, "longitude": 2.0, "elevation": 0, "timestamp": None},
                          {"latitude": 47.01, "longitude": 2.01, "elevation": None, "timestamp": None}],
            "coursePoints": [{"name": "Eau", "lat": 47.0, "lon": 2.0,
                              "coursePointType": "WATER", "distance": 0, "note": "Station saisonnière"}],
            "includeLaps": True, "hasTurnDetectionDisabled": True,
            "hasPowerGuide": True}


def transport(*replies):
    session = Mock()
    session.request.side_effect = [nullcontext(reply) for reply in replies]
    client = Mock(_connectapi="https://connect.example")
    client._fresh_api_session.side_effect = lambda: nullcontext(session)
    client._token_expires_soon.return_value = False
    client.get_api_headers.return_value = {"Authorization": "fixture", "Accept": "application/json"}
    return Mock(client=client, garmin_connect_activities="/activities"), session


def response(status, data, headers=None):
    body = data if isinstance(data, bytes) else json.dumps(data).encode()
    return SimpleNamespace(status_code=status, headers=headers or {}, iter_content=lambda **kwargs: iter([body]))


class CourseBridgeTest(unittest.TestCase):
    def setUp(self):
        self.garmin = Mock(garmin_connect_activities="/activities")
        self.request = self.enterContext(patch.object(bridge, "request"))
        self.wait = self.enterContext(patch.object(bridge.time, "sleep"))

    def run_command(self, command, **args):
        return bridge.publish(self.garmin, {"operation": f"course_{command}", "expectedActivityId": 42, **args})

    def test_owner_verified_before_course_read_and_public_id_hidden(self):
        self.request.side_effect = [[{"activityId": 57}]]
        self.assertEqual(self.run_command("get", courseId=123)["status"], "course_account_unverified")
        self.request.assert_called_once_with(self.garmin, "GET", "/activities", params={"start": 0, "limit": 20})
        self.request.reset_mock()
        self.request.side_effect = [[{"activityId": 42}], {"coursesForUser": [course()]}]
        self.assertEqual(self.run_command("get", courseId=123)["status"], "absent")
        self.assertEqual([entry.args[2] for entry in self.request.call_args_list], ["/activities", bridge.OWNER])

    def test_list_declares_incomplete_and_incomplete_lookup_cannot_prove_absence(self):
        envelope = {"coursesForUser": [course()], "totalCount": 2}
        self.request.side_effect = [[{"activityId": 42}], envelope, [{"activityId": 42}], envelope]
        reply = self.run_command("list")
        self.assertFalse(reply["data"]["complete"])
        self.assertEqual(reply["data"]["items"][0]["ascentMeters"], 0)
        self.assertEqual(self.run_command("get", courseId=123)["status"], "course_busy")

    def test_update_retries_only_explicit_processing_refusal_and_preserves_raw_document(self):
        current = course()
        owned = {"coursesForUser": [current]}
        self.request.side_effect = [[{"activityId": 42}], owned, current, bridge.CourseError("course_not_ready", 2),
                                    owned, current, {}]
        edited = self.run_command("update", courseId=17, version=bridge.revision(current),
                                  content={"name": "Crête est"}, fields=["name"])
        self.assertEqual(edited, bridge.result("accepted", {"courseId": 17}))
        payload = {**current, "courseName": "Crête est"}
        writes = [entry for entry in self.request.call_args_list if entry.args[1] == "PUT"]
        self.assertEqual(writes, [call(self.garmin, "PUT", f"{bridge.ROOT}/17", payload=payload)] * 2)
        self.wait.assert_called_once_with(1)

    def test_quota_and_lost_response_are_distinct_and_neither_replays(self):
        current = course()
        owned = {"coursesForUser": [current]}
        self.request.side_effect = [[{"activityId": 42}], owned, current, bridge.CourseError("garmin_rate_limited", 55)]
        reply = self.run_command("delete", courseId=17, version=bridge.revision(current))
        self.assertEqual(reply, bridge.result("garmin_rate_limited", {"retryAfterSeconds": 55}))
        self.assertEqual(self.request.call_count, 4)
        self.wait.assert_not_called()
        self.request.reset_mock()
        self.request.side_effect = [[{"activityId": 42}], owned, current, TimeoutError()]
        reply = self.run_command("delete", courseId=17, version=bridge.revision(current))
        self.assertEqual(reply["status"], "course_write_unconfirmed")
        self.assertEqual(self.request.call_count, 4)

    def test_precondition_is_rechecked_after_processing_refusal(self):
        current = course()
        owned = {"coursesForUser": [current]}
        self.request.side_effect = [[{"activityId": 42}], owned, current, bridge.CourseError("course_not_ready", 2),
                                    owned, {**current, "courseName": "Edited elsewhere"}]
        reply = self.run_command("delete", courseId=17, version=bridge.revision(current))
        self.assertEqual(reply["status"], "course_changed")
        self.assertEqual([entry.args[1] for entry in self.request.call_args_list].count("DELETE"), 1)

    def test_create_projects_private_payload_and_reads_unknown_elevation(self):
        current = course()
        current["coursePoints"][0]["timestamp"] = 0
        projected = bridge.snapshot(current)
        self.assertEqual(projected["summary"]["sport"], "gravel_cycling")
        self.assertEqual(bridge.summary({**current, "activityTypePk": 4})["sport"], "other")
        self.assertEqual(projected["content"]["segments"][0][0]["elevationMeters"], 0)
        self.assertIsNone(projected["content"]["segments"][0][1]["elevationMeters"])
        self.assertIsNone(projected["content"]["coursePoints"][0]["timestamp"])
        self.assertEqual(projected["related"], ["Power Guide"])
        self.request.side_effect = [[{"activityId": 42}], {"courseId": 18}]
        reply = self.run_command("create", content=projected["content"])
        self.assertEqual(reply["data"]["courseId"], 18)
        payload = self.request.call_args.kwargs["payload"]
        self.assertEqual(payload["rulePK"], 2)
        self.assertEqual(payload["activityTypePk"], 143)
        self.assertEqual(payload["coursePoints"][0]["coursePointType"], "WATER")
        self.assertEqual(payload["geoPoints"][0]["elevation"], 0)
        self.assertIsNone(payload["geoPoints"][1]["elevation"])
        self.assertIsNone(payload["geoPoints"][0]["distance"])

    def test_timed_geometry_sends_cumulative_distances_on_create_and_replace(self):
        current = course()
        content = bridge.snapshot(current)["content"]
        points = content["segments"][0]
        points[0]["timestamp"] = "2026-04-02T18:00:00Z"
        points[1]["timestamp"] = "2026-04-02T18:10:00Z"
        created = bridge.create_payload(content)["geoPoints"]
        updated = bridge.update_payload(current, content, {"source"})["geoPoints"]
        self.assertEqual(created, updated)
        self.assertEqual(created[0]["distance"], 0)
        self.assertGreater(created[1]["distance"], 0)
        self.assertEqual(created[1]["timestamp"] - created[0]["timestamp"], 600_000)
        points[1]["timestamp"] = points[0]["timestamp"]
        with self.assertRaises(bridge.CourseError) as failure:
            bridge.create_payload(content)
        self.assertEqual(failure.exception.status, "invalid_input")

    def test_preservation_fingerprints_and_both_geometry_metric_aliases(self):
        current = course()
        original = bridge.snapshot(current)
        lost_settings = bridge.snapshot({**current, "includeLaps": False})
        lost_notes = bridge.snapshot({**current, "coursePoints": [{**current["coursePoints"][0], "note": None}]})
        self.assertNotEqual(original["settingsVersion"], lost_settings["settingsVersion"])
        self.assertNotEqual(original["navigationVersion"], lost_notes["navigationVersion"])
        aliases = {"distanceMeter", "distanceInMeters", "elevationGainMeter", "elevationGainInMeters",
                   "elevationLossMeter", "elevationLossInMeters"}
        updated = bridge.update_payload({**current, **dict.fromkeys(aliases, 5)}, original["content"], {"source"})
        self.assertFalse(aliases.intersection(updated))
        self.assertEqual(updated["coursePoints"], current["coursePoints"])


class TransportTest(unittest.TestCase):
    def test_courses_and_workouts_respect_the_same_held_session_lock(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "garmin_tokens.json"
            alias = Path(directory) / "alias"
            alias.symlink_to(path)
            with (patch.object(shared.tempfile, "gettempdir", return_value=directory),
                  shared.session_lock(path) as first, shared.session_lock(alias) as second):
                fcntl.flock(first, fcntl.LOCK_EX | fcntl.LOCK_NB)
                for courses, status in ((True, "course_busy"), (False, "workout_service_unavailable")):
                    with patch.object(shared.time, "monotonic", side_effect=[0, 9]):
                        reply = shared.locked_publish(second, path, {}, courses)
                    self.assertEqual(reply["status"], status)

    def test_shared_entrypoint_dispatches_courses_and_keeps_accepted_result_if_save_fails(self):
        garmin, session = transport(response(200, [{"activityId": 42}]), response(200, {"courseId": 27}))
        garmin.client.dumps.side_effect = ["old", "new"]
        request = {"operation": "course_create", "expectedActivityId": 42, "content": bridge.snapshot(course())["content"]}
        with (tempfile.TemporaryDirectory() as directory, patch.object(shared, "Garmin", return_value=garmin),
              patch.object(shared, "save_session", side_effect=PermissionError) as save):
            root = Path(directory)
            token = root / "garmin_tokens.json"
            token.write_text("old")
            token.chmod(0o600)
            with (patch.object(shared.sys, "stdin", SimpleNamespace(buffer=io.BytesIO(json.dumps(request).encode()))),
                  patch.object(shared.tempfile, "gettempdir", return_value=directory)):
                reply = shared.main(directory, courses=True)
            self.assertEqual(reply, bridge.result("accepted", {"courseId": 27}))
            save.assert_called_once_with(token, "new")
            self.assertEqual(token.read_text(), "old")
        self.assertEqual([attempt.args[0] for attempt in session.request.call_args_list], ["GET", "POST"])

    def test_workout_reads_a_read_only_session_directory(self):
        garmin = Mock()
        garmin.get_activities.return_value = [{"activityId": 42}]
        garmin.get_workouts.return_value = []
        garmin.client.dumps.return_value = "same tokens"
        request = {"operation": "search", "expectedActivityId": 42, "query": "trail", "limit": 1}
        with tempfile.TemporaryDirectory() as directory, patch.object(shared, "Garmin", return_value=garmin):
            root = Path(directory) / "read-only"
            root.mkdir()
            token = root / "garmin_tokens.json"
            token.write_text("same tokens")
            token.chmod(0o600)
            root.chmod(0o500)
            try:
                with (patch.object(shared.sys, "stdin", SimpleNamespace(buffer=io.BytesIO(json.dumps(request).encode()))),
                      patch.object(shared.tempfile, "gettempdir", return_value=directory)):
                    self.assertEqual(shared.main(root)["status"], "no_data")
                self.assertEqual(list(root.iterdir()), [token])
            finally:
                root.chmod(0o700)

    def test_isolated_entrypoint_allows_base64_output_larger_than_the_general_bridge_budget(self):
        body = b"x" * bridge.MAX_EXPORT_BYTES
        garmin, _ = transport(response(200, [{"activityId": 42}]),
                              response(200, {"coursesForUser": [course()]}), response(200, body))
        garmin.client.dumps.return_value = "same tokens"
        request = {"operation": "course_export", "expectedActivityId": 42, "courseId": 17, "format": "gpx"}
        with tempfile.TemporaryDirectory() as directory:
            token = Path(directory) / "garmin_tokens.json"
            token.write_text("same tokens")
            token.chmod(0o600)
            stdin = SimpleNamespace(buffer=io.BytesIO(json.dumps(request).encode()))
            with (patch("garminconnect.Garmin", return_value=garmin),
                  patch.object(shared.sys, "argv", [shared.__file__, directory, "courses"]),
                  patch.object(shared.sys, "stdin", stdin), patch.object(shared.os, "umask"),
                  patch.object(shared.logging, "disable"), patch("builtins.print") as output,
                  patch.object(shared.tempfile, "gettempdir", return_value=directory)):
                runpy.run_path(shared.__file__, run_name="__main__")
            wire = output.call_args.args[0]
            self.assertGreater(len(wire), bridge.MAX_BODY)
            reply = json.loads(wire)
            self.assertEqual(reply["status"], "ok")
            self.assertEqual(len(reply["data"]["contentBase64"]), 4 * ((len(body) + 2) // 3))

    def test_raw_429_distinguishes_processing_refusal_from_global_quota(self):
        message = b'{"message":"The course 17 is not yet ready to delete or update"}'
        garmin, session = transport(response(429, message), response(429, message, {"Retry-After": "47"}),
                                    response(429, {"message": "Too many requests"}, {"Retry-After": "32"}))
        for method, status, delay in (("PUT", "course_not_ready", 2),
                                      ("GET", "garmin_rate_limited", 47),
                                      ("DELETE", "garmin_rate_limited", 32)):
            with self.assertRaises(bridge.CourseError) as failure:
                bridge.request(garmin, method, f"{bridge.ROOT}/17")
            self.assertEqual((failure.exception.status, failure.exception.retry_after), (status, delay))
        self.assertEqual(session.request.call_count, 3)

    def test_native_download_requests_binary_without_changing_json_accept(self):
        garmin, session = transport(response(200, b"FIT data"), response(200, {"courseId": 17}))
        url = f"{bridge.ROOT}/fit/17/0"
        self.assertEqual(bridge.request(garmin, "GET", url, binary=True), b"FIT data")
        self.assertEqual(bridge.request(garmin, "GET", f"{bridge.ROOT}/17"), {"courseId": 17})
        requests = session.request.call_args_list
        self.assertEqual(requests[0].kwargs["headers"], {"Authorization": "fixture", "Accept": "*/*"})
        self.assertEqual(requests[1].kwargs["headers"], {"Authorization": "fixture", "Accept": "application/json"})

    def test_401_on_a_mutation_does_not_refresh_and_send_again(self):
        response = Mock(status_code=401)
        reply = Mock()
        reply.__enter__ = Mock(return_value=response)
        reply.__exit__ = Mock(return_value=None)
        session = Mock()
        session.request.return_value = reply
        opened = Mock()
        opened.__enter__ = Mock(return_value=session)
        opened.__exit__ = Mock(return_value=None)
        client = Mock(_connectapi="https://connect.example")
        client._fresh_api_session.return_value = opened
        client._token_expires_soon.return_value = False
        client.get_api_headers.return_value = {"Authorization": "fixture"}
        with self.assertRaises(bridge.CourseError) as failure:
            bridge.request(Mock(client=client), "POST", bridge.ROOT, payload={"courseName": "Crête"})
        self.assertEqual(failure.exception.status, "garmin_access_denied")
        session.request.assert_called_once()
        client._refresh_session.assert_not_called()
        self.assertFalse(session.request.call_args.kwargs["allow_redirects"])

    def test_bounded_response_ignores_missing_length_header(self):
        response = Mock()
        response.iter_content.return_value = iter([b"abc", b"def"])
        with self.assertRaises(bridge.CourseError) as failure:
            bridge.response_bytes(response, 4)
        self.assertEqual(failure.exception.status, "course_response_too_large")
