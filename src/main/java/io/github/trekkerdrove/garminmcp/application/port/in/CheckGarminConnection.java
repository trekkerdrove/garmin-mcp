package io.github.trekkerdrove.garminmcp.application.port.in;

import io.github.trekkerdrove.garminmcp.application.dto.ConnectionResult;

public interface CheckGarminConnection {

    ConnectionResult checkConnection();
}
