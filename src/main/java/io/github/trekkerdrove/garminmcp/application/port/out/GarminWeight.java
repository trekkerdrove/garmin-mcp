package io.github.trekkerdrove.garminmcp.application.port.out;

import java.time.LocalDate;
import java.util.OptionalDouble;

public interface GarminWeight {
    OptionalDouble kilogramsOnOrBefore(LocalDate date);
}
