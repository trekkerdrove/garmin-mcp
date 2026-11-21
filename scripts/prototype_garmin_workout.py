# /// script
# requires-python = ">=3.10"
# dependencies = ["garminconnect==0.3.2"]
# ///
"""Create one unscheduled test workout through a local Garmin Connect session."""

import argparse
import getpass
import json
import logging
import os
from pathlib import Path
import sys

from garminconnect import Garmin


WORKOUT_NAME = "MCP TEST - marche 5 min"
DESCRIPTION = "Prototype Garmin MCP Java - verification ecriture locale"


def workout_payload():
    walking = {"sportTypeId": 12, "sportTypeKey": "walking"}
    return {
        "workoutName": WORKOUT_NAME,
        "description": DESCRIPTION,
        "sportType": walking,
        "workoutSegments": [
            {
                "segmentOrder": 1,
                "sportType": walking,
                "workoutSteps": [
                    {
                        "type": "ExecutableStepDTO",
                        "stepOrder": 1,
                        "stepType": {"stepTypeId": 3, "stepTypeKey": "interval"},
                        "endCondition": {"conditionTypeId": 2, "conditionTypeKey": "time"},
                        "endConditionValue": 300.0,
                        "targetType": {"workoutTargetTypeId": 1, "workoutTargetTypeKey": "no.target"},
                    }
                ],
            }
        ],
    }


def save_session(token_dir):
    if not sys.stdin.isatty() or not sys.stderr.isatty():
        raise SystemExit("Ouvre un terminal local pour saisir tes identifiants.")

    email = input("E-mail Garmin Connect : ").strip()
    password = getpass.getpass("Mot de passe Garmin Connect : ")
    garmin = Garmin()
    garmin.client.login(email, password, prompt_mfa=lambda: getpass.getpass("Code MFA : "))
    if not garmin.client.is_authenticated:
        raise SystemExit("Garmin Connect n’a fourni aucune session utilisable.")

    token_dir.mkdir(mode=0o700, parents=True, exist_ok=True)
    if token_dir.stat().st_mode & 0o077:
        raise SystemExit("Le répertoire de session doit être privé (0700).")
    garmin.client.dump(str(token_dir))
    print("Session Garmin Connect enregistrée.")


def create_and_verify(garmin, expected_activity_id):
    recent = garmin.get_activities(0, 20)
    if not isinstance(recent, list) or not any(
        activity.get("activityId") == expected_activity_id for activity in recent
    ):
        raise SystemExit("La session Connect ne correspond pas au compte Personal API attendu.")

    for offset in range(0, 1000, 100):
        workouts = garmin.get_workouts(start=offset, limit=100)
        if not isinstance(workouts, list):
            raise SystemExit("Le format de la bibliothèque Garmin est inattendu.")
        existing = next(
            (workout for workout in workouts if workout.get("workoutName") == WORKOUT_NAME),
            None,
        )
        if existing is not None or len(workouts) < 100:
            break
    else:
        raise SystemExit("Bibliothèque trop grande pour vérifier l’absence de doublon.")

    created = existing is None
    response = garmin.upload_workout(workout_payload()) if created else existing
    workout_id = int(response["workoutId"])
    if workout_id <= 0:
        raise SystemExit("Garmin n’a pas donné d’identifiant pour la séance.")
    saved = garmin.get_workout_by_id(workout_id)
    steps = [step for segment in saved["workoutSegments"] for step in segment["workoutSteps"]]
    if (
        int(saved["workoutId"]) != workout_id
        or saved["workoutName"] != WORKOUT_NAME
        or saved["description"] != DESCRIPTION
        or saved["sportType"]["sportTypeKey"] != "walking"
        or len(steps) != 1
        or steps[0]["endCondition"]["conditionTypeKey"] != "time"
        or steps[0]["endConditionValue"] != 300
    ):
        raise SystemExit("La séance relue ne correspond pas au prototype.")
    return {"created": created, "verified": True, "workoutId": workout_id, "name": WORKOUT_NAME}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("preview", "login", "create"))
    parser.add_argument("--token-dir", type=Path)
    parser.add_argument("--expected-activity", type=int)
    args = parser.parse_args()
    if args.action == "preview":
        print(json.dumps(workout_payload(), indent=2))
        return
    if args.token_dir is None:
        parser.error("--token-dir est requis.")
    if args.action == "create" and (args.expected_activity is None or args.expected_activity <= 0):
        parser.error("--expected-activity doit identifier une activité du compte Personal API.")

    os.umask(0o077)
    logging.disable(logging.CRITICAL)
    try:
        if args.action == "login":
            save_session(args.token_dir)
            return
        garmin = Garmin()
        garmin.login(str(args.token_dir.resolve()))
        print(json.dumps(create_and_verify(garmin, args.expected_activity)))
    except Exception as error:
        status = getattr(getattr(error, "response", None), "status_code", None)
        http_status = f", HTTP {status}" if isinstance(status, int) else ""
        raise SystemExit(f"Échec Garmin Connect ({type(error).__name__}{http_status}).") from None


if __name__ == "__main__":
    main()
