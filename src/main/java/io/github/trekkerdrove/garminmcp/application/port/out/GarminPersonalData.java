package io.github.trekkerdrove.garminmcp.application.port.out;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public interface GarminPersonalData {
    enum WeightView { measurements, composition, summary }
    enum CycleView { summary, day }

    List<Map<String, Object>> weights(LocalDate start, LocalDate end, WeightView view);
    List<Map<String, Object>> goals(LocalDate start, LocalDate end);
    NutritionDay nutrition(LocalDate date, Long mealId, boolean journal);
    Map<String, Object> cycle(LocalDate date, CycleView view, boolean details, boolean analysis);
    Map<String, Object> profile();

    record NutritionDay(int foodCount, Map<String, Number> nutrients, Map<String, Object> journal,
            boolean truncated) {}
}
