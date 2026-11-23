"""Use the verified owner's Garmin Connect session."""

import fcntl
import hashlib
import json
import logging
import os
from datetime import date, datetime, timedelta, timezone
from decimal import Decimal
from fractions import Fraction
from math import isfinite
from pathlib import Path
import re
import runpy
import stat
import sys
import tempfile
import time
import unicodedata

from garminconnect import Garmin, GarminConnectConnectionError
from requests.exceptions import ConnectTimeout


SPORTS = {"running": 1, "walking": 12, "cycling": 2}
KINDS = {"warmup": 1, "cooldown": 2, "interval": 3, "recovery": 4}
MAX_RESPONSE_BYTES = 65_536
COACH_UUID = re.compile(r"[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")
DEVICE_OPERATIONS = {"list_devices", "send_to_device"}
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


STRENGTH = {"sportTypeKey": "strength_training", "sportTypeId": 5}
KILOGRAM = {"unitId": 8, "unitKey": "kilogram", "factor": 1000.0}


def build_strength_payload(plan):
    steps = []
    for order, entry in enumerate(plan["steps"], 1):
        rest = entry["kind"] == "rest"
        reps = entry.get("reps")
        step = {
            "type": "ExecutableStepDTO",
            "stepOrder": order,
            "stepType": {"stepTypeId": 5 if rest else 3, "stepTypeKey": "rest" if rest else "interval"},
            "endCondition": {"conditionTypeId": 2 if reps is None else 10,
                             "conditionTypeKey": "time" if reps is None else "reps"},
            "endConditionValue": entry["durationSeconds"] if reps is None else reps,
            "targetType": {"workoutTargetTypeId": 1, "workoutTargetTypeKey": "no.target"},
        }
        if not rest:
            step.update(category=entry["category"], exerciseName=entry["exerciseName"])
            if entry.get("weightKg") is not None:
                step.update(weightValue=entry["weightKg"], weightUnit=KILOGRAM)
        steps.append(step)
    return {
        "workoutName": plan["name"],
        "sportType": STRENGTH,
        "workoutSegments": [{"segmentOrder": 1, "sportType": STRENGTH, "workoutSteps": steps}],
    }


def strength_shape(workout):
    try:
        segment, = workout["workoutSegments"]
        if any(sport.get(key) != value for sport in (workout["sportType"], segment["sportType"])
               for key, value in STRENGTH.items()):
            return None
        return workout["workoutName"], tuple(strength_steps(segment["workoutSteps"]))
    except (AttributeError, KeyError, TypeError, ValueError):
        return None


def strength_steps(steps, depth=0):
    if not 1 <= len(steps) <= 50 or depth >= 50:
        raise ValueError("Invalid strength step count")
    effective = []
    previous_order = 0
    for step in steps:
        if step["stepOrder"] <= previous_order:
            raise ValueError("Invalid strength step order")
        previous_order = step["stepOrder"]
        kind = step["stepType"]["stepTypeKey"]
        kind_id = step["stepType"]["stepTypeId"]
        condition = step["endCondition"]["conditionTypeKey"]
        condition_id = step["endCondition"]["conditionTypeId"]
        value = step.get("endConditionValue")
        if step["type"] == "RepeatGroupDTO":
            count = step["numberOfIterations"]
            if ((kind, kind_id, condition, condition_id) != ("repeat", 6, "iterations", 7)
                    or type(count) is not int or not 1 <= count <= 50 or step.get("smartRepeat")
                    or (value is not None and value != count)):
                raise ValueError("Unsupported strength repeat")
            children = strength_steps(step["workoutSteps"], depth + 1)
            repeated = children * count
            if step.get("skipLastRestStep") and children[-1][0] == "rest":
                repeated.pop()
            effective.extend(repeated)
        else:
            if (step["type"] != "ExecutableStepDTO"
                    or step["targetType"]["workoutTargetTypeKey"] != "no.target"
                    or step["targetType"]["workoutTargetTypeId"] != 1
                    or (step.get("secondaryTargetType") or {}).get("workoutTargetTypeKey") not in {None, "no.target"}):
                raise ValueError("Unsupported strength target")
            weight = step.get("weightValue")
            unit = step.get("weightUnit")
            if weight is not None and (not unit or any(unit.get(k) != v for k, v in KILOGRAM.items())):
                raise ValueError("Unsupported strength weight unit")
            if (kind, kind_id, condition, condition_id) == ("recovery", 4, "time", 2):
                kind, kind_id = "rest", 5
            effective.append((kind, kind_id, condition, condition_id, value, step.get("category"),
                              step.get("exerciseName"), weight))
        if len(effective) > 50:
            raise ValueError("Invalid strength step count")
    if not effective:
        raise ValueError("Empty strength workout")
    return effective


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


def save_copy(garmin, payload, shape, source_id=None, *, unique_name=False):
    expected = shape(payload)
    if expected is None:
        return {"status": "workout_rejected"}
    matches = (int(row["workoutId"]) for row in library(garmin) if row["workoutName"] == payload["workoutName"])
    existing = next(matches, None)
    if existing is not None:
        if unique_name and next(matches, None) is not None:
            return {"status": "workout_name_conflict"}
        stored = read_workout(garmin, existing) if existing != source_id else None
        if stored is not None and shape(stored) == expected:
            return {"status": "already_exists", "workoutId": existing}
        return {"status": "workout_name_conflict"}

    try:
        uploaded = garmin.upload_workout(payload)
    except Exception as error:
        return failure(error, "workout_write_unconfirmed")
    try:
        workout_id = int(uploaded["workoutId"])
        if workout_id > 0 and workout_id != source_id:
            stored = read_workout(garmin, workout_id)
            if stored is not None and shape(stored) == expected:
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


def coach_fields(data, fields):
    return {name: data[key] for key, name in fields.items()
            if type(data.get(key)) in (str, int, float, bool)}


def coach_sport_key(value):
    return value.get("sportTypeKey") if isinstance(value, dict) else value


def coach_sport(value):
    value = coach_sport_key(value)
    if isinstance(value, str):
        words = set(re.findall(r"[a-z]+", value.lower()))
        if words & {"bike", "biking", "cycle", "cycling", "swim", "swimming",
                    "walk", "walking", "triathlon", "multisport"} or {"multi", "sport"} <= words:
            return "other"
        if words & {"run", "running", "marathon"}:
            return "running"
        if words & {"strength", "cardio"}:
            return "supplemental"
    return None


def coach_plan_sport(plan, details):
    for data in (plan, details):
        for key in ("sportType", "sport"):
            sport = coach_sport(data.get(key))
            if sport is not None:
                return sport
    for value in (details.get("trainingType"), plan.get("planName")):
        sport = coach_sport(value)
        if sport is not None:
            return sport
    return None


def coach_plan_summary(plan, details):
    race = details.get("athleteRace")
    return {
        **coach_fields(plan, {"trainingPlanId": "trainingPlanId", "planName": "name",
                              "trainingPlanClassification": "classification"}),
        **coach_fields(details, {"trainingType": "trainingType", "workoutsPerWeek": "workoutsPerWeek",
                                 "remainingAtpWorkoutsForWeek": "remainingWorkoutsThisWeek",
                                 "registrationDate": "registrationDate", "planCompleted": "planCompleted"}),
        **coach_fields(race if isinstance(race, dict) else {}, {"raceName": "raceName", "raceDay": "raceDay"}),
    }


def coach_entry(row, day, plan):
    sport = coach_sport_key(row.get("workoutType"))
    return {
        "date": day.isoformat(),
        **coach_fields(plan, {"trainingPlanId": "trainingPlanId", "planName": "planName"}),
        **({"sport": sport} if isinstance(sport, str) else {}),
        **coach_fields(row, {"scheduledWorkoutId": "scheduledWorkoutId", "workoutId": "workoutId",
                             "workoutUuid": "workoutUuid", "fbtAdaptivePlanId": "fbtAdaptivePlanId",
                             "tpType": "tpType", "workoutName": "name",
                             "workoutPhrase": "workoutType", "isRestDay": "isRestDay", "race": "isRaceDay",
                             "estimatedDurationInSecs": "estimatedDurationSeconds",
                             "estimatedDistanceInMeters": "estimatedDistanceMeters",
                             "trainingPlanId": "trainingPlanId", "tpPlanName": "planName"}),
    }


def coach_week(garmin, first, last):
    result = garmin.query_garmin_graphql({
        "query": f'query{{trainingPlanScalar(calendarDate:"{first}", lang:"en-US", firstDayOfWeek:"monday")}}'
    })
    if result.get("errors"):
        raise ValueError("Garmin Coach query failed")
    plans = result["data"]["trainingPlanScalar"]["trainingPlanWorkoutScheduleDTOS"]
    if not isinstance(plans, list):
        raise ValueError("Invalid Garmin Coach plans")
    candidates = []
    malformed = False
    for plan in plans:
        if not isinstance(plan, dict):
            malformed = True
            continue
        details = plan.get("trainingPlanDetailsDTO")
        if not isinstance(details, dict):
            details = {}
        if details.get("planCompleted") is True or plan.get("trainingPlanClassification") == "ITP":
            continue
        rows = plan.get("workoutScheduleSummaries")
        if rows is None:
            rows = []
        summary = coach_plan_summary(plan, details)
        if not isinstance(rows, list) or not ("trainingPlanId" in summary or "name" in summary):
            malformed = True
            continue
        sports, entries, published = set(), [], False
        for row in rows:
            try:
                day = date.fromisoformat(row["scheduleDate"])
            except (KeyError, TypeError, ValueError):
                malformed = True
                continue
            sport = coach_sport(row.get("workoutType"))
            sports.add(sport)
            if (first <= day <= last and (sport in (None, "running")
                    or row.get("isRestDay") is True or row.get("race") is True)):
                published = True
                if row.get("associatedActivityId") is None:
                    entries.append(coach_entry(row, day, plan))
        candidates.append((summary, coach_plan_sport(plan, details), sports, published, entries))
    return bool(plans), candidates, malformed


def coach_workouts(garmin, request, *, verify_membership=False):
    try:
        start, end = (date.fromisoformat(request[key]) for key in ("startDate", "endDate"))
    except (ValueError, KeyError, TypeError):
        return {"status": "invalid_input"}
    if start.isoformat() != request["startDate"] or end.isoformat() != request["endDate"] or (end - start).days != 6:
        return {"status": "invalid_input"}

    weeks, entries, candidates = [], [], []
    plan_sports = {}
    observed_plan = False
    day = start
    while day <= end:
        monday = day - timedelta(days=day.weekday())
        sunday = monday + timedelta(days=6)
        coverage = {"weekStartDate": monday.isoformat(), "status": "empty", "plans": []}
        weeks.append(coverage)
        try:
            has_plans, plans, malformed = coach_week(garmin, day, min(sunday, end))
            observed_plan |= has_plans
            if malformed:
                coverage.update(status="empty" if plans else "error", error="coach_malformed_data")
            candidates.append((coverage, plans))
            for plan, sport, sports, _, _ in plans:
                if "trainingPlanId" in plan:
                    plan_types, workout_types = plan_sports.setdefault(plan["trainingPlanId"], (set(), set()))
                    if sport is not None:
                        plan_types.add(sport)
                    workout_types.update(sports)
        except Exception as error:
            coverage.update(status="error", error=failure(error, "coach_read_failed")["status"])
        day = sunday + timedelta(days=1)

    # Weeks share the output budget. Reserve space for the response envelope.
    weekly_budget = MAX_RESPONSE_BYTES // len(weeks) - 256
    for coverage, plans in candidates:
        week_entries = []
        for plan, sport, sports, published, upcoming in plans:
            plan_types, workout_types = plan_sports.get(plan.get("trainingPlanId"), (set(), sports))
            inferred_running = not plan_types and "running" in workout_types and "other" not in workout_types
            if sport == "running" or (sport is None and (plan_types == {"running"} or inferred_running)):
                coverage["plans"].append(plan)
                week_entries.extend(upcoming)
                if published:
                    coverage["status"] = "published"
        if len(json.dumps([coverage, week_entries], ensure_ascii=False).encode("utf-8")) > weekly_budget:
            coverage.update(status="error", error="workout_detail_limit", plans=[])
        else:
            entries.extend(week_entries)

    if any("error" in coverage for coverage in weeks):
        status = "coach_read_failed" if all(coverage["status"] == "error" for coverage in weeks) else "partial"
    else:
        status = "ok" if entries else "no_data"
        if not observed_plan:
            # An empty schedule can be unpublished. Check enrollment before reporting no active plan.
            try:
                if garmin.get_training_plans()["trainingPlanList"] == []:
                    status = "no_active_plan"
            except Exception as error:
                if verify_membership:
                    return failure(error, "coach_read_failed")
    return {"status": status, "data": {"startDate": start.isoformat(), "endDate": end.isoformat(),
                                       "weeks": weeks,
                                       "entries": sorted(entries, key=lambda entry: entry["date"])}}


WORKOUT_STEP_FIELDS = (
    "type", "stepOrder", "stepType", "description", "endCondition", "endConditionValue",
    "endConditionCompare", "endConditionZone", "preferredEndConditionUnit",
    "targetType", "targetValueOne", "targetValueTwo", "targetValueUnit", "zoneNumber",
    "secondaryTargetType", "secondaryTargetValueOne", "secondaryTargetValueTwo",
    "secondaryTargetValueUnit", "secondaryZoneNumber", "numberOfIterations", "skipLastRestStep",
)
UNIT_FIELDS = ("unitKey", "unitId", "factor")
SPORT_FIELDS = ("sportTypeKey", "sportTypeId")
COACH_STEP_METADATA = {
    "stepType": ("stepTypeKey", "stepTypeId"),
    "endCondition": ("conditionTypeKey", "conditionTypeId"),
    "preferredEndConditionUnit": UNIT_FIELDS,
    "endConditionValueUnit": UNIT_FIELDS,
    "targetType": ("workoutTargetTypeKey", "workoutTargetTypeId"),
    "targetValueUnit": UNIT_FIELDS,
    "secondaryTargetType": ("workoutTargetTypeKey", "workoutTargetTypeId"),
    "secondaryTargetValueUnit": UNIT_FIELDS,
}
SPEED_UNITS = {"m/s", "mps", "meter_per_second", "meters_per_second", "metre_per_second", "metres_per_second"}


def canonical_coach_uuid(value):
    return value.lower() if isinstance(value, str) and COACH_UUID.fullmatch(value) else None


def detail_number(value):
    return type(value) is int or (type(value) is float and isfinite(value))


def detail_fields(data, names, metadata=None):
    content = {}
    for key in names:
        value = data.get(key)
        if key == "skipLastRestStep":
            if type(value) is bool:
                content[key] = value
        elif detail_number(value) or isinstance(value, str):
            content[key] = value
        elif isinstance(value, dict) and metadata and key in metadata:
            fields = detail_fields(value, metadata[key])
            if fields:
                content[key] = fields
    return content


def ordered_coach_items(items, order_key):
    if items is None:
        return []
    if not isinstance(items, list) or not all(isinstance(item, dict) for item in items):
        raise ValueError("Invalid Garmin Coach structure")
    orders = [item.get(order_key) for item in items]
    if all(type(order) is int for order in orders) and len(set(orders)) == len(orders):
        return sorted(items, key=lambda item: item[order_key])
    return items


def coach_pace(target_type, unit, first_speed, second_speed):
    key = target_type.get("workoutTargetTypeKey") if isinstance(target_type, dict) else target_type
    unit_key = unit.get("unitKey") if isinstance(unit, dict) else unit
    if key != "pace.zone" or (unit is not None
            and (not isinstance(unit_key, str) or unit_key.lower() not in SPEED_UNITS)):
        return None
    speeds = (first_speed, second_speed)
    if not all(detail_number(speed) and speed > 0 for speed in speeds):
        return None
    # Garmin's adaptive pace.zone speeds default to m/s when no unit is provided.
    paces = [int(Fraction(1000) / Fraction(str(speed)) + Fraction(1, 2)) for speed in speeds]
    return {"fast": min(paces), "slow": max(paces)}


def usable_step_type(value, key, identifier):
    if isinstance(value, dict):
        type_id = value.get(identifier)
        if type(type_id) is int and type_id > 0:
            return True
        value = value.get(key)
    return isinstance(value, str) and bool(value.strip())


def coach_step(step):
    if "numberOfIterations" in step:
        iterations = step["numberOfIterations"]
        if type(iterations) is not int or iterations <= 0:
            raise ValueError("Invalid Garmin Coach repeat count")
    fields = (*WORKOUT_STEP_FIELDS, "endConditionValueUnit")
    content = detail_fields(step, fields, COACH_STEP_METADATA)
    for target, unit, first, second, pace in (
        ("targetType", "targetValueUnit", "targetValueOne", "targetValueTwo", "paceSecondsPerKm"),
        ("secondaryTargetType", "secondaryTargetValueUnit", "secondaryTargetValueOne",
         "secondaryTargetValueTwo", "secondaryPaceSecondsPerKm"),
    ):
        bounds = coach_pace(step.get(target), step.get(unit), step.get(first), step.get(second))
        if bounds is not None:
            content[pace] = bounds
    if "workoutSteps" in step:
        content["workoutSteps"] = [coach_step(child) for child in ordered_coach_items(step["workoutSteps"], "stepOrder")]
    step_type = content.get("stepType")
    step_key = step_type.get("stepTypeKey") if isinstance(step_type, dict) else step_type
    is_repeat = content.get("type") == "RepeatGroupDTO" or "numberOfIterations" in content or step_key == "repeat"
    if is_repeat and not content.get("workoutSteps"):
        raise ValueError("Invalid Garmin Coach repeat group")
    description = content.get("description")
    if not (content.get("workoutSteps") or (isinstance(description, str) and description.strip())
            or any(usable_step_type(content.get(field), *COACH_STEP_METADATA[field])
                   for field in ("stepType", "endCondition", "targetType", "secondaryTargetType"))):
        raise ValueError("Invalid Garmin Coach step")
    return content


def incompatible_coach_sport(data):
    return any(coach_sport(data.get(field)) in {"other", "supplemental"} for field in ("sportType", "sport"))


def coach_segment(segment):
    if incompatible_coach_sport(segment):
        raise ValueError("Unexpected Garmin Coach segment sport")
    content = detail_fields(segment, ("segmentOrder", "sportType"), {"sportType": SPORT_FIELDS})
    if not content and "workoutSteps" not in segment:
        raise ValueError("Invalid Garmin Coach segment")
    steps = ordered_coach_items(segment.get("workoutSteps"), "stepOrder")
    return {**content, "workoutSteps": [coach_step(step) for step in steps]}


def coach_detail_summary(workout):
    summary = {}
    for key, value in (("name", workout.get("workoutName")), ("sport", coach_sport_key(workout.get("sportType"))),
                       ("workoutType", workout.get("workoutPhrase"))):
        if isinstance(value, str) and value.strip():
            summary[key] = value
    for key, sources in (("estimatedDurationSeconds", ("estimatedDurationInSecs", "estimatedDuration")),
                         ("estimatedDistanceMeters", ("estimatedDistanceInMeters", "estimatedDistance"))):
        for source in sources:
            if detail_number(workout.get(source)):
                summary[key] = workout[source]
                break
    return summary


def coach_workout_details(garmin, request, workout_uuid):
    schedule = coach_workouts(garmin, request, verify_membership=True)
    if "data" not in schedule:
        return schedule
    entries = schedule["data"]["entries"]
    matching = [entry for entry in entries if canonical_coach_uuid(entry.get("workoutUuid")) == workout_uuid]
    selected = next((entry for entry in matching if entry.get("isRestDay") is not True), None)
    if selected is None:
        weeks = schedule["data"]["weeks"]
        errors = [week["error"] for week in weeks if "error" in week]
        if errors:
            shared = next((error for error in errors if error not in {"coach_read_failed", "coach_malformed_data"}),
                          "coach_read_failed")
            return {"status": shared}
        if schedule["status"] == "no_active_plan":
            return {"status": "coach_workout_not_found"}
        # A published plan cannot establish coverage for another plan.
        plan_ids = {plan.get("trainingPlanId") for week in weeks for plan in week["plans"]}
        if len(plan_ids) != 1 or None in plan_ids or any(len(week["plans"]) != 1 for week in weeks):
            return {"status": "coach_read_failed"}
        complete = len({entry["date"] for entry in entries}) == 7 and all(
            entry.get("isRestDay") is True or canonical_coach_uuid(entry.get("workoutUuid")) for entry in entries)
        return {"status": "coach_workout_not_found" if matching or complete else "coach_read_failed"}

    try:
        workout = garmin.connectapi(f"workout-service/fbt-adaptive/{workout_uuid}")
    except Exception as error:
        if http_status(error) == 404:
            return {"status": "coach_workout_not_found"}
        return failure(error, "coach_read_failed")
    if not isinstance(workout, dict) or incompatible_coach_sport(workout):
        return {"status": "coach_read_failed"}
    if "workoutUuid" in workout and canonical_coach_uuid(workout["workoutUuid"]) != workout_uuid:
        return {"status": "coach_read_failed"}
    summary = coach_detail_summary(workout)
    try:
        segments = [coach_segment(segment) for segment in ordered_coach_items(workout.get("workoutSegments"), "segmentOrder")]
    except (ValueError, RecursionError):
        return {"status": "coach_read_failed"}
    has_steps = any(segment["workoutSteps"] for segment in segments)
    if not has_steps and "workoutUuid" not in workout and not (summary.get("name") or summary.get("workoutType")):
        return {"status": "coach_read_failed"}
    summary_fields = ("name", "sport", "workoutType", "estimatedDurationSeconds", "estimatedDistanceMeters")
    return {"status": "ok" if has_steps else "no_data",
            "data": {**detail_fields(selected, summary_fields), **summary, "workoutUuid": workout_uuid,
                     "workout": {"workoutSegments": segments if has_steps else []}}}


def copy_step(step):
    content = selected_fields(step, (*WORKOUT_STEP_FIELDS, "strokeType", "equipmentType", "drillType",
                                     "category", "exerciseName", "weightValue", "weightUnit"))
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


def account_verified(garmin, expected_id):
    recent = garmin.get_activities(0, 20)
    return isinstance(recent, list) and any(isinstance(row, dict) and row.get("activityId") == expected_id
                                            for row in recent)


def device_number(value):
    if isinstance(value, str) and re.fullmatch(r"[0-9]{1,19}", value):
        value = int(value)
    if type(value) is int and 0 < value <= 2**63 - 1:
        return value
    raise ValueError("Invalid Garmin device ID")


def valid_send_name(value):
    if not isinstance(value, str) or len(value) > 512 or not value.strip():
        return False
    try:
        value.encode("utf-8")
    except UnicodeEncodeError:
        return False
    return True


def device_list(garmin):
    rows = garmin.get_devices()
    if not isinstance(rows, list):
        raise ValueError("Invalid Garmin device list")
    devices = {}
    seen = set()
    for row in rows:
        if not isinstance(row, dict):
            continue
        device_id = row.get("deviceId")
        try:
            number = device_number(device_id)
        except ValueError:
            continue
        if number in seen:
            devices.pop(number, None)
            continue
        seen.add(number)
        name = next((name for name in (row.get("displayName"), row.get("productDisplayName"))
                     if valid_send_name(name)), None)
        if name is not None:
            devices[number] = {"deviceId": str(device_id), "name": name}
    return devices


def queue_device_message(garmin, message, unavailable):
    try:
        client = garmin.client
        if client._token_expires_soon():
            client._refresh_session()
        headers = client.get_api_headers()
        session = client._fresh_api_session()
    except Exception as error:
        return device_read_failure(error, unavailable)
    # The library request helper replays a 401. The queue only gets one POST.
    try:
        with session as transport, transport.request("POST", f"{client._connectapi}/device-service/devicemessage/messages",
                                                      json=[message], headers=headers, timeout=(4, 10), stream=True,
                                                      allow_redirects=False) as response:
            status = response.status_code
            if status in {200, 204}:
                return {"status": "queued"}
            if status == 429:
                return {"status": "garmin_rate_limited"}
            if 400 <= status < 500 and status != 408:
                return {"status": "device_send_rejected"}
    except ConnectTimeout:
        return {"status": unavailable}
    except Exception:
        pass
    return {"status": "device_send_unconfirmed"}


def device_read_failure(error, unavailable):
    status = {401: "course_account_unverified" if unavailable == "garmin_unavailable" else "workout_session_unavailable",
              403: "garmin_access_denied", 429: "garmin_rate_limited"}.get(http_status(error), unavailable)
    return {"status": status}


def device_operation(garmin, request, course_bridge=None):
    courses = course_bridge is not None
    kind = "course" if courses else "workout"
    unavailable = "garmin_unavailable" if courses else "workout_service_unavailable"
    list_only = request["operation"] == "list_devices"
    id_key = f"{kind}Id"
    if not list_only:
        content_id = request.get(id_key)
        device_id = request.get("deviceId")
        confirm = request.get("confirm", False)
        if (type(content_id) is not int or not 0 < content_id <= 2**63 - 1 or not isinstance(device_id, str)
                or type(confirm) is not bool or ("workoutId" in request and "courseId" in request)):
            return {"status": "invalid_input"}
        try:
            number = device_number(device_id)
        except ValueError:
            return {"status": "invalid_input"}
    try:
        verify_account = course_bridge["account_verified"] if courses else account_verified
        if not verify_account(garmin, request["expectedActivityId"]):
            return {"status": f"{kind}_account_unverified"}
        if list_only:
            devices = list(device_list(garmin).values())
            return {"status": "ok" if devices else "no_data", "data": {"devices": devices}}
        read_content = course_bridge["owned_detail"] if courses else library_workout
        content = read_content(garmin, content_id)
        if content is None:
            return {"status": f"{kind}_not_found"}
        name = content[f"{kind}Name"]
        if not valid_send_name(name):
            raise ValueError("Invalid Garmin content name")
        device = device_list(garmin).get(number)
        if device is None:
            return {"status": "device_not_found"}
    except LibraryLimit:
        return {"status": "workout_library_limit"}
    except Exception as error:
        if courses and isinstance(error, course_bridge["CourseError"]):
            return {"status": error.status}
        return device_read_failure(error, unavailable)
    data = {id_key: content_id, "name": name, "deviceId": device_id, "deviceName": device["name"]}
    if not confirm:
        return {"status": "preview", "data": data}
    message_url = (f"course-service/course/fit/{content_id}/{number}?elevation=true" if courses
                   else f"workout-service/workout/FIT/{content_id}")
    reply = queue_device_message(garmin, {"deviceId": number, "messageName": name, "groupName": None,
                                         "fileType": "FIT", "metaDataId": content_id, "messageType": f"{kind}s",
                                         "priority": 0 if courses else 1, "messageUrl": message_url}, unavailable)
    return {**reply, "data": data} if reply["status"] == "queued" else reply


def publish(garmin, request):
    operation = request["operation"]
    if operation == "coach_workout_details":
        workout_uuid = canonical_coach_uuid(request.get("workoutUuid"))
        if workout_uuid is None:
            return {"status": "invalid_input"}
    try:
        verified = account_verified(garmin, request["expectedActivityId"])
    except Exception as error:
        if operation == "coach_workout_details":
            return failure(error, "coach_read_failed")
        raise
    if not verified:
        return {"status": "workout_account_unverified"}
    if operation == "set_weight":
        return set_weight(garmin, request)
    if operation == "create":
        return create(garmin, request["workout"])
    if operation == "create_strength":
        return save_copy(garmin, build_strength_payload(request["workout"]), strength_shape, unique_name=True)
    if operation == "search":
        return search_workouts(garmin, request)
    if operation == "coach_workouts":
        return coach_workouts(garmin, request)
    if operation == "coach_workout_details":
        return coach_workout_details(garmin, request, workout_uuid)
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


def session_lock(token_file):
    directory = Path(tempfile.gettempdir()) / f"garmin-connect-locks-{os.getuid()}"
    directory.mkdir(mode=0o700, exist_ok=True)
    owner = os.getuid()
    directory_fd = os.open(directory, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        info = os.fstat(directory_fd)
        if info.st_uid != owner or info.st_mode & 0o077:
            raise PermissionError("Invalid Garmin session lock directory")
        key = hashlib.sha256(os.fsencode(token_file.resolve())).hexdigest()
        lock_fd = os.open(key, os.O_WRONLY | os.O_CREAT | os.O_NOFOLLOW, 0o600, dir_fd=directory_fd)
    finally:
        os.close(directory_fd)
    info = os.fstat(lock_fd)
    if not stat.S_ISREG(info.st_mode) or info.st_uid != owner or info.st_mode & 0o077 or info.st_nlink != 1:
        os.close(lock_fd)
        raise PermissionError("Invalid Garmin session lock file")
    return os.fdopen(lock_fd, "a")


def main(token_dir, courses=False):
    limit = 8 * 1024 * 1024 if courses else 16_384
    raw = sys.stdin.buffer.read(limit + 1)
    if len(raw) > limit:
        return {"status": "course_source_too_large" if courses else "workout_rejected"}
    request = json.loads(raw)
    token_file = Path(token_dir) / "garmin_tokens.json"
    with session_lock(token_file) as lock:
        result = locked_publish(lock, token_file, request, courses)
    if courses and request["operation"] in DEVICE_OPERATIONS:
        if len(json.dumps(result, ensure_ascii=False).encode("utf-8")) > MAX_RESPONSE_BYTES:
            return {"status": "course_response_too_large"}
    return result


def locked_publish(lock, token_file, request, courses):
    deadline = time.monotonic() + 8
    while True:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            break
        except BlockingIOError:
            if time.monotonic() >= deadline:
                return {"status": "course_busy" if courses else "workout_service_unavailable"}
            time.sleep(0.05)
    unavailable = "course_account_unverified" if courses else "workout_session_unavailable"
    if not token_file.is_file() or token_file.stat().st_mode & 0o077:
        return {"status": unavailable}
    garmin = Garmin()
    try:
        stored = token_file.read_text(encoding="utf-8")
        garmin.client.loads(stored)
    except Exception:
        return {"status": unavailable}
    before = garmin.client.dumps()
    device = request["operation"] in DEVICE_OPERATIONS
    try:
        if courses:
            module = runpy.run_path(str(Path(__file__).with_name("garmin_courses_bridge.py")))
            if device:
                return device_operation(garmin, request, module)
            return module["publish"](garmin, request)
        if device:
            return device_operation(garmin, request)
        return publish(garmin, request)
    finally:
        try:
            refreshed = garmin.client.dumps()
            if refreshed != before:
                save_session(token_file, refreshed)
        except (OSError, TypeError, ValueError):
            if not courses and not device:
                raise


def save_session(token_file, refreshed):
    temporary = tempfile.NamedTemporaryFile(mode="w", dir=token_file.parent, delete=False, encoding="utf-8")
    try:
        with temporary:
            temporary.write(refreshed)
        os.replace(temporary.name, token_file)
    finally:
        Path(temporary.name).unlink(missing_ok=True)


def bounded_output(result, courses):
    output = json.dumps(result, ensure_ascii=False)
    export = courses and isinstance(result.get("data"), dict) and "contentBase64" in result["data"]
    limit = 8 * 1024 * 1024 if courses else MAX_RESPONSE_BYTES
    if export:
        limit = 4 * ((limit + 2) // 3) + 1024
    if len(output.encode("utf-8")) > limit:
        return '{"status":"course_response_too_large"}' if courses else '{"status":"workout_detail_limit"}'
    return output


if __name__ == "__main__":
    os.umask(0o077)
    logging.disable(logging.CRITICAL)
    courses = len(sys.argv) > 2 and sys.argv[2] == "courses"
    try:
        result = main(sys.argv[1], courses)
    except LibraryLimit:
        result = {"status": "workout_library_limit"}
    except Exception as error:
        result = failure(error, "garmin_unavailable" if courses else "workout_service_unavailable")
    print(bounded_output(result, courses))
