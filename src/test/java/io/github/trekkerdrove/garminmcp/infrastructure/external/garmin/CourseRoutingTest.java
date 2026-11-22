package io.github.trekkerdrove.garminmcp.infrastructure.external.garmin;

import io.github.trekkerdrove.garminmcp.application.dto.CourseData.*;
import io.github.trekkerdrove.garminmcp.application.port.out.ActivityReader;
import io.github.trekkerdrove.garminmcp.application.port.out.CourseLibrary;
import io.github.trekkerdrove.garminmcp.application.port.out.CourseRouter;
import io.github.trekkerdrove.garminmcp.application.port.out.GarminArchive;
import io.github.trekkerdrove.garminmcp.application.service.GarminConnectService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class CourseRoutingTest {
    private static final String ORS = "https://ors.test";
    private static final String PELIAS = "https://pelias.test";
    private static final String DIRECTIONS = ORS + "/v2/directions/foot-hiking/geojson";
    private static final String REPLY = """
            {"type":"FeatureCollection","features":[{
              "type":"Feature","properties":{"summary":{"distance":15200},"ascent":51,"descent":50,
                "warnings":[{"code":1,"message":"Restricted access on part of the route"}]},
              "geometry":{"type":"LineString","coordinates":[[2,47.0001,10],[2.05,47.01,61],[2,47.0001,11]]}
            }]}
            """;
    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final CourseRouting router = new CourseRouting(ORS, "ors-key", PELIAS, "", Map.of(), new CourseFormats(), builder.build());
    private final Map<String, Double> start = Map.of("latitude", 47.0, "longitude", 2.0);

    @Test
    void routesAutomaticLoopWithVerifiedProfileAndReportsSnapAndProviderMetrics() {
        server.expect(requestTo(DIRECTIONS)).andExpect(method(HttpMethod.POST)).andExpect(header("Authorization", "ors-key"))
                .andExpect(jsonPath("$.coordinates[0][0]").value(2.0))
                .andExpect(jsonPath("$.options.round_trip.length").value(15_000.0))
                .andExpect(jsonPath("$.options.avoid_features[0]").value("ferries"))
                .andRespond(withSuccess(REPLY, MediaType.APPLICATION_JSON));
        var routes = router.route(new PlanRequest("trail_running", "loop", start, null, null, 15_000d, null, List.of("ferries"), 1));
        assertThat(routes).hasSize(1);
        assertThat(routes.getFirst().snapDistanceMeters()).isBetween(11d, 12d);
        assertThat(routes.getFirst().trace().metrics()).isEqualTo(new Metrics(15_200d, 51d, 50d, 1d, "openrouteservice"));
        assertThat(routes.getFirst().trace().source()).contains("OpenStreetMap");
        assertThat(routes.getFirst().trace().warnings()).contains("Restricted access on part of the route");
        server.verify();
    }

    @Test
    void keepsViaOrderAndReturnsToStartOnTheNetwork() {
        server.expect(requestTo(DIRECTIONS))
                .andExpect(content().json("""
                        {"coordinates":[[2.0,47.0],[2.05,47.01],[2.0,47.0]],"elevation":true,"instructions":false}
                        """, JsonCompareMode.STRICT))
                .andRespond(withSuccess(REPLY, MediaType.APPLICATION_JSON));
        assertThat(router.route(new PlanRequest("hiking", "loop", start,
                List.of(Map.of("latitude", 47.01, "longitude", 2.05)), null, null, null, null, 1))).hasSize(1);
        server.verify();
    }

    @Test
    void requestsAnExplicitGeocodingChoiceAndDistinguishesUnavailableRouting() {
        server.expect(requestTo(PELIAS + "/search?text=St%20Martin&size=5"))
                .andRespond(withSuccess("""
                        {"features":[
                          {"properties":{"label":"Saint Martin Nord"},"geometry":{"coordinates":[2,47]}},
                          {"properties":{"label":"Saint Martin Sud"},"geometry":{"coordinates":[2,46]}}
                        ]}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(DIRECTIONS)).andRespond(withServerError());
        assertThatThrownBy(() -> router.route(new PlanRequest("hiking", "loop", "St Martin", null, null, 15_000d, null, null, null)))
                .isInstanceOfSatisfying(Problem.class, failure -> {
                    assertThat(failure.status()).isEqualTo("location_selection_required");
                    assertThat(failure.details().toString()).contains("Saint Martin Nord", "Saint Martin Sud");
                });
        assertThatThrownBy(() -> router.route(new PlanRequest("hiking", "loop", start, null, null, 15_000d, null, null, null)))
                .hasMessage("routing_unavailable");
        server.verify();
    }

    @Test
    void createsOneSharedSessionWorkerAndKeepsReadOnlyToolsSeparate() throws ClassNotFoundException {
        var reads = Class.forName("io.github.trekkerdrove.garminmcp.adapter.in.mcp.CourseTools");
        var writes = Class.forName("io.github.trekkerdrove.garminmcp.adapter.in.mcp.CourseWriteTools");
        var config = new ApplicationContextRunner().withUserConfiguration(GarminConnectConfiguration.class,
                CourseConfiguration.class, CourseFormats.class, reads, writes)
                .withBean(ActivityReader.class, () -> mock(ActivityReader.class))
                .withBean(GarminArchive.class, () -> (id, max) -> new byte[0]);
        config.withPropertyValues("garmin.courses.enabled=true", "garmin.workouts.enabled=true",
                        "garmin.workouts.python=/python", "garmin.workouts.script=/script",
                        "garmin.workouts.token-directory=.", "garmin.workouts.session-file=")
                .run(context -> assertThat(context).hasSingleBean(GarminConnectWorker.class)
                        .hasSingleBean(CourseLibrary.class).hasSingleBean(GarminConnectService.class));
        config.withPropertyValues("garmin.courses.enabled=true", "garmin.workouts.python=/python",
                        "garmin.workouts.script=/script", "garmin.workouts.token-directory=.", "garmin.workouts.session-file=")
                .run(context -> assertThat(context).hasSingleBean(reads).doesNotHaveBean(writes));
        config.withPropertyValues("garmin.courses.routing.url=https://ors.test")
                .run(context -> assertThat(context).hasSingleBean(CourseRouter.class)
                        .doesNotHaveBean(GarminConnectWorker.class).doesNotHaveBean(CourseLibrary.class));
    }
}
