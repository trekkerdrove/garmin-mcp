package io.github.trekkerdrove.garminmcp.application.service;

import io.github.trekkerdrove.garminmcp.application.dto.ConnectionResult;
import io.github.trekkerdrove.garminmcp.application.port.out.GarminActivityProbe.Outcome;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class CheckGarminConnectionServiceTest {

    @ParameterizedTest
    @CsvSource({
            "ACTIVITY_FOUND, connected, true",
            "NO_ACTIVITY, connected, false",
            "MISSING_KEY, missing_garmin_key,",
            "INVALID_KEY, invalid_garmin_key,",
            "ACCESS_DENIED, garmin_access_denied,",
            "RATE_LIMITED, garmin_rate_limited,",
            "UNAVAILABLE, garmin_unavailable,"
    })
    void preservesTheConnectionResultForEachGarminOutcome(Outcome outcome, String status, Boolean hasActivity) {
        var checkConnection = new CheckGarminConnectionService(() -> outcome);

        assertThat(checkConnection.checkConnection()).isEqualTo(new ConnectionResult(status, hasActivity));
    }
}
