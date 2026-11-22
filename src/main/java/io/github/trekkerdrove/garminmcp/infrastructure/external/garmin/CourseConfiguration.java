package io.github.trekkerdrove.garminmcp.infrastructure.external.garmin;

import io.github.trekkerdrove.garminmcp.application.dto.CourseData.Format;
import io.github.trekkerdrove.garminmcp.application.port.out.ActivityReader;
import io.github.trekkerdrove.garminmcp.application.port.out.CourseCodec;
import io.github.trekkerdrove.garminmcp.application.port.out.CourseLibrary;
import io.github.trekkerdrove.garminmcp.application.port.out.CourseRouter;
import io.github.trekkerdrove.garminmcp.application.port.out.GarminArchive;
import io.github.trekkerdrove.garminmcp.application.service.CourseService;
import java.time.Clock;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class CourseConfiguration {
    @Bean
    CourseService courseService(ObjectProvider<CourseLibrary> library, ActivityReader activities, GarminArchive archive, CourseCodec codec) {
        return new CourseService(library.getIfAvailable(), activities, archive, codec, Clock.systemUTC());
    }

    @Bean
    @ConditionalOnProperty(name = "garmin.courses.enabled", havingValue = "true")
    CourseLibrary courseLibrary(GarminConnectWorker worker,
            @Value("${garmin.courses.writes-enabled:false}") boolean writes,
            @Value("${garmin.courses.verified-edit-fields:name}") String edits,
            @Value("${garmin.courses.verified-point-types:}") String pointTypes,
            @Value("${garmin.courses.verified-native-exports:gpx}") String nativeExports) {
        var fields = csv(edits);
        if (!Set.of("name", "description", "sport", "source", "coursePoints").containsAll(fields)) {
            throw new IllegalArgumentException("Invalid verified course edit field");
        }
        Set<Format> formats = csv(nativeExports).stream().map(Format::valueOf).collect(Collectors.toUnmodifiableSet());
        if (!Set.of(Format.gpx, Format.fit).containsAll(formats)) throw new IllegalArgumentException("Invalid native course export");
        var capabilities = new CourseLibrary.Capabilities(Set.of("running", "trail_running", "hiking", "road_biking",
                "mountain_biking", "gravel_cycling"), writes ? fields : Set.of(), csv(pointTypes), writes, writes, formats);
        return new PythonCourseLibrary(worker, capabilities);
    }

    @Bean
    @ConditionalOnExpression("!'${garmin.courses.routing.url:}'.isBlank()")
    CourseRouter courseRouting(CourseFormats formats,
            @Value("${garmin.courses.routing.url}") String directions,
            @Value("${garmin.courses.routing.api-key:}") String orsKey,
            @Value("${garmin.courses.routing.pelias-url:}") String pelias,
            @Value("${garmin.courses.routing.pelias-key:}") String peliasKey,
            @Value("${garmin.courses.routing.profiles:}") String profiles) {
        Map<String, String> mappings = new HashMap<>();
        for (String mapping : csv(profiles)) {
            String[] pair = mapping.split("=", -1);
            if (pair.length != 2 || mappings.putIfAbsent(pair[0].strip(), pair[1].strip()) != null) {
                throw new IllegalArgumentException("Invalid course routing profile mapping");
            }
        }
        return new CourseRouting(directions, orsKey, pelias, peliasKey, mappings, formats);
    }

    private static Set<String> csv(String value) {
        return Arrays.stream(value.split(",")).map(String::strip).filter(part -> !part.isEmpty()).collect(Collectors.toUnmodifiableSet());
    }
}
