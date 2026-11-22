package io.github.trekkerdrove.garminmcp.application.port.out;

import io.github.trekkerdrove.garminmcp.application.dto.CourseData.PlanRequest;
import io.github.trekkerdrove.garminmcp.application.dto.CourseData.Route;
import java.util.List;

public interface CourseRouter {
    List<Route> route(PlanRequest request);
}
