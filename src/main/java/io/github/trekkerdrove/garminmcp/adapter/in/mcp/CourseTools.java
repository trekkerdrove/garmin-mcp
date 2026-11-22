package io.github.trekkerdrove.garminmcp.adapter.in.mcp;

import io.github.trekkerdrove.garminmcp.application.dto.CourseData.*;
import io.github.trekkerdrove.garminmcp.application.dto.ToolResult;
import io.github.trekkerdrove.garminmcp.application.port.out.CourseRouter;
import io.github.trekkerdrove.garminmcp.application.service.CourseService;
import io.github.trekkerdrove.garminmcp.application.service.CourseService.Action;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "garmin.courses.enabled", havingValue = "true")
class CourseTools {
    static final String SPORTS = "running, trail_running, hiking, road_biking, mountain_biking, gravel_cycling. "
            + "walking maps to hiking, cycling to road_biking.";
    static final String SOURCE = "Discriminated source kind: file (format=gpx,tcx,fit,geojson; text or contentBase64, "
            + "FIT requires base64, optional fileName and zero-based selectionIndex), points (latitude, longitude, "
            + "optional elevationMeters and timestamp with offset), geojson (geometry), activity (activityId), "
            + "or plan (planId). Up to 4 MiB per decoded file and 50,000 trace points.";
    static final String POINTS = "Up to 500 navigation points with name, Garmin type, and coordinates or distanceMeters. "
            + "An optional timestamp can identify the position on the trace. Supported types depend on Garmin verification.";
    static final String CONFIRM = "Default false. After approval, set true with only operationId from this tool's preview.";
    static final String OPERATION = "Confirmation uses only this preview operationId and confirm=true.";
    static final String ID = "Positive Garmin courseId from the owner's library.";
    static final String LOSS = "Default false. Set true after reviewing the losses reported with the same arguments.";
    private final CourseService service;

    CourseTools(CourseService service) {
        this.service = service;
    }

    @McpTool(name = "garmin_list_courses", description = "List owner courses with name and sport filters and opaque pagination.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true))
    public ToolResult list(@McpToolParam(required = false, description = "Case- and accent-insensitive name filter.") String query,
            @McpToolParam(required = false, description = SPORTS) String sport,
            @McpToolParam(required = false, description = "Page size, default 20 and maximum 50.") Integer limit,
            @McpToolParam(required = false, description = "nextCursor from a previous page with the same filters.") String cursor) {
        return call(() -> service.list(query, sport, limit, cursor));
    }

    @McpTool(name = "garmin_get_course", description = "Read an owner course summary, details, or a revision-bound geometry page.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true))
    public ToolResult get(@McpToolParam(description = ID) Long courseId,
            @McpToolParam(required = false, description = "summary (default), detail or geometry.") String view,
            @McpToolParam(required = false, description = "Zero-based point offset for a geometry view.") Integer offset,
            @McpToolParam(required = false, description = "Maximum points per geometry page, up to 500.") Integer limit,
            @McpToolParam(required = false, description = "Required after the first page of geometry.") String revision) {
        return call(() -> service.getCourse(courseId, view, offset, limit, revision));
    }

    @McpTool(name = "garmin_export_course", description = "Export owner course bytes with SHA-256 and independently encoded base64 windows.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true))
    public ToolResult export(@McpToolParam(description = ID) Long courseId,
            @McpToolParam(description = "gpx, tcx, fit or geojson. Format support depends on available Garmin data.") Format format,
            @McpToolParam(required = false, description = "Decoded-byte offset, default zero. Each window is at most 256 KiB.") Integer offsetBytes,
            @McpToolParam(required = false, description = "SHA-256 from the first window. Required for continuation.") String expectedSha256) {
        return call(() -> service.export(courseId, format, offsetBytes, expectedSha256));
    }

    static ToolResult call(Supplier<ToolResult> action) {
        return ToolResult.run(() -> {
            try {
                return action.get();
            } catch (Problem failure) {
                return new ToolResult(failure.status(), failure.details());
            }
        });
    }
}

@Component
@ConditionalOnProperty(name = {"garmin.courses.enabled", "garmin.courses.writes-enabled"}, havingValue = "true")
class CourseWriteTools {
    private final CourseService service;

    CourseWriteTools(CourseService service) {
        this.service = service;
    }

    @McpTool(name = "garmin_create_course", description = "Preview and confirm a private course from a supported trace source.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true))
    public ToolResult create(@McpToolParam(required = false, description = CourseTools.SOURCE) Source source,
            @McpToolParam(required = false, description = "Course name, up to 128 characters. Defaults to source name.") String name,
            @McpToolParam(required = false, description = CourseTools.SPORTS) String sport,
            @McpToolParam(required = false, description = "Description, up to 512 characters.") String description,
            @McpToolParam(required = false, description = CourseTools.POINTS) List<NavigationPoint> coursePoints,
            @McpToolParam(required = false, description = CourseTools.LOSS) Boolean allowDataLoss,
            @McpToolParam(required = false, description = CourseTools.OPERATION) String operationId,
            @McpToolParam(required = false, description = CourseTools.CONFIRM) Boolean confirm) {
        boolean hasIntent = source != null || name != null || sport != null || description != null || coursePoints != null || allowDataLoss != null;
        return write(Action.create, null, new Changes(name, description, sport, source, coursePoints, allowDataLoss), hasIntent, operationId, confirm);
    }

    @McpTool(name = "garmin_update_course", description = "Preview and confirm an in-place course edit. Omitted changes preserve existing data.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = true, idempotentHint = true))
    public ToolResult update(@McpToolParam(required = false, description = CourseTools.ID) Long courseId,
            @McpToolParam(required = false, description = "name (128 chars), description (512 chars, empty clears), sport, "
                    + "source, coursePoints (empty list clears), allowDataLoss. Fields require Garmin verification. "
                    + CourseTools.SPORTS + " " + CourseTools.SOURCE + " " + CourseTools.POINTS) Changes changes,
            @McpToolParam(required = false, description = CourseTools.OPERATION) String operationId,
            @McpToolParam(required = false, description = CourseTools.CONFIRM) Boolean confirm) {
        return write(Action.update, courseId, changes, courseId != null || changes != null, operationId, confirm);
    }

    @McpTool(name = "garmin_duplicate_course", description = "Preview and confirm an independent private copy with a different name.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true))
    public ToolResult duplicate(@McpToolParam(required = false, description = CourseTools.ID) Long courseId,
            @McpToolParam(required = false, description = "New name, up to 128 characters and different from the source.") String name,
            @McpToolParam(required = false, description = "Optional replacement description, up to 512 characters.") String description,
            @McpToolParam(required = false, description = CourseTools.SPORTS) String sport,
            @McpToolParam(required = false, description = CourseTools.LOSS) Boolean allowDataLoss,
            @McpToolParam(required = false, description = CourseTools.OPERATION) String operationId,
            @McpToolParam(required = false, description = CourseTools.CONFIRM) Boolean confirm) {
        boolean hasIntent = courseId != null || name != null || description != null || sport != null || allowDataLoss != null;
        return write(Action.duplicate, courseId, new Changes(name, description, sport, null, null, allowDataLoss), hasIntent, operationId, confirm);
    }

    @McpTool(name = "garmin_delete_course", description = "Preview an irreversible deletion, then confirm the exact course and revision shown.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = true, idempotentHint = true))
    public ToolResult delete(@McpToolParam(required = false, description = CourseTools.ID) Long courseId,
            @McpToolParam(required = false, description = CourseTools.OPERATION) String operationId,
            @McpToolParam(required = false, description = CourseTools.CONFIRM) Boolean confirm) {
        return write(Action.delete, courseId, null, courseId != null, operationId, confirm);
    }

    @McpTool(name = "garmin_get_course_operation", description = "Read a course operation and reconcile uncertain results using reads.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true))
    public ToolResult operation(@McpToolParam(description = "Opaque operationId returned by the course preview.") String operationId) {
        return CourseTools.call(() -> service.operation(operationId));
    }

    private ToolResult write(Action action, Long courseId, Changes changes, boolean hasIntent, String operationId, Boolean confirm) {
        return CourseTools.call(() -> {
            if (Boolean.TRUE.equals(confirm)) {
                if (operationId == null || operationId.isBlank() || hasIntent) throw new Problem("invalid_input");
                return service.confirm(action, operationId);
            }
            if (operationId != null || !hasIntent) throw new Problem("invalid_input");
            return service.prepare(action, courseId, changes);
        });
    }
}

@Component
@ConditionalOnExpression("!'${garmin.courses.routing.url:}'.isBlank()")
class CoursePlanningTool {
    private final CourseService service;
    private final CourseRouter router;

    CoursePlanningTool(CourseService service, CourseRouter router) {
        this.service = service;
        this.router = router;
    }

    @McpTool(name = "garmin_plan_course", description = "Plan up to three real network routes through openrouteservice.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true))
    public ToolResult plan(@McpToolParam(description = CourseTools.SPORTS) String sport,
            @McpToolParam(description = "point_to_point or loop.") String shape,
            @McpToolParam(description = "Start {latitude, longitude} or a place name with a configured geocoder.") Object start,
            @McpToolParam(required = false, description = "Up to five ordered coordinates or place names.") List<Object> via,
            @McpToolParam(required = false, description = "End point or place for point_to_point.") Object finish,
            @McpToolParam(required = false, description = "Loop target distance in meters, required for a loop without waypoints.") Double targetDistanceMeters,
            @McpToolParam(required = false, description = "Maximum distance difference in meters. Default 10% of target.") Double distanceToleranceMeters,
            @McpToolParam(required = false, description = "Avoid ferries, fords or steps.") List<String> avoid,
            @McpToolParam(required = false, description = "Number of proposed alternatives, from 1 to 3.") Integer alternatives) {
        return CourseTools.call(() -> service.plan(router, new PlanRequest(sport, shape, start, via, finish, targetDistanceMeters,
                distanceToleranceMeters, avoid, alternatives)));
    }
}
