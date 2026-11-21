package io.github.trekkerdrove.garminmcp.infrastructure.external.garmin;

import io.github.trekkerdrove.garminmcp.application.port.out.WellnessReader;
import io.github.trekkerdrove.garminmcp.application.service.WellnessService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class WellnessConfiguration {
    @Bean
    WellnessService wellnessService(WellnessReader reader) {
        return new WellnessService(reader);
    }
}
