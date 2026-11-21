package io.github.trekkerdrove.garminmcp.application.port.out;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Only selected activity measurements cross the adapter boundary. */
public interface ActivityReader {
    enum SearchView { SUMMARY, ZONES, STRENGTH, POWER, SPLITS }

    List<Activity> search(Map<String, Object> filters, int offset, int limit, SearchView view);
    List<Sport> types();
    Detail detail(long id);
    Selection workouts(long id, int limit);
    Selection wellness(LocalDate start, LocalDate end, int limit);
    List<Night> sleep(LocalDate firstWakeDate, LocalDate lastWakeDate);

    record Sport(String key, String parent) {}
    record Night(LocalDate wakeDate, Long seconds) {}
    record Selection(List<Map<String, Object>> rows, boolean truncated) {}
    record Activity(long id, Long parentId, LocalDate date, String sport, Map<String, Object> summary,
                    Map<String, Object> heartZones, Map<String, Object> powerZones,
                    Selection exercises, Map<Integer, Double> powerCurve, Map<Integer, Double> splits) {
        public Double measurement(String name) {
            return summary.get(name) instanceof Number n ? n.doubleValue() : null;
        }
    }
    record Detail(long id, LocalDate date, Map<String, Object> summary, Map<String, Object> effect,
                  Selection splits) {}
}
