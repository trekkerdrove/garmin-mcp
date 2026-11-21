package io.github.trekkerdrove.garminmcp.infrastructure.external.garmin;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import io.github.trekkerdrove.garminmcp.application.dto.GarminFailure;
import io.github.trekkerdrove.garminmcp.application.port.out.GarminArchive;

import com.nimbusds.jwt.SignedJWT;
import io.github.trekkerdrove.garminmcp.application.port.out.GarminActivityProbe;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriBuilder;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.NullNode;

@Component
public class GarminHttpClient implements GarminActivityProbe, GarminArchive {

    private static final String TOKEN_PATH = "/personalapitoken-service/personal-api/personal-api-key/token";
    private static final int JSON_BYTES = 4 * 1024 * 1024;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final ScheduledExecutorService DEADLINES =
            Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("garmin-read-deadline").factory());

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
        var page = new LinkedHashMap<String, Integer>();
        page.put("start", 0);
        page.put("limit", 1);
        try {
            JsonNode activities = get(GarminRoute.A1, page);
            if (!activities.isArray()) {
                return Outcome.UNAVAILABLE;
            }
            return activities.isEmpty() ? Outcome.NO_ACTIVITY : Outcome.ACTIVITY_FOUND;
        } catch (GarminFailure error) {
            return switch (error.status()) {
                case "missing_garmin_key" -> Outcome.MISSING_KEY;
                case "invalid_garmin_key" -> Outcome.INVALID_KEY;
                case "garmin_access_denied" -> Outcome.ACCESS_DENIED;
                case "garmin_rate_limited" -> Outcome.RATE_LIMITED;
                default -> Outcome.UNAVAILABLE;
            };
        }
    }

    public JsonNode get(GarminRoute route, Map<String, ?> query, Object... pathValues) {
        if (route.shape == GarminRoute.Shape.BINARY) {
            throw new IllegalArgumentException("Expected a JSON operation");
        }
        byte[] body = read(route, query, pathValues, JSON_BYTES);
        if (body == null) {
            if (route.allowsNoContent()) {
                return NullNode.getInstance();
            }
            throw new GarminFailure("garmin_unavailable");
        }
        try {
            JsonNode value = JSON.readTree(body);
            boolean valid = value != null && switch (route.shape) {
                case ARRAY -> value.isArray();
                case OBJECT -> value.isObject();
                case OBJECT_OR_ARRAY -> value.isObject() || value.isArray();
                case BINARY -> false;
            };
            if (!valid) {
                throw new GarminFailure("garmin_unavailable");
            }
            return value;
        } catch (JacksonException malformed) {
            throw new GarminFailure("garmin_unavailable");
        }
    }

    @Override
    public byte[] activityArchive(long activityId, int maxBytes) {
        if (activityId <= 0 || maxBytes < 1 || maxBytes > 16 * 1024 * 1024) {
            throw new IllegalArgumentException("Invalid archive request");
        }
        try {
            byte[] archive = read(GarminRoute.A3, Map.of(), new Object[]{activityId}, maxBytes);
            if (archive == null) throw new GarminFailure("garmin_unavailable");
            return archive;
        } catch (GarminFailure error) {
            if ("response_too_large".equals(error.status())) {
                throw new GarminFailure("fit_limit_exceeded");
            }
            throw error;
        }
    }

    private byte[] read(GarminRoute route, Map<String, ?> query, Object[] pathValues, int limit) {
        if (apiKey.isBlank()) {
            throw new GarminFailure("missing_garmin_key");
        }
        if (!apiKey.chars().allMatch(character -> character > 0x20 && character < 0x7f)) {
            throw new GarminFailure("invalid_garmin_key");
        }
        Token token = null;
        try {
            token = accessToken();
            return client.get().uri(uri -> buildUri(uri, route, query, pathValues))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token.value()).accept(MediaType.ALL)
                    .exchange((request, response) -> boundedBody(response, limit));
        } catch (GarminFailure failure) {
            if (token == null && failure.status().equals("not_found")) {
                throw new GarminFailure("garmin_unavailable");
            }
            if (failure.status().equals("garmin_unauthorized")) {
                invalidate(token);
                throw new GarminFailure("garmin_access_denied");
            }
            throw failure;
        } catch (RestClientException | ParseException | IllegalArgumentException failure) {
            throw new GarminFailure("garmin_unavailable");
        }
    }

    private static URI buildUri(UriBuilder uri, GarminRoute route, Map<String, ?> query, Object[] pathValues) {
        uri.path(route.path);
        var values = new ArrayList<>(java.util.Arrays.asList(pathValues));
        query.forEach((key, raw) -> {
            var items = raw instanceof Collection<?> collection ? collection : java.util.List.of(raw);
            for (Object item : items) {
                uri.queryParam(key, "{q" + values.size() + "}");
                values.add(item);
            }
        });
        return uri.build(values.toArray());
    }

    private static byte[] boundedBody(ClientHttpResponse response, int limit) throws IOException {
        // Spring drains the response on close; close the stream first to abort oversized or rejected bodies.
        try (var stream = response.getBody()) {
            int status = response.getStatusCode().value();
            if (status == 204) {
                return null;
            }
            if (status != 200) {
                throw new GarminFailure(switch (status) {
                    case 401 -> "garmin_unauthorized";
                    case 403 -> "garmin_access_denied";
                    case 404 -> "not_found";
                    case 429 -> "garmin_rate_limited";
                    default -> "garmin_unavailable";
                });
            }
            if (response.getHeaders().getContentLength() > limit) {
                throw new GarminFailure("response_too_large");
            }
            return readLimitedStream(stream, limit);
        }
    }

    private static byte[] readLimitedStream(InputStream stream, int limit) throws IOException {
        long started = System.nanoTime();
        var deadline = DEADLINES.schedule(() -> {
            try {
                stream.close();
            } catch (IOException ignored) {
                // The request may already have closed the stream.
            }
        }, 10, TimeUnit.SECONDS);
        try {
            byte[] bytes = stream.readNBytes(limit + 1);
            if (bytes.length > limit) {
                throw new GarminFailure("response_too_large");
            }
            if (bytes.length == 0 || System.nanoTime() - started >= TimeUnit.SECONDS.toNanos(10)) {
                throw new GarminFailure("garmin_unavailable");
            }
            return bytes;
        } finally {
            deadline.cancel(false);
        }
    }

    private synchronized Token accessToken() throws ParseException {
        if (cachedToken != null && Instant.now().plusSeconds(30).isBefore(cachedToken.expiresAt())) {
            return cachedToken;
        }

        Instant requestedAt = Instant.now();
        byte[] response = client.post().uri(TOKEN_PATH)
                .header(HttpHeaders.AUTHORIZATION, "ApiKey " + apiKey)
                .accept(MediaType.ALL)
                .exchange((request, reply) -> boundedBody(reply, 16384));
        if (response == null || response.length == 0) {
            throw new ParseException("Garmin returned an empty token", 0);
        }
        String value = new String(response, StandardCharsets.UTF_8).strip();
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
