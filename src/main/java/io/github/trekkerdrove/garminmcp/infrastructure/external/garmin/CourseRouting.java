package io.github.trekkerdrove.garminmcp.infrastructure.external.garmin;

import io.github.trekkerdrove.garminmcp.application.dto.CourseData.*;
import io.github.trekkerdrove.garminmcp.application.dto.CourseGeometry;
import io.github.trekkerdrove.garminmcp.application.port.out.CourseRouter;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static io.github.trekkerdrove.garminmcp.application.dto.CourseData.MAX_HTTP_BYTES;

final class CourseRouting implements CourseRouter {
    private static final Set<String> AVOID = Set.of("ferries", "fords", "steps");
    private static final Map<String, String> DEFAULT_PROFILES = Map.of(
            "running", "foot-walking", "hiking", "foot-hiking", "trail_running", "foot-hiking",
            "road_biking", "cycling-road", "mountain_biking", "cycling-mountain", "gravel_cycling", "cycling-mountain");
    private final RestClient client;
    private final String directions;
    private final String pelias;
    private final String orsKey;
    private final String peliasKey;
    private final Map<String, String> profiles;
    private final CourseFormats formats;
    private final JsonMapper json = JsonMapper.builder().build();

    CourseRouting(String directions, String orsKey, String pelias, String peliasKey,
                  Map<String, String> configuredProfiles, CourseFormats formats) {
        this(directions, orsKey, pelias, peliasKey, configuredProfiles, formats, routingClient());
    }

    CourseRouting(String directions, String orsKey, String pelias, String peliasKey,
                  Map<String, String> configuredProfiles, CourseFormats formats, RestClient client) {
        this.directions = https(directions);
        this.pelias = pelias.isBlank() ? null : https(pelias);
        this.orsKey = orsKey;
        this.peliasKey = peliasKey;
        this.formats = formats;
        var effective = new LinkedHashMap<>(DEFAULT_PROFILES);
        configuredProfiles.forEach((sport, profile) -> {
            if (!DEFAULT_PROFILES.containsKey(sport) || !profile.matches("(foot|cycling)-[a-z-]+")) {
                throw new IllegalArgumentException("Invalid course routing profile");
            }
            effective.put(sport, profile);
        });
        this.profiles = Map.copyOf(effective);
        this.client = client;
    }

    private static RestClient routingClient() {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build());
        factory.setReadTimeout(Duration.ofSeconds(10));
        return RestClient.builder().requestFactory(factory).build();
    }

    private static String https(String value) {
        URI uri = URI.create(value);
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("Routing endpoints require an HTTPS URL");
        }
        return value.replaceFirst("/+$", "");
    }

    @Override
    public List<Route> route(PlanRequest input) {
        String requested = input.sport() == null ? "" : input.sport().strip().toLowerCase(Locale.ROOT);
        String sport = switch (requested) {
            case "walking" -> "hiking";
            case "cycling" -> "road_biking";
            default -> requested;
        };
        String profile = profiles.get(sport);
        boolean loop = "loop".equals(input.shape());
        if (profile == null || !loop && !"point_to_point".equals(input.shape())) throw new Problem("invalid_input");
        var via = input.via() == null ? List.of() : input.via();
        if (via.size() > 5 || loop && input.finish() != null || !loop && input.finish() == null
                || !loop && input.targetDistanceMeters() != null) throw new Problem("invalid_input");
        boolean autoLoop = loop && via.isEmpty();
        Double target = input.targetDistanceMeters();
        Double tolerance = input.distanceToleranceMeters();
        if (autoLoop && target == null || target != null && (!Double.isFinite(target) || target <= 0 || target > 1_000_000)
                || tolerance != null && (target == null || !Double.isFinite(tolerance) || tolerance < 0)) throw new Problem("invalid_input");
        int alternatives = input.alternatives() == null ? 1 : input.alternatives();
        if (alternatives < 1 || alternatives > 3) throw new Problem("invalid_input");
        var avoid = input.avoid() == null ? List.<String>of() : input.avoid();
        if (avoid.stream().anyMatch(value -> value == null || !AVOID.contains(value))) {
            throw new Problem("invalid_input", Map.of("supportedAvoid", AVOID));
        }
        Point start = location(input.start(), "start");
        var coordinates = new ArrayList<List<Double>>();
        coordinates.add(coordinates(start));
        for (int i = 0; i < via.size(); i++) coordinates.add(coordinates(location(via.get(i), "via[" + i + "]")));
        if (!autoLoop) coordinates.add(coordinates(loop ? start : location(input.finish(), "finish")));

        var warnings = new ArrayList<String>();
        if ("gravel_cycling".equals(sport) && "cycling-mountain".equals(profile)) {
            warnings.add("The ORS mountain-bike profile approximates gravel routing.");
        }
        if (!requested.equals(sport)) warnings.add("Sport " + requested + " maps to " + sport + ".");
        if (!autoLoop && coordinates.size() > 2 && alternatives > 1) {
            warnings.add("ORS supplies one route when intermediate points are specified.");
        }
        var results = new ArrayList<Route>();
        var geometries = new HashSet<List<List<Point>>>();
        for (int attempt = 0; attempt < (autoLoop ? alternatives : 1); attempt++) {
            var options = new LinkedHashMap<String, Object>();
            if (!avoid.isEmpty()) options.put("avoid_features", avoid);
            if (autoLoop) options.put("round_trip", Map.of("length", target, "points", 3, "seed", 100 + attempt));
            var body = new LinkedHashMap<String, Object>();
            body.put("coordinates", coordinates);
            body.put("elevation", true);
            body.put("instructions", false);
            if (!options.isEmpty()) body.put("options", options);
            if (!autoLoop && coordinates.size() == 2 && alternatives > 1) {
                body.put("alternative_routes", Map.of("target_count", alternatives));
            }
            try {
                var response = send(client.post().uri(URI.create(directions + "/v2/directions/" + profile + "/geojson"))
                        .header("Authorization", orsKey).contentType(MediaType.APPLICATION_JSON).body(body), "route_not_found");
                var features = response.path("features");
                if (!features.isArray()) throw new Problem("routing_unavailable");
                for (JsonNode feature : features) {
                    var route = project(feature, start, sport, profile, warnings);
                    var segments = route.trace().segments();
                    if (loop && CourseGeometry.distance(segments.getFirst().getFirst(), segments.getLast().getLast()) > 1) continue;
                    if (geometries.add(route.trace().segments())) results.add(route);
                    if (results.size() == alternatives) break;
                }
            } catch (Problem failure) {
                if (!"route_not_found".equals(failure.status()) || !autoLoop) throw failure;
            }
        }
        if (target != null) results.sort(Comparator.comparingDouble(route -> Math.abs(route.trace().metrics().distanceMeters() - target)));
        return results;
    }

    private Route project(JsonNode feature, Point start, String sport, String profile, List<String> warnings) {
        if (!feature.path("geometry").isObject()) throw new Problem("routing_unavailable");
        Trace parsed;
        try {
            parsed = formats.readGeoJson(json.convertValue(feature.path("geometry"), new TypeReference<Map<String, Object>>() {}));
        } catch (IllegalArgumentException | Problem invalid) {
            if (invalid instanceof Problem problem && "course_source_too_large".equals(problem.status())) {
                throw new Problem("course_response_too_large");
            }
            throw new Problem("routing_unavailable");
        }
        var properties = feature.path("properties");
        var values = properties.path("summary");
        Double distance = measure(values.path("distance"));
        if (distance == null || distance <= 0) throw new Problem("routing_unavailable");
        var ascent = measure(properties.path("ascent"));
        var descent = measure(properties.path("descent"));
        var routeWarnings = new ArrayList<>(warnings);
        for (var warning : properties.path("warnings")) {
            if (routeWarnings.size() >= 10) break;
            String message = warning.path("message").asString("").strip();
            if (!message.isEmpty()) routeWarnings.add(message.substring(0, Math.min(256, message.length())));
        }
        var metrics = new Metrics(distance, ascent == null ? parsed.metrics().ascentMeters() : ascent,
                descent == null ? parsed.metrics().descentMeters() : descent, parsed.metrics().elevationCoverage(),
                ascent == null || descent == null ? "openrouteservice (elevation derived from trace when absent)" : "openrouteservice");
        var trace = new Trace(parsed.segments(), List.of(), null, sport, null,
                "openrouteservice (" + profile + ", © OpenStreetMap contributors)", metrics, routeWarnings, List.of());
        Point first = parsed.segments().getFirst().getFirst();
        double snap = CourseGeometry.distance(start, first);
        return new Route(trace, snap > 0.1 ? new Point(first.latitude(), first.longitude(), null, null) : null,
                snap > 0.1 ? snap : null);
    }

    private static Double measure(JsonNode value) {
        return value.isNumber() && Double.isFinite(value.asDouble()) && value.asDouble() >= 0 ? value.asDouble() : null;
    }

    private Point location(Object input, String field) {
        if (input instanceof Map<?, ?> point && point.size() == 2 && point.get("latitude") instanceof Number latitude
                && point.get("longitude") instanceof Number longitude) {
            return new Point(latitude.doubleValue(), longitude.doubleValue(), null, null);
        }
        if (!(input instanceof String place) || place.isBlank() || place.length() > 300) throw new Problem("invalid_input");
        if (pelias == null) throw new Problem("routing_unavailable", "A geocoder is required for textual locations.");
        var uri = UriComponentsBuilder.fromUriString(pelias).path("/search").queryParam("text", "{place}").queryParam("size", 5);
        var variables = new LinkedHashMap<String, Object>();
        variables.put("place", place);
        if (!peliasKey.isBlank()) {
            uri.queryParam("api_key", "{key}");
            variables.put("key", peliasKey);
        }
        var reply = send(client.get().uri(uri.encode().buildAndExpand(variables).toUri()), "route_not_found");
        var candidates = new ArrayList<Map<String, Object>>();
        Point first = null;
        for (var hit : reply.path("features")) {
            var coordinates = hit.path("geometry").path("coordinates");
            String label = hit.path("properties").path("label").asString("");
            if (coordinates.size() < 2 || !coordinates.get(0).isNumber() || !coordinates.get(1).isNumber() || label.isBlank()) continue;
            Point point = new Point(coordinates.get(1).asDouble(), coordinates.get(0).asDouble(), null, null);
            if (first == null) first = point;
            candidates.add(Map.of("label", label, "latitude", point.latitude(), "longitude", point.longitude()));
            if (candidates.size() == 5) break;
        }
        if (first == null) throw new Problem("route_not_found", Map.of("field", field));
        if (candidates.size() > 1) throw new Problem("location_selection_required", Map.of("field", field, "choices", candidates));
        return first;
    }

    private static List<Double> coordinates(Point point) {
        return List.of(point.longitude(), point.latitude());
    }

    private JsonNode send(RestClient.RequestHeadersSpec<?> request, String absent) {
        try {
            return request.exchange((sent, response) -> {
                byte[] bytes;
                try (var stream = response.getBody()) {
                    bytes = stream.readNBytes(MAX_HTTP_BYTES + 1);
                }
                if (bytes.length > MAX_HTTP_BYTES) throw new Problem("course_response_too_large");
                if (response.getStatusCode().value() == 404) throw new Problem(absent);
                var body = json.readTree(bytes);
                if (body == null || !body.isObject()) throw new Problem("routing_unavailable");
                if (!response.getStatusCode().is2xxSuccessful()) {
                    int code = body.path("error").path("code").asInt(0);
                    if (code == 2009 || code == 2010) throw new Problem(absent);
                    throw new Problem("routing_unavailable");
                }
                return body;
            });
        } catch (RestClientException | JacksonException failure) {
            throw new Problem("routing_unavailable");
        }
    }
}
