package io.github.trekkerdrove.garminmcp.application.port.out;

public interface GarminActivityProbe {

    Outcome probeActivity();

    enum Outcome {
        ACTIVITY_FOUND,
        NO_ACTIVITY,
        MISSING_KEY,
        INVALID_KEY,
        ACCESS_DENIED,
        RATE_LIMITED,
        UNAVAILABLE
    }
}
