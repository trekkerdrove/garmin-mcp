import unittest
from datetime import datetime
from unittest.mock import Mock

from garmin_workout_bridge import GarminConnectConnectionError, set_weight, weight_selection


DAY = "2026-03-29"
TIMESTAMP = DAY + "T07:30:00+02:00"


def row(measurement_id, grams, timestamp=TIMESTAMP, **fields):
    time = datetime.fromisoformat(timestamp)
    utc_epoch = int(time.timestamp() * 1000)
    return {"samplePk": measurement_id, "calendarDate": DAY, "weight": grams, "sourceType": "MANUAL",
            "timestampGMT": utc_epoch, "date": utc_epoch + int(time.utcoffset().total_seconds() * 1000), **fields}


class WeightTest(unittest.TestCase):
    def setUp(self):
        self.garmin = Mock()
        self.garmin.get_daily_weigh_ins.return_value = {"dateWeightList": []}

    def invoke(self, **args):
        return set_weight(self.garmin, {"date": DAY, "weightKg": 72.4, "timestamp": TIMESTAMP, "confirm": False, **args})

    def reads(self, *pages):
        self.garmin.get_daily_weigh_ins.side_effect = [{"dateWeightList": page} for page in pages]

    def test_create_preview_and_replay(self):
        self.assertEqual(self.invoke()["data"]["action"], "create")
        self.garmin.add_weigh_in.assert_not_called()
        saved = row(15, 72400, bmi=22.0)
        self.reads([], [saved], [saved])
        self.assertEqual(self.invoke(confirm=True)["status"], "created")
        self.assertEqual(self.invoke(confirm=True)["status"], "already_exists")
        self.garmin.add_weigh_in.assert_called_once_with(72.4, "kg", TIMESTAMP)

    def test_selection_and_scale_data_are_protected(self):
        scale = {**row(15, 72000, bodyFat=19.2), "sourceType": "INDEX"}
        self.reads([scale], [scale, row(16, 72000)])
        self.assertEqual(self.invoke(selection=weight_selection(scale), confirm=True)["status"], "weight_not_editable")
        choices = self.invoke()
        self.assertEqual(choices["status"], "selection_required")
        self.assertEqual([r["measurementId"] for r in choices["data"]["measurements"]], ["15", "16"])
        self.garmin.add_weigh_in.assert_not_called()
        self.garmin.delete_weigh_in.assert_not_called()

    def test_correct_one_id_at_its_original_time(self):
        original, saved = row(15, 72000), row(17, 72400)
        other = row(16, 73000, DAY + "T20:30:00+02:00")
        self.reads([original, other], [original, other, saved], [other, saved])
        result = self.invoke(confirm=True, selection=weight_selection(original), timestamp=DAY + "T00:00:00+01:00")
        self.assertEqual(result["status"], "updated")
        self.assertEqual(result["data"]["measurement"]["measurementId"], "17")
        self.garmin.add_weigh_in.assert_called_once_with(72.4, "kg", TIMESTAMP)
        self.garmin.delete_weigh_in.assert_called_once_with("15", DAY)

    def test_ambiguous_add_never_deletes_original(self):
        original = row(15, 72000)
        self.reads([original], [original])
        self.garmin.add_weigh_in.side_effect = TimeoutError()
        self.assertEqual(self.invoke(confirm=True, selection=weight_selection(original))["status"], "weight_write_unconfirmed")
        self.garmin.delete_weigh_in.assert_not_called()

    def test_retry_finishes_verified_replacement(self):
        original, saved = row(15, 72000), row(17, 72400)
        self.reads([original], [original, saved], [original, saved], [original, saved], [original, saved],
                   [original, saved], [saved], [saved])
        self.garmin.delete_weigh_in.side_effect = [TimeoutError(), None]
        selection = weight_selection(original)
        self.assertEqual(self.invoke(confirm=True, selection=selection)["status"], "weight_cleanup_pending")
        self.assertEqual(self.invoke(confirm=True, selection=selection)["status"], "updated")
        self.assertEqual(self.invoke(confirm=True, selection=selection)["status"], "already_exists")
        self.assertEqual(self.garmin.add_weigh_in.call_count, 1)

    def test_selection_rejects_changed_original(self):
        self.reads([row(15, 72100)])
        self.assertEqual(self.invoke(confirm=True, selection=weight_selection(row(15, 72000)))["status"],
                         "weight_measurement_changed")
        self.garmin.add_weigh_in.assert_not_called()
        self.garmin.delete_weigh_in.assert_not_called()

    def test_explicit_garmin_rejection_is_reported_without_cleanup(self):
        original = row(15, 72000)
        self.reads([original])
        self.garmin.add_weigh_in.side_effect = GarminConnectConnectionError("API Error 401")
        self.assertEqual(self.invoke(confirm=True, selection=weight_selection(original))["status"],
                         "workout_session_unavailable")
        self.garmin.delete_weigh_in.assert_not_called()

    def test_replacement_checks_wall_time_and_offset(self):
        original, saved = row(15, 72000), row(17, 72400)
        same_instant = row(16, 72400, DAY + "T06:30:00+01:00")
        self.reads([original, same_instant], [original, same_instant, saved], [same_instant, saved])
        self.assertEqual(self.invoke(confirm=True, selection=weight_selection(original))["status"], "updated")
        self.garmin.add_weigh_in.assert_called_once_with(72.4, "kg", TIMESTAMP)


if __name__ == "__main__":
    unittest.main()
