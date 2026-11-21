"""Manage a workout in the verified owner's Garmin Connect library."""

import json
import logging
import os
from datetime import datetime, timezone
from decimal import Decimal
from pathlib import Path
import re
import sys
import tempfile
import time
import unicodedata

from garminconnect import Garmin, GarminConnectConnectionError


SPORTS = {"running": 1, "walking": 12, "cycling": 2}
KINDS = {"warmup": 1, "cooldown": 2, "interval": 3, "recovery": 4}
# Garmin computes BMI for a manual weight, even without any scale data.
COMPOSITION_FIELDS = ("bodyFat", "bodyWater", "boneMass", "muscleMass", "physiqueRating",
                      "visceralFat", "visceralFatMass", "visceralFatRating", "metabolicAge", "basalMet", "activeMet")


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
    seen = set()
    # Garmin treats both 0 and 1 as the first item.
    for offset in range(1, 1001, 100):
        rows = garmin.get_workouts(start=offset, limit=100)
        if not isinstance(rows, list):
            raise ValueError("Invalid workout list")
        previous_count = len(seen)
        for row in rows:
            workout_id = int(row["workoutId"])
            if workout_id not in seen:
                seen.add(workout_id)
                yield row
        if len(rows) < 100:
            return
        if len(seen) == previous_count:
            break
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
    return save_copy(garmin, payload, workout_shape)


def save_copy(garmin, payload, shape, source_id=None):
    expected = shape(payload)
    existing = named_workout(garmin, payload["workoutName"])
    if existing is not None:
        if existing != source_id and shape(garmin.get_workout_by_id(existing)) == expected:
            return {"status": "already_exists", "workoutId": existing}
        return {"status": "workout_name_conflict"}

    try:
        uploaded = garmin.upload_workout(payload)
    except Exception as error:
        return failure(error, "workout_write_unconfirmed")
    try:
        workout_id = int(uploaded["workoutId"])
        if workout_id > 0 and workout_id != source_id and shape(garmin.get_workout_by_id(workout_id)) == expected:
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


def library_workout(garmin, workout_id):
    workout = read_workout(garmin, workout_id)
    if workout is not None and any(int(row["workoutId"]) == workout_id for row in library(garmin)):
        return workout
    return None


def workout_summary(workout):
    return {
        "workoutId": int(workout["workoutId"]),
        "name": workout["workoutName"],
        "sport": (workout.get("sportType") or {}).get("sportTypeKey"),
        "updatedAt": workout.get("updatedDate") or workout.get("updateDate") or workout.get("createdDate"),
    }


def search_text(name):
    return "".join(char for char in unicodedata.normalize("NFKD", name.casefold())
                   if not unicodedata.combining(char))


def search_workouts(garmin, request):
    query = search_text(request["query"])
    matches = []
    capped = False
    try:
        for workout in library(garmin):
            if query in search_text(workout["workoutName"]):
                matches.append(workout_summary(workout))
    except LibraryLimit:
        capped = True
    matches.sort(key=lambda item: (item["updatedAt"] or "", item["workoutId"]), reverse=True)
    limit = request["limit"]
    status = "truncated" if capped or len(matches) > limit else ("ok" if matches else "no_data")
    return {"status": status, "data": {"workouts": matches[:limit], "libraryScanCapped": capped}}


def selected_fields(data, names):
    return {key: data[key] for key in names if data.get(key) is not None}


def copy_step(step):
    content = selected_fields(step, (
        "type", "stepOrder", "stepType", "description", "endCondition", "endConditionValue",
        "endConditionCompare", "endConditionZone", "preferredEndConditionUnit",
        "targetType", "targetValueOne", "targetValueTwo", "targetValueUnit", "zoneNumber",
        "secondaryTargetType", "secondaryTargetValueOne", "secondaryTargetValueTwo",
        "secondaryTargetValueUnit", "secondaryZoneNumber", "strokeType", "equipmentType",
        "drillType", "category", "exerciseName", "weightValue", "weightUnit", "numberOfIterations",
        "skipLastRestStep",
    ))
    if "workoutSteps" in step:
        content["workoutSteps"] = [copy_step(child) for child in step["workoutSteps"] or []]
    return content


def workout_content(workout):
    # Use workout content fields only. Garmin assigns IDs and ownership on upload.
    content = selected_fields(workout, ("workoutName", "sportType", "subSportType", "description",
                                        "poolLength", "poolLengthUnit", "isSessionTransitionEnabled"))
    content["workoutSegments"] = [
        {**selected_fields(segment, ("segmentOrder", "sportType", "description", "poolLength", "poolLengthUnit")),
         "workoutSteps": [copy_step(step) for step in segment["workoutSteps"]]}
        for segment in workout["workoutSegments"]
    ]
    return content


def all_steps(steps):
    for step in steps:
        yield step
        yield from all_steps(step.get("workoutSteps", []))


def duplicate_workout(garmin, request, current):
    name = request["name"]
    source_id = request["workoutId"]
    if name == current["workoutName"]:
        return {"status": "workout_name_conflict"}
    content = workout_content(current)
    content["workoutName"] = name
    steps = [step for segment in content["workoutSegments"] for step in all_steps(segment["workoutSteps"])]
    if not steps:
        return {"status": "workout_unsupported"}
    for change in request.get("distanceChanges", []):
        matching = [step for step in steps if step.get("stepOrder") == change["stepOrder"]]
        if len(matching) != 1 or (matching[0].get("endCondition") or {}).get("conditionTypeKey") != "distance":
            return {"status": "invalid_input"}
        matching[0]["endConditionValue"] = change["distanceMeters"]
    if not request["confirm"]:
        return {"status": "preview", "data": {"sourceWorkoutId": source_id, "workout": content}}
    result = save_copy(garmin, content, workout_content, source_id)
    if result["status"] in {"created", "already_exists"}:
        return {"status": result["status"], "data": {"sourceWorkoutId": source_id, "workoutId": result["workoutId"],
                                                     "name": name, "reused": result["status"] == "already_exists"}}
    return result


def edit_existing(garmin, operation, request):
    workout_id = request["workoutId"]
    current = library_workout(garmin, workout_id)
    if current is None:
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


def weight_rows(garmin, day):
    rows = garmin.get_daily_weigh_ins(day)["dateWeightList"]
    if not isinstance(rows, list) or any(row["calendarDate"] != day for row in rows):
        raise ValueError("Unexpected Garmin weight day")
    return rows


def weight_timestamp(row):
    # Garmin sends both the real UTC epoch and the wall time encoded as a UTC epoch.
    utc = datetime.fromtimestamp(row["timestampGMT"] / 1000, timezone.utc)
    wall = datetime.fromtimestamp(row["date"] / 1000, timezone.utc)
    return wall.replace(tzinfo=timezone(wall - utc))


def weight_kg(row):
    return Decimal(str(row["weight"])) / 1000


def editable_weight(row):
    return row.get("sourceType") == "MANUAL" and all(row.get(key) is None for key in COMPOSITION_FIELDS)


def weight_selection(row):
    return f"{row['samplePk']}:{row['date']}:{row['timestampGMT']}:{weight_kg(row):f}"


def weight_summary(row):
    return {"measurementId": str(row["samplePk"]), "weightKg": float(weight_kg(row)),
            "timestamp": weight_timestamp(row).isoformat(), "source": row.get("sourceType"),
            "canCorrect": editable_weight(row), "selection": weight_selection(row)}


def weight_result(status, data, **details):
    return {"status": status, "data": {**data, **details}}


def verified_weights(garmin, day, find):
    for delay in (0, 0.25, 0.75):
        if delay:
            time.sleep(delay)
        try:
            rows = weight_rows(garmin, day)
            matches = find(rows)
        except Exception:
            continue
        if matches is not None:
            return matches
    return None


def set_weight(garmin, request):
    day, kg = request["date"], Decimal(str(request["weightKg"]))
    data = {"date": day, "weightKg": float(kg)}
    rows = weight_rows(garmin, day)
    selection = request.get("selection")
    original = None
    original_id = None
    if selection is not None:
        original_id, local_ms, utc_ms, _ = selection.split(":")
        timestamp = weight_timestamp({"date": int(local_ms), "timestampGMT": int(utc_ms)})
        if timestamp.date().isoformat() != day:
            return {"status": "invalid_input"}
        original = next((row for row in rows if str(row["samplePk"]) == original_id), None)
        if original is not None and weight_selection(original) != selection:
            return weight_result("weight_measurement_changed", data, measurement=weight_summary(original))
    elif len(rows) == 1 and not request["confirm"]:
        original = rows[0]
        timestamp = weight_timestamp(original)
    else:
        timestamp = datetime.fromisoformat(request["timestamp"])

    data["timestamp"] = timestamp.isoformat()

    def replacements(entries):
        return [row for row in entries if editable_weight(row) and weight_kg(row) == kg
                and weight_timestamp(row).isoformat() == data["timestamp"]]

    matching = replacements(rows)
    if selection is not None and original is None:
        if len(matching) == 1:
            return weight_result("already_exists", data, measurement=weight_summary(matching[0]))
        return weight_result("weight_measurement_not_found", data)
    if selection is None and rows and original is None:
        if len(rows) == 1 and matching:
            return weight_result("already_exists", data, measurement=weight_summary(matching[0]))
        return weight_result("selection_required", data, measurements=[weight_summary(row) for row in rows])

    if original is not None:
        data["previousMeasurement"] = weight_summary(original)
        if weight_kg(original) == kg:
            return weight_result("already_exists", data, measurement=data["previousMeasurement"])
        if not editable_weight(original) or timestamp.date().isoformat() != day:
            return weight_result("weight_not_editable", data)
    if not request["confirm"]:
        return weight_result("preview", data, action="create" if original is None else "correct")

    if not matching:
        try:
            garmin.add_weigh_in(float(kg), "kg", data["timestamp"])
        except Exception as error:
            rejection = failure(error, "weight_write_unconfirmed")
            if rejection["status"] != "weight_write_unconfirmed":
                return rejection
        rows = verified_weights(garmin, day, lambda entries: entries if replacements(entries) else None)
        if rows is None:
            return {"status": "weight_write_unconfirmed"}
        matching = replacements(rows)
    if len(matching) != 1:
        return {"status": "weight_write_unconfirmed"}

    saved = matching[0]
    saved_id = str(saved["samplePk"])
    data["measurement"] = weight_summary(saved)
    if original is None:
        return weight_result("created", data)
    current = next((row for row in rows if str(row["samplePk"]) == original_id), None)
    if current is None or original_id == saved_id:
        return weight_result("updated", data)
    if weight_selection(current) != selection or not editable_weight(current):
        return weight_result("weight_measurement_changed", data)
    try:
        garmin.delete_weigh_in(original_id, day)
    except Exception:
        pass

    def replacement_only(entries):
        ids = {str(row["samplePk"]) for row in entries}
        remaining = replacements(entries)
        return True if original_id not in ids and len(remaining) == 1 and str(remaining[0]["samplePk"]) == saved_id else None

    if verified_weights(garmin, day, replacement_only):
        return weight_result("updated", data)
    return weight_result("weight_cleanup_pending", data)


def publish(garmin, request):
    recent = garmin.get_activities(0, 20)
    if not any(row["activityId"] == request["expectedActivityId"] for row in recent):
        return {"status": "workout_account_unverified"}
    operation = request["operation"]
    if operation == "set_weight":
        return set_weight(garmin, request)
    if operation == "create":
        return create(garmin, request["workout"])
    if operation == "search":
        return search_workouts(garmin, request)
    if operation in {"get", "duplicate"}:
        current = library_workout(garmin, request["workoutId"])
        if current is None:
            return {"status": "workout_not_found"}
        if operation == "get":
            return {"status": "ok", "data": {**workout_summary(current), "workout": workout_content(current)}}
        return duplicate_workout(garmin, request, current)
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
    output = json.dumps(result, ensure_ascii=False)
    if len(output.encode("utf-8")) > 65_536:
        output = '{"status":"workout_detail_limit"}'
    print(output)
