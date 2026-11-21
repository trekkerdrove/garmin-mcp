"""Manage a workout in the verified owner's Garmin Connect library."""

import json
import logging
import os
from pathlib import Path
import re
import sys
import tempfile

from garminconnect import Garmin, GarminConnectConnectionError


SPORTS = {"running": 1, "walking": 12, "cycling": 2}
KINDS = {"warmup": 1, "cooldown": 2, "interval": 3, "recovery": 4}


def build_payload(draft):
    sport = {"sportTypeKey": draft["sport"], "sportTypeId": SPORTS[draft["sport"]]}
    steps = []
    for order, entry in enumerate(draft["steps"], 1):
        timed = entry.get("durationSeconds") is not None
        zone = entry.get("heartRateZone")
        pace = entry.get("paceSecondsPerKm")
        kind = entry["kind"]
        step = {
            "type": "ExecutableStepDTO",
            "stepOrder": order,
            "stepType": {"stepTypeId": KINDS[kind], "stepTypeKey": kind},
            "endCondition": {"conditionTypeId": 2 if timed else 3, "conditionTypeKey": "time" if timed else "distance"},
            "endConditionValue": entry["durationSeconds"] if timed else entry["distanceMeters"],
            "targetType": {"workoutTargetTypeId": 1, "workoutTargetTypeKey": "no.target"},
        }
        if zone is not None:
            step["targetType"] = {"workoutTargetTypeId": 4, "workoutTargetTypeKey": "heart.rate.zone"}
            step["zoneNumber"] = zone
        elif pace is not None:
            step["targetType"] = {"workoutTargetTypeId": 6, "workoutTargetTypeKey": "pace.zone"}
            step["targetValueOne"] = 1000 / pace["fast"]
            step["targetValueTwo"] = 1000 / pace["slow"]
        steps.append(step)
    return {
        "workoutName": draft["name"],
        "sportType": sport,
        "workoutSegments": [{"segmentOrder": 1, "sportType": sport, "workoutSteps": steps}],
    }


def pace_bounds(step):
    if step["targetType"]["workoutTargetTypeKey"] == "pace.zone":
        # Garmin may round stored speeds. Compare in the MCP's unit of whole seconds/km.
        return tuple(round(1000 / value) if value and value > 0 else None
                     for value in (step.get("targetValueOne"), step.get("targetValueTwo")))
    return None


def workout_shape(workout):
    segments = workout["workoutSegments"]
    if len(segments) != 1:
        return None
    return (
        workout["workoutName"],
        workout["sportType"]["sportTypeKey"],
        tuple(
            (s["type"], s["stepType"]["stepTypeKey"], s["endCondition"]["conditionTypeKey"],
             s["endConditionValue"], s["targetType"]["workoutTargetTypeKey"], s.get("zoneNumber"), pace_bounds(s))
            for s in segments[0]["workoutSteps"]
        ),
    )


class LibraryLimit(Exception):
    pass


def library(garmin):
    for offset in range(0, 1000, 100):
        rows = garmin.get_workouts(start=offset, limit=100)
        if not isinstance(rows, list):
            raise ValueError("Invalid workout list")
        yield from rows
        if len(rows) < 100:
            return
    raise LibraryLimit()


def named_workout(garmin, name):
    return next((int(row["workoutId"]) for row in library(garmin) if row["workoutName"] == name), None)


def http_status(error):
    while error is not None:
        code = getattr(getattr(error, "response", None), "status_code", None)
        if code is not None:
            return code
        # 0.3.2 drops the Response and wraps its own "API Error NNN" exception.
        if isinstance(error, GarminConnectConnectionError):
            match = re.match(r"^API Error (\d{3})\b", str(error))
            if match:
                return int(match[1])
        error = error.__cause__
    return None


def failure(error, fallback):
    return {"status": {400: "workout_rejected", 401: "workout_session_unavailable",
                       403: "garmin_access_denied", 429: "garmin_rate_limited"}.get(http_status(error), fallback)}


def create(garmin, draft):
    payload = build_payload(draft)
    expected = workout_shape(payload)
    existing = named_workout(garmin, payload["workoutName"])
    if existing is not None:
        if workout_shape(garmin.get_workout_by_id(existing)) == expected:
            return {"status": "already_exists", "workoutId": existing}
        return {"status": "workout_name_conflict"}

    try:
        uploaded = garmin.upload_workout(payload)
    except Exception as error:
        return failure(error, "workout_write_unconfirmed")
    try:
        workout_id = int(uploaded["workoutId"])
        if workout_id > 0 and workout_shape(garmin.get_workout_by_id(workout_id)) == expected:
            return {"status": "created", "workoutId": workout_id}
    except Exception:
        pass
    return {"status": "workout_write_unconfirmed"}


def read_workout(garmin, workout_id):
    try:
        workout = garmin.get_workout_by_id(workout_id)
    except Exception as error:
        if http_status(error) == 404:
            return None
        raise
    if int(workout["workoutId"]) != workout_id or not isinstance(workout["workoutName"], str):
        raise ValueError("Unexpected workout")
    return workout


def edit_existing(garmin, operation, request):
    workout_id = request["workoutId"]
    current = read_workout(garmin, workout_id)
    if current is None or not any(int(row["workoutId"]) == workout_id for row in library(garmin)):
        return {"status": "workout_not_found"}
    result = {"workoutId": workout_id, "name": current["workoutName"]}
    if operation == "update":
        name = request.get("name", current["workoutName"])
        if name != current["workoutName"] and named_workout(garmin, name) is not None:
            return {"status": "workout_name_conflict"}
        payload = {**current, "workoutName": name}
        if "steps" in request:
            sport = current["sportType"]["sportTypeKey"]
            segments = current["workoutSegments"]
            if sport not in SPORTS or len(segments) != 1:
                return {"status": "workout_unsupported"}
            if sport == "cycling" and any(step.get("paceSecondsPerKm") is not None for step in request["steps"]):
                return {"status": "workout_unsupported"}
            new = build_payload({"name": name, "sport": sport, "steps": request["steps"]})
            payload["workoutSegments"] = [{**segments[0], "workoutSteps": new["workoutSegments"][0]["workoutSteps"]}]
    if request["confirm"] is not True:
        return {**result, "status": "preview"}
    try:
        if operation == "delete":
            garmin.delete_workout(workout_id)
        else:
            if payload == current:
                return {**result, "status": "unchanged"}
            garmin.client.put("connectapi", f"{garmin.garmin_workouts}/workout/{workout_id}", json=payload)
    except Exception as error:
        return failure(error, "workout_write_unconfirmed")
    try:
        saved = read_workout(garmin, workout_id)
        if operation == "delete" and saved is None:
            return {**result, "status": "deleted"}
        if operation == "update" and saved and saved["workoutName"] == name:
            if "steps" not in request or workout_shape(saved) == workout_shape(payload):
                return {"workoutId": workout_id, "name": name, "status": "updated"}
    except Exception:
        pass
    return {"status": "workout_write_unconfirmed"}


def publish(garmin, request):
    recent = garmin.get_activities(0, 20)
    if not any(row["activityId"] == request["expectedActivityId"] for row in recent):
        return {"status": "workout_account_unverified"}
    operation = request["operation"]
    if operation == "create":
        return create(garmin, request["workout"])
    if operation in {"update", "delete"}:
        return edit_existing(garmin, operation, request)
    return {"status": "workout_rejected"}


def main(token_dir):
    raw = sys.stdin.buffer.read(16_385)
    if len(raw) > 16_384:
        return {"status": "workout_rejected"}
    request = json.loads(raw)
    token_file = Path(token_dir) / "garmin_tokens.json"
    if not token_file.is_file() or token_file.stat().st_mode & 0o077:
        return {"status": "workout_session_unavailable"}
    garmin = Garmin()
    try:
        stored = token_file.read_text(encoding="utf-8")
        garmin.client.loads(stored)
    except Exception:
        return {"status": "workout_session_unavailable"}
    before = garmin.client.dumps()
    try:
        return publish(garmin, request)
    finally:
        refreshed = garmin.client.dumps()
        if refreshed != before:
            temporary = tempfile.NamedTemporaryFile(mode="w", dir=token_file.parent, delete=False, encoding="utf-8")
            try:
                with temporary:
                    temporary.write(refreshed)
                os.replace(temporary.name, token_file)
            finally:
                Path(temporary.name).unlink(missing_ok=True)


if __name__ == "__main__":
    os.umask(0o077)
    logging.disable(logging.CRITICAL)
    try:
        result = main(sys.argv[1])
    except LibraryLimit:
        result = {"status": "workout_library_limit"}
    except Exception as error:
        result = failure(error, "workout_service_unavailable")
    print(json.dumps(result))
