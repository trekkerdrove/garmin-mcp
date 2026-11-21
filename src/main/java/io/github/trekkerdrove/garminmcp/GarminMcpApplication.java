package io.github.trekkerdrove.garminmcp;

import java.time.Clock;
import java.time.ZoneId;

import io.github.trekkerdrove.garminmcp.application.port.in.CheckGarminConnection;
import io.github.trekkerdrove.garminmcp.application.port.out.GarminPersonalData;
import io.github.trekkerdrove.garminmcp.application.service.PersonalTrackingService;
import io.github.trekkerdrove.garminmcp.application.port.out.GarminActivityProbe;
import io.github.trekkerdrove.garminmcp.application.service.CheckGarminConnectionService;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class GarminMcpApplication {

    public static void main(String[] args) {
        SpringApplication.run(GarminMcpApplication.class, args);
    }

    @Bean
    CheckGarminConnection checkGarminConnection(GarminActivityProbe garmin) {
        return new CheckGarminConnectionService(garmin);
    }

    @Bean
    PersonalTrackingService personalTrackingService(GarminPersonalData garmin) {
        return new PersonalTrackingService(garmin, Clock.system(ZoneId.of("Europe/Paris")));
    }
}
