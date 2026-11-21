package io.github.trekkerdrove.garminmcp.application.port.out;

public interface GarminArchive {
    byte[] activityArchive(long activityId, int maxBytes);
}
