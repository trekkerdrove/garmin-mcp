package io.github.trekkerdrove.garminmcp;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.trekkerdrove.garminmcp.application.dto.ConnectionResult;
import io.github.trekkerdrove.garminmcp.application.service.CheckGarminConnectionService;
import io.github.trekkerdrove.garminmcp.infrastructure.external.garmin.GarminHttpClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class McpIntegrationTest {

    private static final String PUBLIC_URL = "https://garmin.example/mcp";
    private static final String ISSUER = "https://issuer.example";
    private static final String OWNER = "garmin-owner";
    private static final String JWKS = ISSUER + "/keys";
    private static final String INITIALIZE = """
            {"jsonrpc":"2.0","id":0,"method":"initialize","params":{"protocolVersion":"2025-11-25",
             "capabilities":{},"clientInfo":{"name":"integration-test","version":"1"}}}
            """;
    private static final String LIST = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}";
    private static final String CALL = """
            {"jsonrpc":"2.0","id":2,"method":"tools/call",
             "params":{"name":"garmin_check_connection","arguments":{}}}
            """;

    private static final AtomicInteger TOKEN_REQUESTS = new AtomicInteger();
    private static final AtomicInteger ACTIVITY_REQUESTS = new AtomicInteger();
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final RSAKey KEY = signingKey();
    private static final HttpServer GARMIN = fakeGarmin();

    @LocalServerPort
    int port;

    @Autowired
    RestTemplate oauthHttp;

    @TestBean(methodName = "fakeGarminClient", enforceOverride = true)
    GarminHttpClient garminHttpClient;

    @DynamicPropertySource
    static void applicationProperties(DynamicPropertyRegistry registry) {
        registry.add("mcp.public-url", () -> PUBLIC_URL);
        registry.add("mcp.oauth.issuer", () -> ISSUER);
        registry.add("mcp.oauth.owner-subject", () -> OWNER);
    }

    static GarminHttpClient fakeGarminClient() {
        return new GarminHttpClient("fake-personal-key", "http://127.0.0.1:" + GARMIN.getAddress().getPort());
    }

    @AfterAll
    static void stopGarmin() {
        GARMIN.stop(0);
    }

    @Test
    void doesNotEchoAMalformedGarminKey() {
        var client = new GarminHttpClient("fake-secret\ninvalid", "http://127.0.0.1:" + GARMIN.getAddress().getPort());
        var checkConnection = new CheckGarminConnectionService(client);
        assertThat(checkConnection.checkConnection()).isEqualTo(new ConnectionResult("invalid_garmin_key", null));
    }

    @Test
    void authenticatesBeforeListingToolsOrReadingGarmin() throws Exception {
        var oauth = MockRestServiceServer.bindTo(oauthHttp).ignoreExpectOrder(true).build();
        oauth.expect(requestTo(ISSUER + "/.well-known/openid-configuration")).andRespond(withSuccess("""
                {"issuer":"https://issuer.example","jwks_uri":"https://issuer.example/keys"}
                """, MediaType.APPLICATION_JSON));
        oauth.expect(requestTo(JWKS)).andRespond(withSuccess(
                new JWKSet(KEY.toPublicJWK()).toString(), MediaType.APPLICATION_JSON));

        assertThat(get("/health").body()).contains("UP");
        assertThat(get("/.well-known/oauth-protected-resource").body()).contains(PUBLIC_URL, ISSUER);

        var anonymous = mcp(CALL, null, null);
        assertThat(anonymous.statusCode()).isEqualTo(401);
        assertThat(anonymous.headers().firstValue("WWW-Authenticate"))
                .contains("Bearer resource_metadata=\"https://garmin.example/.well-known/oauth-protected-resource\"");
        assertThat(mcp(LIST, null, null).statusCode()).isEqualTo(401);
        assertThat(mcp(CALL, jwt(OWNER, "https://other.example/mcp", 600), null).statusCode()).isEqualTo(401);
        var expired = mcp(CALL, jwt(OWNER, PUBLIC_URL, -120), null);
        assertThat(expired.statusCode()).isEqualTo(401);
        assertThat(expired.headers().firstValue("WWW-Authenticate").orElseThrow())
                .contains("error=\"invalid_token\"", "resource_metadata=");
        assertThat(mcp(CALL, jwt("other-user", PUBLIC_URL, 600), null).statusCode()).isEqualTo(403);

        String ownerToken = jwt(OWNER, PUBLIC_URL, 600);
        assertThat(mcp(CALL, ownerToken, "https://other.example").statusCode()).isEqualTo(403);
        assertThat(TOKEN_REQUESTS).hasValue(0);
        assertThat(ACTIVITY_REQUESTS).hasValue(0);

        var initialize = mcp(INITIALIZE, ownerToken, null);
        assertThat(initialize.statusCode()).isEqualTo(200);
        assertThat(initialize.body()).contains("serverInfo", "garmin-mcp");
        var tools = mcp(LIST, ownerToken, null);
        assertThat(tools.statusCode()).isEqualTo(200);
        assertThat(tools.body()).contains("garmin_check_connection", "\"readOnlyHint\":true");

        var first = mcp(CALL, ownerToken, null);
        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(toolText(first.body())))
                .isEqualTo(JSON.readTree("{\"status\":\"connected\",\"hasActivity\":true}"));
        assertThat(first.body()).doesNotContain("private-activity-name");
        var second = mcp(CALL, ownerToken, null);
        assertThat(JSON.readTree(toolText(second.body())))
                .isEqualTo(JSON.readTree("{\"status\":\"connected\",\"hasActivity\":false}"));
        assertThat(TOKEN_REQUESTS).hasValue(1);
        assertThat(ACTIVITY_REQUESTS).hasValue(2);
        exerciseCatalog(JSON.readTree(tools.body()).path("result").path("tools"), ownerToken);
        oauth.verify();
    }

    private void exerciseCatalog(JsonNode tools, String token) throws Exception {
        var names = new ArrayList<String>();
        for (var tool : tools) {
            String name = tool.path("name").asString();
            names.add(name);
            assertThat(tool.path("annotations").path("readOnlyHint").asBoolean()).as(name).isTrue();
            assertThat(tool.path("annotations").path("destructiveHint").asBoolean(true)).as(name).isFalse();
        }
        assertThat(names).containsExactlyInAnyOrderElementsOf(CatalogFixture.CATALOG).hasSize(36).doesNotHaveDuplicates();

        int before = CatalogFixture.REQUESTS.get();
        assertThat(call("garmin_get_activity", Map.of("activityId", -1), token).path("status").asString())
                .isEqualTo("invalid_input");
        assertThat(call("garmin_get_weight", Map.of("startDate", "2025-02-30"), token).path("status").asString())
                .isEqualTo("invalid_input");
        assertThat(CatalogFixture.REQUESTS).hasValue(before);

        for (var tool : tools) {
            String name = tool.path("name").asString();
            if (name.equals("garmin_check_connection")) {
                continue;
            }
            var properties = tool.path("inputSchema").path("properties");
            var args = new LinkedHashMap<String, Object>();
            if (properties.has("activityId")) args.put("activityId", 42);
            for (String field : new String[]{"date", "startDate", "endDate"}) {
                if (properties.has(field)) args.put(field, CatalogFixture.DAY);
            }
            JsonNode result = call(name, args, token);
            String expected = switch (name) {
                case "garmin_get_wellness_trends", "garmin_get_hrv",
                     "garmin_get_stress", "garmin_get_hydration", "garmin_get_body_battery",
                     "garmin_get_spo2", "garmin_get_fitness_age", "garmin_get_vo2max", "garmin_get_heart_rate_zones",
                     "garmin_get_respiration", "garmin_get_activity_strength_summary" -> "no_data";
                default -> "ok";
            };
            assertThat(result.path("status").asString()).as("%s %s", name, result).isEqualTo(expected);
            var data = result.path("data");
            switch (name) {
                case "running_distance" -> assertThat(data.path("distanceKm").asDouble()).isEqualTo(5);
                case "compare_sleep_by_training_day" -> assertThat(data.at("/training/meanSleepSeconds").asInt()).isEqualTo(24000);
                case "garmin_get_daily_summary", "garmin_get_steps" -> assertThat(data.at("/observations/0/values/totalSteps").asInt(-1)).isZero();
                case "garmin_get_heart_rate" -> assertThat(data.at("/observations/0/values/restingHeartRate").asInt()).isEqualTo(58);
                case "garmin_get_wellness_sessions" -> {
                    var session = data.at("/sessions/0");
                    assertThat(session.path("date").asString()).isEqualTo(CatalogFixture.DAY);
                    assertThat(session.at("/metrics/0/avgValue").asDouble()).isEqualTo(64.5);
                    assertThat(session.at("/zones/0/millisInZone").asInt()).isEqualTo(3000);
                }
                case "garmin_get_power_curve" -> assertThat(data.at("/efforts/60s/value").asInt()).isEqualTo(300);
                case "garmin_get_fit_samples", "garmin_analyze_activity_file" -> assertThat(data.path("matchedRows").asInt()).isPositive();
                case "garmin_get_profile_settings" -> assertThat(data.at("/settings/timeZone").asString()).isEqualTo("Europe/Paris");
            }
        }

        call("garmin_get_hrv", Map.of("startDate", CatalogFixture.DAY, "endDate", "2025-04-02"), token);
        var weights = call("garmin_get_weight", Map.of("startDate", CatalogFixture.DAY), token).path("data").path("rows");
        assertThat(weights.size()).isEqualTo(2);
        assertThat(weights.path(0).path("weightKg").asDouble()).isEqualTo(71.5);
        var summary = call("garmin_get_weight", Map.of("startDate", CatalogFixture.DAY, "view", "summary"), token);
        assertThat(summary.path("data").path("rows").path(0).path("latestWeightKg").asDouble()).isEqualTo(72);
        var meal = call("garmin_get_nutrition", Map.of("startDate", CatalogFixture.DAY, "mealId", 7), token).path("data");
        assertThat(meal.path("totals").path("calories").asInt()).isEqualTo(200);
        assertThat(meal.path("foodCount").asInt()).isEqualTo(1);
        var cycleArgs = Map.of("startDate", CatalogFixture.DAY, "view", "day", "details", true);
        assertThat(call("garmin_get_cycle", cycleArgs, token).toString()).contains("light").doesNotContain("userProfilePk");
        assertThat(call("garmin_get_activity", Map.of("activityId", 404), token).path("status").asString()).isEqualTo("not_found");
        assertThat(call("garmin_get_activity", Map.of("activityId", 429), token).path("status").asString()).isEqualTo("garmin_rate_limited");
        assertThat(call("garmin_get_fit_samples", Map.of("activityId", 413), token).path("status").asString())
                .isEqualTo("fit_limit_exceeded");
        assertThat(call("garmin_get_fit_samples", Map.of("activityId", 414), token).path("status").asString())
                .isEqualTo("fit_limit_exceeded");

        var filters = Map.of("search", "a & b", "requiredField", java.util.List.of("distance", "duration"));
        var page = call("garmin_search_activities", Map.of("limit", 1, "filters", filters), token);
        assertThat(page.path("status").asString()).isEqualTo("truncated");
        assertThat(page.at("/data/nextOffset").asInt()).isEqualTo(1);
        assertThat(page.at("/data/activities/0/activityId").asInt()).isEqualTo(45);
        var next = call("garmin_search_activities", Map.of("limit", 1, "offset", 1, "filters", filters), token);
        assertThat(next.at("/data/activities/0/activityId").asInt()).isEqualTo(46);
        int exchanges = TOKEN_REQUESTS.get();
        assertThat(call("garmin_get_activity", Map.of("activityId", 403), token).path("status").asString())
                .isEqualTo("garmin_access_denied");
        assertThat(call("garmin_get_activity", Map.of("activityId", 401), token).path("status").asString())
                .isEqualTo("garmin_access_denied");
        assertThat(TOKEN_REQUESTS).hasValue(exchanges);
        assertThat(call("garmin_get_activity", Map.of("activityId", 42), token).path("status").asString()).isEqualTo("ok");
        assertThat(TOKEN_REQUESTS).hasValue(exchanges + 1);
        assertThat(CatalogFixture.ROUTES).as("Distinct official GET endpoints reached via MCP").hasSize(28);
    }

    private JsonNode call(String name, Map<String, ?> arguments, String token) throws Exception {
        String body = JSON.writeValueAsString(Map.of("jsonrpc", "2.0", "id", 3, "method", "tools/call",
                "params", Map.of("name", name, "arguments", arguments)));
        var reply = mcp(body, token, null);
        assertThat(reply.statusCode()).as(name).isEqualTo(200);
        assertThat(reply.body()).as(name).doesNotContain("PRIVATE_FIELD", "fake-personal-key");
        var content = JSON.readTree(reply.body()).path("result");
        assertThat(content.path("isError").asBoolean(false)).as("%s %s", name, content).isFalse();
        return JSON.readTree(toolText(reply.body()));
    }

    private static String toolText(String body) {
        return JSON.readTree(body).path("result").path("content").path(0).path("text").asString();
    }

    private HttpResponse<String> get(String path) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(local(path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> mcp(String body, String token, String origin) throws Exception {
        var request = HttpRequest.newBuilder(local("/mcp"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("MCP-Protocol-Version", "2025-11-25")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (origin != null) {
            request.header("Origin", origin);
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI local(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private static HttpServer fakeGarmin() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            String garminToken = jwt("garmin", "garmin-api", 3600);
            server.createContext("/personalapitoken-service/personal-api/personal-api-key/token", exchange -> {
                if (!"POST".equals(exchange.getRequestMethod())
                        || !"ApiKey fake-personal-key".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                    reply(exchange, 401, "text/plain", "Unauthorized");
                    return;
                }
                TOKEN_REQUESTS.incrementAndGet();
                reply(exchange, 200, "text/plain", garminToken);
            });
            server.createContext("/activitylist-service/activities/search/activities", exchange -> {
                if (!CatalogFixture.allowed(exchange, garminToken)) {
                    return;
                }
                CatalogFixture.ROUTES.add("/activitylist-service/activities/search/activities");
                String query = exchange.getRequestURI().getRawQuery();
                if (query.contains("search=")) {
                    if (!query.contains("search=a%20%26%20b") || !query.contains("requiredField=distance&requiredField=duration")) {
                        reply(exchange, 400, "text/plain", "Incorrect search parameters");
                        return;
                    }
                    int id = query.contains("start=1") ? 46 : 45;
                    reply(exchange, 200, "application/json", """
                            [{"activityId":%d,"startTimeLocal":"2025-04-01T09:00:00","activityType":{"typeKey":"walking"}}]""".formatted(id));
                    return;
                }
                if (!"start=0&limit=1".equals(exchange.getRequestURI().getQuery())) {
                    reply(exchange, 200, "application/json", """
                            [{"activityId":42,"startTimeLocal":"2025-04-01T19:30:00","activityType":{"typeKey":"running"},
                            "distance":5000,"duration":1800,"maxAvgPower_60":300,"hrTimeInZone_1":55}]""");
                    return;
                }
                String activities = ACTIVITY_REQUESTS.incrementAndGet() == 1
                        ? "[{\"activityName\":\"private-activity-name\"}]" : "[]";
                reply(exchange, 200, "application/json", activities);
            });
            CatalogFixture.register(server, garminToken);
            server.start();
            return server;
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private static void reply(HttpExchange exchange, int status, String mediaType, String body) throws IOException {
        byte[] content = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", mediaType);
        exchange.sendResponseHeaders(status, content.length);
        try (var stream = exchange.getResponseBody()) {
            stream.write(content);
        }
    }

    private static RSAKey signingKey() {
        try {
            return new RSAKeyGenerator(2048).keyID("test").generate();
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private static String jwt(String subject, String audience, long seconds) throws Exception {
        var claims = new JWTClaimsSet.Builder().subject(subject).issuer(ISSUER).audience(audience)
                .expirationTime(Date.from(Instant.now().plusSeconds(seconds))).build();
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID())
                .type(new JOSEObjectType("at+jwt")).build(), claims);
        jwt.sign(new RSASSASigner(KEY));
        return jwt.serialize();
    }
}
