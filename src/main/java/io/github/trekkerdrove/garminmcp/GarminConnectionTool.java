package io.github.trekkerdrove.garminmcp;

import java.net.http.HttpClient;
import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;

import com.nimbusds.jwt.SignedJWT;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.node.ArrayNode;

@Component
class GarminConnectionTool {

    private static final String TOKEN_PATH = "/personalapitoken-service/personal-api/personal-api-key/token";
    private static final String ACTIVITIES_PATH = "/activitylist-service/activities/search/activities";

    private final RestClient client;
    private final String apiKey;
    private Token cachedToken;

    @Autowired
    GarminConnectionTool(@Value("${garmin.api-key}") String apiKey) {
        this(apiKey, "https://personal.connectapi.garmin.com");
    }

    GarminConnectionTool(String apiKey, String baseUrl) {
        this.apiKey = apiKey;
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    @McpTool(name = "garmin_check_connection",
            description = "Checks the owner's Garmin connection using at most one activity. Returns a status and "
                    + "whether Garmin returned an activity. Does not return activity details.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
    public ConnectionResult checkConnection() {
        if (apiKey.isBlank()) {
            return new ConnectionResult("missing_garmin_key", null);
        }
        if (!apiKey.chars().allMatch(character -> character > 0x20 && character < 0x7f)) {
            return new ConnectionResult("invalid_garmin_key", null);
        }

        Token token = null;
        try {
            token = accessToken();
            ArrayNode activities = client.get()
                    .uri(uri -> uri.path(ACTIVITIES_PATH).queryParam("start", 0).queryParam("limit", 1).build())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token.value())
                    .accept(MediaType.ALL)
                    .retrieve()
                    .body(ArrayNode.class);
            return activities == null
                    ? new ConnectionResult("garmin_unavailable", null)
                    : new ConnectionResult("connected", !activities.isEmpty());
        } catch (RestClientResponseException error) {
            return switch (error.getStatusCode().value()) {
                case 401, 403 -> {
                    if (error.getStatusCode().value() == 401) {
                        invalidate(token);
                    }
                    yield new ConnectionResult("garmin_access_denied", null);
                }
                case 429 -> new ConnectionResult("garmin_rate_limited", null);
                default -> new ConnectionResult("garmin_unavailable", null);
            };
        } catch (RestClientException | ParseException | IllegalArgumentException error) {
            return new ConnectionResult("garmin_unavailable", null);
        }
    }

    private synchronized Token accessToken() throws ParseException {
        if (cachedToken != null && Instant.now().plusSeconds(30).isBefore(cachedToken.expiresAt())) {
            return cachedToken;
        }

        Instant requestedAt = Instant.now();
        String response = client.post().uri(TOKEN_PATH)
                .header(HttpHeaders.AUTHORIZATION, "ApiKey " + apiKey)
                .accept(MediaType.ALL)
                .retrieve().body(String.class);
        if (response == null || response.isBlank()) {
            throw new ParseException("Garmin returned an empty token", 0);
        }
        String value = response.strip();
        var expires = SignedJWT.parse(value).getJWTClaimsSet().getExpirationTime();
        if (expires == null || !Instant.now().isBefore(expires.toInstant())) {
            throw new ParseException("Garmin returned a token without a usable expiry", 0);
        }
        Instant maximumExpiry = requestedAt.plus(Duration.ofHours(24));
        Instant expiry = expires.toInstant();
        cachedToken = new Token(value, expiry.isBefore(maximumExpiry) ? expiry : maximumExpiry);
        return cachedToken;
    }

    private synchronized void invalidate(Token token) {
        if (cachedToken == token) {
            cachedToken = null;
        }
    }

    private record Token(String value, Instant expiresAt) {}

    record ConnectionResult(String status, Boolean hasActivity) {}
}
