package io.github.trekkerdrove.garminmcp.infrastructure.external.garmin;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import io.github.trekkerdrove.garminmcp.application.dto.GarminFailure;
import io.github.trekkerdrove.garminmcp.application.port.out.StrengthExerciseCatalog;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@Component
class GarminStrengthExercises implements StrengthExerciseCatalog {
    private static final String CATALOG_URL = "https://connect.garmin.com/web-data/exercises/Exercises.json";
    private static final Duration CACHE_LIFETIME = Duration.ofDays(1);
    private static final int CATALOG_LIMIT = 1024 * 1024;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final RestClient http;
    private List<Exercise> catalog;
    private Instant expiresAt = Instant.MIN;

    GarminStrengthExercises() {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3_000);
        factory.setReadTimeout(5_000);
        http = RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public synchronized List<Exercise> exercises() {
        if (Instant.now().isAfter(expiresAt)) {
            try {
                catalog = http.get().uri(CATALOG_URL).exchange((request, response) -> {
                    if (!response.getStatusCode().is2xxSuccessful()) throw unavailable();
                    byte[] body = response.getBody().readNBytes(CATALOG_LIMIT + 1);
                    if (body.length > CATALOG_LIMIT) throw unavailable();
                    return parse(body);
                });
                expiresAt = Instant.now().plus(CACHE_LIFETIME);
            } catch (RestClientException | JacksonException error) {
                throw unavailable();
            }
        }
        return catalog;
    }

    static List<Exercise> parse(byte[] body) {
        var categories = JSON.readTree(body).path("categories");
        var exercises = new ArrayList<Exercise>();
        if (!categories.isObject()) throw unavailable();
        for (var category : categories.properties()) {
            var entries = category.getValue().path("exercises");
            if (!entries.isObject()) throw unavailable();
            for (String key : entries.propertyNames()) {
                exercises.add(new Exercise(category.getKey(), key));
            }
        }
        if (exercises.isEmpty()) throw unavailable();
        exercises.sort(Comparator.comparing(Exercise::exerciseName).thenComparing(Exercise::category));
        return List.copyOf(exercises);
    }

    private static GarminFailure unavailable() {
        return new GarminFailure("garmin_unavailable");
    }
}
