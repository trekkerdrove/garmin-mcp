"""Publish one validated workout to a local python-garminconnect session."""

import json
import logging
import os
from pathlib import Path
import sys
import tempfile

from garminconnect import Garmin


SPORTS = {"running": 1, "walking": 12, "cycling": 2}
KINDS = {"warmup": 1, "cooldown": 2, "interval": 3, "recovery": 4}


def build_payload(draft):
    sport = {"sportTypeKey": draft["sport"], "sportTypeId": SPORTS[draft["sport"]]}
    steps = []
    for order, entry in enumerate(draft["steps"], 1):
        timed = entry.get("durationSeconds") is not None
        zone = entry.get("heartRateZone")
        kind = entry["kind"]
        step = {
            "type": "ExecutableStepDTO",
            "stepOrder": order,
            "stepType": {"stepTypeId": KINDS[kind], "stepTypeKey": kind},
            "endCondition": {"conditionTypeId": 2 if timed else 3, "conditionTypeKey": "time" if timed else "distance"},
            "endConditionValue": entry["durationSeconds"] if timed else entry["distanceMeters"],
            "targetType": {
                "workoutTargetTypeId": 1 if zone is None else 4,
                "workoutTargetTypeKey": "no.target" if zone is None else "heart.rate.zone",
            },
        }
        if zone is not None:
            step["zoneNumber"] = zone
        steps.append(step)
    return {
        "workoutName": draft["name"],
        "sportType": sport,
        "workoutSegments": [{"segmentOrder": 1, "sportType": sport, "workoutSteps": steps}],
    }


def workout_shape(workout):
    segments = workout["workoutSegments"]
    if len(segments) != 1:
        return None
    return (
        workout["workoutName"],
        workout["sportType"]["sportTypeKey"],
        tuple(
            (s["type"], s["stepType"]["stepTypeKey"], s["endCondition"]["conditionTypeKey"],
             s["endConditionValue"], s["targetType"]["workoutTargetTypeKey"], s.get("zoneNumber"))
            for s in segments[0]["workoutSteps"]
        ),
    )


def publish(garmin, request):
    recent = garmin.get_activities(0, 20)
    if not any(row["activityId"] == request["expectedActivityId"] for row in recent):
        return {"status": "workout_account_unverified"}

    payload = build_payload(request["workout"])
    expected = workout_shape(payload)
    for offset in range(0, 1000, 100):
        rows = garmin.get_workouts(start=offset, limit=100)
        if not isinstance(rows, list):
            return {"status": "workout_service_unavailable"}
        for row in rows:
            if row["workoutName"] != payload["workoutName"]:
                continue
            workout_id = int(row["workoutId"])
            if workout_shape(garmin.get_workout_by_id(workout_id)) == expected:
                return {"status": "already_exists", "workoutId": workout_id}
            return {"status": "workout_name_conflict"}
        if len(rows) < 100:
            break
    else:
        return {"status": "workout_library_limit"}

    try:
        uploaded = garmin.upload_workout(payload)
    except Exception as error:
        status = getattr(getattr(error, "response", None), "status_code", None)
        if status in {400, 401, 403, 429}:
            return {"status": {
                400: "workout_rejected", 401: "workout_session_unavailable",
                403: "garmin_access_denied", 429: "garmin_rate_limited",
            }[status]}
        return {"status": "workout_write_unconfirmed"}
    try:
        workout_id = int(uploaded["workoutId"])
        if workout_id > 0 and workout_shape(garmin.get_workout_by_id(workout_id)) == expected:
            return {"status": "created", "workoutId": workout_id}
    except Exception:
        pass
    return {"status": "workout_write_unconfirmed"}


def main(token_dir):
    request = json.loads(sys.stdin.buffer.read(8193))
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
    except Exception as error:
        status = getattr(getattr(error, "response", None), "status_code", None)
        result = {"status": "garmin_rate_limited" if status == 429 else "workout_service_unavailable"}
    print(json.dumps(result))
