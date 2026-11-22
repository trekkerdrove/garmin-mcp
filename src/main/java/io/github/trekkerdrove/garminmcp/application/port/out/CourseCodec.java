package io.github.trekkerdrove.garminmcp.application.port.out;

import io.github.trekkerdrove.garminmcp.application.dto.CourseData.*;
import java.util.Map;

public interface CourseCodec {
    Trace readFile(Format format, byte[] bytes, Integer selectionIndex);
    Trace readNativeExport(Format format, byte[] bytes);
    Trace readGeoJson(Map<String, Object> geometry);
    Trace readActivity(byte[] archive);
    Export reconstruct(Snapshot course, Format format);
}
