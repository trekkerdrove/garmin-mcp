package io.github.trekkerdrove.garminmcp;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
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
        oauth.verify();
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
                if (!"GET".equals(exchange.getRequestMethod())
                        || !("Bearer " + garminToken).equals(exchange.getRequestHeaders().getFirst("Authorization"))
                        || !"start=0&limit=1".equals(exchange.getRequestURI().getQuery())) {
                    reply(exchange, 400, "text/plain", "Unexpected request");
                    return;
                }
                String activities = ACTIVITY_REQUESTS.incrementAndGet() == 1
                        ? "[{\"activityName\":\"private-activity-name\"}]" : "[]";
                reply(exchange, 200, "application/json", activities);
            });
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
