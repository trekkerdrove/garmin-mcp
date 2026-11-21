package io.github.trekkerdrove.garminmcp.application.port.out;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import io.github.trekkerdrove.garminmcp.application.dto.FitRequest;

public interface FitDecoder {
    Decoded read(byte[] archive, FitRequest request);

    record Decoded(List<Map<String, Object>> rows, int matched, int records, Instant startUtc, Instant endUtc,
                   LocalDate localStartDate) {}
}
