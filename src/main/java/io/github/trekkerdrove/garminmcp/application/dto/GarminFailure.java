package io.github.trekkerdrove.garminmcp.application.dto;

/** A public error code, never the upstream body. */
public final class GarminFailure extends RuntimeException {
    public GarminFailure(String status) {
        super(status);
    }

    public String status() {
        return getMessage();
    }
}
