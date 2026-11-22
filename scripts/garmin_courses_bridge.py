"""Project Garmin courses without replaying a mutation on a transport failure."""

import base64
from datetime import datetime, timezone
import hashlib
import json
import math
import time

MAX_BODY = 8 * 1024 * 1024
MAX_EXPORT_BYTES = 8 * 1024 * 1024
MAX_LIST = 2_000
MAX_POINTS = 50_000
MAX_NAVIGATION = 500
ROOT = "/course-service/course"
OWNER = "/web-gateway/course/owner/"
SPORT_IDS = {"running": 1, "hiking": 3, "gravel_cycling": 143,
             "mountain_biking": 5, "trail_running": 6, "road_biking": 10}
SPORT_NAMES = {value: key for key, value in SPORT_IDS.items()} | {2: "road_biking", 4: "other", 9: "hiking"}
SETTINGS = ("hasPaceBand", "hasPowerGuide", "hasTurnDetectionDisabled", "matchedToSegments", "includeLaps", "favorite")
NAVIGATION_EXTRAS = ("note", "cutoffDuration", "restDuration", "elevation", "derivedElevation", "uuid")


class CourseError(Exception):
    def __init__(self, status, retry_after=None):
        self.status = status
        self.retry_after = retry_after


def result(status, data=None):
    return {"status": status, "data": data}


def response_bytes(response, limit):
    body = bytearray()
    for chunk in response.iter_content(chunk_size=8192):
        body.extend(chunk)
        if len(body) > limit:
            raise CourseError("course_response_too_large")
    return bytes(body)


def request(garmin, method, path, *, payload=None, binary=False, params=None):
    client = garmin.client
    if client._token_expires_soon():
        client._refresh_session()
    headers = client.get_api_headers()
    if binary:
        headers = {**headers, "Accept": "*/*"}
    # The pinned request helper replays a 401 for every HTTP verb and buffers the complete body.
    with client._fresh_api_session() as session:
        with session.request(method, f"{client._connectapi}{path}", json=payload, params=params,
                             headers=headers, timeout=(4, 10), stream=True,
                             allow_redirects=False) as response:
            status = response.status_code
            if status in {401, 403, 412}:
                raise CourseError("garmin_access_denied")
            if status == 404:
                return None
            if status == 429:
                body = response_bytes(response, 16_384).lower()
                if method in {"PUT", "DELETE"} and b"is not yet ready to delete or update" in body:
                    raise CourseError("course_not_ready", 2)
                retry = response.headers.get("Retry-After", "")
                try:
                    delay = max(0, int(retry))
                except ValueError:
                    from email.utils import parsedate_to_datetime
                    try:
                        delay = max(0, math.ceil((parsedate_to_datetime(retry) - datetime.now(timezone.utc)).total_seconds()))
                    except (TypeError, ValueError, OverflowError):
                        delay = 2
                raise CourseError("garmin_rate_limited", min(delay, 2**31 - 1))
            if status in {400, 405, 409, 422}:
                raise CourseError("course_edit_unsupported" if method != "GET" else "garmin_unavailable")
            if not 200 <= status < 300:
                raise CourseError("garmin_unavailable")
            if status == 204:
                return {}
            body = response_bytes(response, MAX_EXPORT_BYTES if binary else MAX_BODY)
            return body if binary else json.loads(body)


def identifier(value):
    return type(value) is int and 0 < value <= 2**63 - 1


def text(value):
    return value if isinstance(value, str) else None


def number(value):
    return value if type(value) in {int, float} and math.isfinite(value) else None


def first(row, *keys):
    for key in keys:
        if row.get(key) is not None:
            return row[key]
    return None


def instant(value):
    if value is None:
        return None
    if type(value) in {int, float}:
        return datetime.fromtimestamp(value / 1_000, timezone.utc).isoformat()
    value = datetime.fromisoformat(value.replace("Z", "+00:00"))
    return None if value.tzinfo is None else value.isoformat()


def milliseconds(value):
    if value is None:
        return None
    stamp = datetime.fromisoformat(value.replace("Z", "+00:00")).astimezone(timezone.utc)
    epoch = datetime(1970, 1, 1, tzinfo=timezone.utc)
    delta = stamp - epoch
    return (delta.days * 86400 + delta.seconds) * 1_000 + delta.microseconds // 1_000


def summary(row):
    if not identifier(row.get("courseId")) or not text(row.get("courseName")):
        raise CourseError("garmin_unavailable")
    modified = None
    for field in ("modifiedDate", "lastModifiedDate", "updatedDate", "lastUpdatedDate", "createdDate"):
        if row.get(field) is not None:
            try:
                modified = instant(row[field])
            except (TypeError, ValueError, OverflowError):
                continue
            if modified is not None:
                break
    return {"courseId": row["courseId"], "name": row["courseName"],
            "sport": SPORT_NAMES.get(row.get("activityTypePk")),
            "privacy": {1: "public", 2: "private"}.get(row.get("rulePK")),
            "distanceMeters": number(first(row, "distanceMeter", "distanceInMeters")),
            "ascentMeters": number(first(row, "elevationGainMeter", "elevationGainInMeters")),
            "descentMeters": number(first(row, "elevationLossMeter", "elevationLossInMeters")),
            "modifiedAt": modified}


def owner_library(garmin):
    envelope = request(garmin, "GET", OWNER)
    if not isinstance(envelope, dict) or not isinstance(envelope.get("coursesForUser"), list):
        raise CourseError("garmin_unavailable")
    rows = envelope["coursesForUser"]
    complete = (len(rows) <= MAX_LIST and not envelope.get("nextCursor") and not envelope.get("hasMore")
                and envelope.get("complete") is not False and envelope.get("truncated") is not True)
    if type(envelope.get("totalCount")) is int and envelope["totalCount"] > len(rows):
        complete = False
    items = {}
    for row in rows[:MAX_LIST]:
        try:
            item = summary(row)
            if item["courseId"] in items:
                complete = False
            items[item["courseId"]] = item
        except (CourseError, TypeError, AttributeError):
            complete = False
    return items, complete


def detail(garmin, course_id):
    document = request(garmin, "GET", f"{ROOT}/{course_id}")
    if document is not None and (not isinstance(document, dict) or document.get("courseId") != course_id):
        raise CourseError("garmin_unavailable")
    return document


def owned_detail(garmin, course_id):
    items, complete = owner_library(garmin)
    if course_id not in items:
        if not complete:
            raise CourseError("course_busy")
        return None
    return detail(garmin, course_id)


def trace_point(point):
    latitude, longitude = number(point.get("latitude")), number(point.get("longitude"))
    if latitude is None or longitude is None:
        raise CourseError("garmin_unavailable")
    return {"latitude": latitude, "longitude": longitude, "elevationMeters": number(point.get("elevation")),
            "timestamp": instant(point.get("timestamp"))}


def navigation(point):
    kind = text(point.get("coursePointType"))
    normalized = {"HORS_CATEGORIE": "hors_category"}.get(kind, kind.lower() if kind else None)
    timestamp = point.get("timestamp")
    return {"name": text(point.get("name")), "type": normalized,
            "latitude": number(first(point, "lat", "latitude")), "longitude": number(first(point, "lon", "longitude")),
            "distanceMeters": number(point.get("distance")), "timestamp": None if timestamp == 0 else instant(timestamp)}


def revision(document):
    keys = ("courseId", "courseName", "description", "activityTypePk", "rulePK", "geoPoints",
            "courseLines", "coursePoints") + SETTINGS
    return fingerprint({key: document.get(key) for key in keys})


def fingerprint(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, ensure_ascii=False, allow_nan=False).encode()).hexdigest()


def snapshot(document):
    row = summary(document)
    raw = document.get("geoPoints")
    if raw is not None and not isinstance(raw, list):
        raise CourseError("garmin_unavailable")
    raw = raw or []
    lines = document.get("courseLines") or []
    if not isinstance(lines, list):
        raise CourseError("garmin_unavailable")
    segments = [raw] if raw else []
    if not raw and lines:
        ordered = sorted(lines, key=lambda line: line.get("sortOrder", 0))
        segments = [line["points"] for line in ordered if line.get("points")]
    navigation_points = document.get("coursePoints") or []
    if not isinstance(navigation_points, list) or any(not isinstance(segment, list) for segment in segments):
        raise CourseError("garmin_unavailable")
    if sum(map(len, segments)) > MAX_POINTS or len(navigation_points) > MAX_NAVIGATION:
        raise CourseError("course_response_too_large")
    related = [name for key, name in (("hasPaceBand", "PacePro"), ("hasPowerGuide", "Power Guide"),
                                     ("matchedToSegments", "Garmin segments"), ("favorite", "Favorite"))
               if document.get(key) is True]
    warnings = []
    if any(first(point, "note", "cutoffDuration", "restDuration") is not None for point in navigation_points):
        warnings.append("Additional Garmin navigation notes or durations are not represented in projected course points.")
    if document.get("hasTurnDetectionDisabled") is True or document.get("includeLaps") is True:
        warnings.append("Garmin turn-detection or lap settings are not included in exported navigation.")
    content = {key: row[key] for key in ("name", "sport", "privacy")}
    content.update(description=text(document.get("description")),
                   segments=[[trace_point(point) for point in segment] for segment in segments],
                   coursePoints=[navigation(point) for point in navigation_points])
    return {"summary": row, "content": content, "version": revision(document),
            "settingsVersion": fingerprint([document.get(field) for field in SETTINGS]),
            "navigationVersion": fingerprint([[point.get(field) for field in NAVIGATION_EXTRAS]
                                              for point in navigation_points]),
            "related": related, "warnings": warnings}


def distance_meters(previous, current):
    lat1, lat2 = math.radians(previous["latitude"]), math.radians(current["latitude"])
    dlon = math.radians(math.remainder(current["longitude"] - previous["longitude"], 360))
    a = math.sin((lat2 - lat1) / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin(dlon / 2) ** 2
    return 2 * 6_371_008.8 * math.asin(math.sqrt(min(1, a)))


def geometry(content):
    points = [{"latitude": point["latitude"], "longitude": point["longitude"],
               "elevation": point.get("elevationMeters"), "distance": None,
               "timestamp": milliseconds(point.get("timestamp"))}
              for segment in content["segments"] for point in segment]
    if len(points) < 2:
        raise CourseError("course_no_geometry")
    if any(point["timestamp"] is not None for point in points):
        if points[0]["timestamp"] is None:
            raise CourseError("invalid_input")
        points[0]["distance"] = 0.0
        for previous, point in zip(points, points[1:]):
            step = distance_meters(previous, point)
            if point["timestamp"] is None or point["timestamp"] <= previous["timestamp"] or step <= 0:
                raise CourseError("invalid_input")
            point["distance"] = previous["distance"] + step
    return {"geoPoints": points, "startPoint": points[0], "courseLines": []}


def course_points(content):
    return [{"name": point["name"], "coursePointType": {"hors_category": "HORS_CATEGORIE"}.get(point["type"], point["type"].upper()),
             "lat": point["latitude"],
             "lon": point["longitude"], "distance": point["distanceMeters"], "timestamp": milliseconds(point.get("timestamp"))}
            for point in content["coursePoints"]]


def create_payload(content):
    return {**geometry(content), "activityTypePk": SPORT_IDS[content["sport"]], "courseName": content["name"],
            "description": content.get("description"), "coursePoints": course_points(content),
            "rulePK": 2, "coordinateSystem": "WGS84", "sourceTypeId": 3, "openStreetMap": False,
            "hasTurnDetectionDisabled": False, "elapsedSeconds": None, "matchedToSegments": False,
            "includeLaps": False, "hasPaceBand": False, "hasPowerGuide": False, "favorite": False,
            "speedMeterPerSecond": None}


def update_payload(document, content, fields):
    edited = dict(document)
    if "name" in fields:
        edited["courseName"] = content["name"]
    if "description" in fields:
        edited["description"] = content.get("description")
    if "sport" in fields:
        edited["activityTypePk"] = SPORT_IDS[content["sport"]]
    if "source" in fields:
        edited.update(geometry(content))
        for key in ("distanceMeter", "distanceInMeters", "elevationGainMeter", "elevationGainInMeters",
                    "elevationLossMeter", "elevationLossInMeters", "boundingBox"):
            edited.pop(key, None)
    if "coursePoints" in fields:
        edited["coursePoints"] = course_points(content)
    return edited


def mutate(garmin, command, args):
    course_id = args.get("courseId")
    if command == "create":
        method, path, payload = "POST", ROOT, create_payload(args["content"])
    else:
        method = "PUT" if command == "update" else "DELETE"
        path = f"{ROOT}/{course_id}"

    for attempt in range(3):
        if command != "create":
            document = owned_detail(garmin, course_id)
            if document is None:
                return result("already_absent" if command == "delete" else "course_not_found")
            if revision(document) != args["version"]:
                return result("course_changed")
            payload = update_payload(document, args["content"], args["fields"]) if command == "update" else None
        try:
            reply = request(garmin, method, path, payload=payload)
            if command == "create":
                created_id = reply.get("courseId") if isinstance(reply, dict) else None
                if not identifier(created_id):
                    return result("course_write_unconfirmed")
                return result("accepted", {"courseId": created_id})
            if reply is None:
                return result("course_not_found") if command == "update" else result("course_write_unconfirmed")
            return result("accepted", {"courseId": course_id})
        except CourseError as error:
            if error.status == "course_not_ready" and command != "create" and attempt < 2:
                time.sleep(1)
                continue
            if error.status in {"course_not_ready", "garmin_rate_limited", "garmin_access_denied", "course_edit_unsupported"}:
                return result(error.status, {"retryAfterSeconds": error.retry_after})
            return result("course_write_unconfirmed")
        except Exception:
            return result("course_write_unconfirmed")


def account_verified(garmin, expected_id):
    recent = request(garmin, "GET", garmin.garmin_connect_activities, params={"start": 0, "limit": 20})
    return isinstance(recent, list) and any(isinstance(row, dict) and row.get("activityId") == expected_id
                                            for row in recent)


def publish(garmin, args):
    command = args["operation"].removeprefix("course_")
    try:
        if not account_verified(garmin, args["expectedActivityId"]):
            return result("course_account_unverified")
        if command == "list":
            items, complete = owner_library(garmin)
            return result("ok", {"items": list(items.values()), "complete": complete})
        if command == "create":
            return mutate(garmin, command, args)
        course_id = args.get("courseId")
        if not identifier(course_id):
            return result("invalid_input")
        if command in {"update", "delete"}:
            return mutate(garmin, command, args)
        if command == "absent":
            # The application calls this only after a previously verified owned DELETE target.
            return result("absent" if detail(garmin, course_id) is None else "present")
        if command == "get":
            document = owned_detail(garmin, course_id)
            return result("absent") if document is None else result("ok", snapshot(document))
        if command == "export":
            owned, complete = owner_library(garmin)
            if course_id not in owned:
                return result("absent" if complete else "course_busy")
            format_name = args.get("format")
            if format_name not in {"gpx", "fit"}:
                return result("course_export_unsupported")
            path = f"{ROOT}/{format_name}/{course_id}" + ("/0" if format_name == "fit" else "")
            params = {"elevation": "true"} if format_name == "fit" else None
            raw = request(garmin, "GET", path, binary=True, params=params)
            if not isinstance(raw, bytes) or not raw:
                return result("course_export_unsupported")
            return result("ok", {"contentBase64": base64.b64encode(raw).decode("ascii")})
        return result("invalid_input")
    except CourseError as error:
        return result(error.status, {"retryAfterSeconds": error.retry_after})
    except (ValueError, KeyError, TypeError, OverflowError, AttributeError):
        return result("garmin_unavailable")
    except Exception:
        return result("garmin_unavailable")
