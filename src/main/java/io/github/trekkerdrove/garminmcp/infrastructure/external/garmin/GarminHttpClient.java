package io.github.trekkerdrove.garminmcp.infrastructure.external.garmin;

import java.net.http.HttpClient;
import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;

import com.nimbusds.jwt.SignedJWT;
import io.github.trekkerdrove.garminmcp.application.port.out.GarminActivityProbe;
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
public class GarminHttpClient implements GarminActivityProbe {

    private static final String TOKEN_PATH = "/personalapitoken-service/personal-api/personal-api-key/token";
    private static final String ACTIVITIES_PATH = "/activitylist-service/activities/search/activities";

    private final RestClient client;
    private final String apiKey;
    private Token cachedToken;

    @Autowired
    GarminHttpClient(@Value("${garmin.api-key}") String apiKey) {
        this(apiKey, "https://personal.connectapi.garmin.com");
    }

    public GarminHttpClient(String apiKey, String baseUrl) {
        this.apiKey = apiKey;
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    @Override
    public Outcome probeActivity() {
        if (apiKey.isBlank()) {
            return Outcome.MISSING_KEY;
        }
        if (!apiKey.chars().allMatch(character -> character > 0x20 && character < 0x7f)) {
            return Outcome.INVALID_KEY;
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
            if (activities == null) {
                return Outcome.UNAVAILABLE;
            }
            return activities.isEmpty() ? Outcome.NO_ACTIVITY : Outcome.ACTIVITY_FOUND;
        } catch (RestClientResponseException error) {
            return switch (error.getStatusCode().value()) {
                case 401 -> {
                    invalidate(token);
                    yield Outcome.ACCESS_DENIED;
                }
                case 403 -> Outcome.ACCESS_DENIED;
                case 429 -> Outcome.RATE_LIMITED;
                default -> Outcome.UNAVAILABLE;
            };
        } catch (RestClientException | ParseException | IllegalArgumentException error) {
            return Outcome.UNAVAILABLE;
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
}
