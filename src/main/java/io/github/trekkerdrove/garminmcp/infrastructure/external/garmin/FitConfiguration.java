package io.github.trekkerdrove.garminmcp.infrastructure.external.garmin;

import io.github.trekkerdrove.garminmcp.application.port.out.FitDecoder;
import io.github.trekkerdrove.garminmcp.application.port.out.GarminArchive;
import io.github.trekkerdrove.garminmcp.application.port.out.GarminWeight;
import io.github.trekkerdrove.garminmcp.application.service.ReadFitActivity;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class FitConfiguration {
    @Bean
    ReadFitActivity readFitActivity(GarminArchive garmin, FitDecoder decoder, GarminWeight weights) {
        return new ReadFitActivity(garmin, decoder, weights);
    }
}
