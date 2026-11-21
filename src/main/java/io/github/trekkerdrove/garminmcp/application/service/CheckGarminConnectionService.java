package io.github.trekkerdrove.garminmcp.application.service;

import io.github.trekkerdrove.garminmcp.application.dto.ConnectionResult;
import io.github.trekkerdrove.garminmcp.application.port.in.CheckGarminConnection;
import io.github.trekkerdrove.garminmcp.application.port.out.GarminActivityProbe;

public class CheckGarminConnectionService implements CheckGarminConnection {

    private final GarminActivityProbe garmin;

    public CheckGarminConnectionService(GarminActivityProbe garmin) {
        this.garmin = garmin;
    }

    @Override
    public ConnectionResult checkConnection() {
        return switch (garmin.probeActivity()) {
            case ACTIVITY_FOUND -> new ConnectionResult("connected", true);
            case NO_ACTIVITY -> new ConnectionResult("connected", false);
            case MISSING_KEY -> new ConnectionResult("missing_garmin_key", null);
            case INVALID_KEY -> new ConnectionResult("invalid_garmin_key", null);
            case ACCESS_DENIED -> new ConnectionResult("garmin_access_denied", null);
            case RATE_LIMITED -> new ConnectionResult("garmin_rate_limited", null);
            case UNAVAILABLE -> new ConnectionResult("garmin_unavailable", null);
        };
    }
}
