package io.github.trekkerdrove.garminmcp.application.service;

import io.github.trekkerdrove.garminmcp.application.dto.GarminFailure;
import io.github.trekkerdrove.garminmcp.application.dto.ToolResult;
import io.github.trekkerdrove.garminmcp.application.port.out.ActivityReader;
import io.github.trekkerdrove.garminmcp.application.port.out.GarminDevices;
import io.github.trekkerdrove.garminmcp.application.port.out.GarminDevices.Content;
import java.util.Map;

public final class DeviceSendService {
    private final ActivityReader activities;
    private final GarminDevices devices;
    private final boolean workoutWrites;
    private final boolean courseWrites;

    public DeviceSendService(ActivityReader activities, GarminDevices devices, boolean workoutWrites, boolean courseWrites) {
        this.activities = activities;
        this.devices = devices;
        this.workoutWrites = workoutWrites;
        this.courseWrites = courseWrites;
    }

    public ToolResult list() {
        Content accountType = workoutWrites ? Content.WORKOUT : Content.COURSE;
        return devices.list(accountActivityId(accountType), accountType);
    }

    public ToolResult send(Long workoutId, Long courseId, String deviceId, boolean confirm) {
        if ((workoutId == null) == (courseId == null) || deviceId == null || !deviceId.matches("[0-9]{1,19}")
                || Long.parseLong(deviceId) <= 0) {
            throw new IllegalArgumentException("Expected one content ID and a positive decimal device ID");
        }
        Content content = workoutId != null ? Content.WORKOUT : Content.COURSE;
        long contentId = workoutId != null ? workoutId : courseId;
        if (contentId <= 0) throw new IllegalArgumentException("Expected a positive content ID");
        if (!(content == Content.WORKOUT ? workoutWrites : courseWrites)) {
            throw new GarminFailure("device_send_disabled");
        }
        return devices.send(accountActivityId(content), content, contentId, deviceId, confirm);
    }

    private long accountActivityId(Content content) {
        var recent = activities.search(Map.of(), 0, 1, ActivityReader.SearchView.SUMMARY);
        if (recent.isEmpty() || recent.getFirst().id() <= 0) {
            throw new GarminFailure(content == Content.WORKOUT ? "workout_account_unverified" : "course_account_unverified");
        }
        return recent.getFirst().id();
    }
}
