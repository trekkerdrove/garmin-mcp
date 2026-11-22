package io.github.trekkerdrove.garminmcp.infrastructure.external.garmin;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.github.trekkerdrove.garminmcp.application.dto.CourseData.*;
import io.github.trekkerdrove.garminmcp.application.port.out.CourseRouter;
import io.github.trekkerdrove.garminmcp.application.service.CourseService;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "mcp.public-url=https://courses.example/mcp", "mcp.oauth.issuer=https://issuer.example",
        "mcp.oauth.owner-subject=owner", "garmin.workouts.token-directory=.",
        "garmin.courses.enabled=true", "garmin.courses.writes-enabled=true", "garmin.courses.routing.url=https://ors.example"
})
class CourseMcpIntegrationTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PUBLIC_URL = "https://courses.example/mcp";
    private static final String ISSUER = "https://issuer.example";
    private static final String TOOLS = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}";
    private final HttpClient http = HttpClient.newHttpClient();
    @LocalServerPort int port;
    @Autowired RestTemplate oauthHttp;
    @TestBean(methodName = "fakeService", enforceOverride = true) CourseService courseService;
    @TestBean(methodName = "fakeRouter", enforceOverride = true) CourseRouter courseRouting;

    static CourseService fakeService() {
        return CourseServiceTest.service(new CourseServiceTest.FixtureLibrary(), new CourseFormats());
    }

    static CourseRouter fakeRouter() {
        return request -> {
            assertThat(request.start()).isEqualTo(Map.of("latitude", 47, "longitude", 2));
            var points = List.of(new Point(47.0, 2.0, 0.0, null), new Point(47.01, 2.01, 20.0, null),
                    new Point(47.0, 2.0, 0.0, null));
            return List.of(new Route(new Trace(List.of(points), List.of(), null, "trail_running", null, "openrouteservice",
                    new Metrics(2_800d, 20d, 20d, 1d, "openrouteservice"), List.of(), List.of()), null, null));
        };
    }

    @Test
    void publishesNineOwnerToolsAndExecutesTheStructuredHttpContract() throws Exception {
        var key = new RSAKeyGenerator(2048).keyID("course-test").generate();
        var oauth = MockRestServiceServer.bindTo(oauthHttp).ignoreExpectOrder(true).build();
        oauth.expect(requestTo(ISSUER + "/.well-known/openid-configuration")).andRespond(withSuccess(
                JSON.writeValueAsString(Map.of("issuer", ISSUER, "jwks_uri", ISSUER + "/keys")), MediaType.APPLICATION_JSON));
        oauth.expect(requestTo(ISSUER + "/keys")).andRespond(withSuccess(
                new JWKSet(key.toPublicJWK()).toString(), MediaType.APPLICATION_JSON));
        var requestToken = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
                new JWTClaimsSet.Builder().subject("owner").issuer(ISSUER).audience(PUBLIC_URL)
                        .expirationTime(Date.from(Instant.now().plusSeconds(120))).build());
        requestToken.sign(new RSASSASigner(key));
        String owner = requestToken.serialize();

        assertThat(send(HttpRequest.BodyPublishers.ofString(TOOLS), null).statusCode()).isEqualTo(401);
        var tools = send(HttpRequest.BodyPublishers.ofString(TOOLS), owner);
        assertThat(tools.statusCode()).isEqualTo(200);
        var courseTools = new ArrayList<String>();
        for (var tool : JSON.readTree(tools.body()).at("/result/tools")) {
            String name = tool.path("name").asString();
            if (name.contains("course")) courseTools.add(name);
        }
        assertThat(courseTools).containsExactlyInAnyOrder("garmin_plan_course", "garmin_create_course", "garmin_list_courses",
                "garmin_get_course", "garmin_update_course", "garmin_duplicate_course", "garmin_export_course",
                "garmin_delete_course", "garmin_get_course_operation");
        assertThat(tools.body()).doesNotContain("garmin_create_workout", "garmin_set_weight");

        var planned = call("garmin_plan_course", Map.of("sport", "trail_running", "shape", "loop",
                "start", Map.of("latitude", 47, "longitude", 2), "targetDistanceMeters", 2_800), owner);
        assertThat(planned.path("status").asString()).isEqualTo("ok");
        String planId = planned.at("/data/items/0/planId").asString();
        var preview = call("garmin_create_course", Map.of("name", "Départ", "source", Map.of("kind", "plan", "planId", planId)), owner);
        assertThat(preview.path("status").asString()).isEqualTo("preview");
        String operationId = preview.at("/data/operationId").asString();
        assertThat(call("garmin_create_course", Map.of("operationId", operationId, "confirm", true, "name", "Changed"), owner)
                .path("status").asString()).isEqualTo("invalid_input");
        var confirmed = call("garmin_create_course", Map.of("operationId", operationId, "confirm", true), owner);
        assertThat(confirmed.path("status").asString()).isEqualTo("created");
        long courseId = confirmed.at("/data/courseId").asLong();
        assertThat(call("garmin_get_course", Map.of("courseId", courseId, "view", "detail"), owner)
                .at("/data/geometry/pointCount").asInt()).isEqualTo(3);
        assertThat(call("garmin_get_course_operation", Map.of("operationId", operationId), owner)).isEqualTo(confirmed);
        assertThat(call("garmin_list_courses", Map.of("query", "depart"), owner).at("/data/items/0/name").asString()).isEqualTo("Départ");

        String gpx = "<gpx version=\"1.1\"><rte><name>Test</name><rtept lat=\"47\" lon=\"2\"/><rtept lat=\"47.01\" lon=\"2\"/></rte><!--"
                + "x".repeat(4 * 1024 * 1024 - 300) + "--></gpx>";
        var imported = call("garmin_create_course", Map.of("source", Map.of("kind", "file", "format", "gpx", "text", gpx)), owner);
        assertThat(imported.path("status").asString()).isEqualTo("preview");

        var huge = (" ".repeat(8 * 1024 * 1024 + 1)).getBytes(StandardCharsets.UTF_8);
        var rejected = send(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(huge)), owner);
        assertThat(rejected.statusCode()).isEqualTo(413);
        assertThat(rejected.body()).contains("course_source_too_large");
        oauth.verify();
    }

    private JsonNode call(String tool, Map<String, Object> args, String token) throws Exception {
        var rpc = HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(Map.of("jsonrpc", "2.0", "id", 1,
                "method", "tools/call", "params", Map.of("name", tool, "arguments", args))));
        var reply = send(rpc, token);
        assertThat(reply.statusCode()).as(reply.body()).isEqualTo(200);
        var envelope = JSON.readTree(reply.body()).path("result");
        if (envelope.has("structuredContent")) return envelope.path("structuredContent");
        return JSON.readTree(envelope.at("/content/0/text").asString(""));
    }

    private HttpResponse<String> send(HttpRequest.BodyPublisher body, String token) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp")).POST(body)
                .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream");
        if (token != null) builder.header("Authorization", "Bearer " + token);
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
