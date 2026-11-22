package io.github.trekkerdrove.garminmcp.infrastructure.external.garmin;

import com.garmin.fit.CourseMesg;
import com.garmin.fit.CoursePointMesg;
import com.garmin.fit.Decode;
import com.garmin.fit.File;
import com.garmin.fit.FileIdMesg;
import com.garmin.fit.MesgNum;
import com.garmin.fit.RecordMesg;
import com.garmin.fit.SessionMesg;
import com.garmin.fit.Sport;
import com.garmin.fit.SubSport;
import io.github.trekkerdrove.garminmcp.application.dto.CourseData.*;
import io.github.trekkerdrove.garminmcp.application.dto.CourseGeometry;
import io.github.trekkerdrove.garminmcp.application.dto.GarminFailure;
import io.github.trekkerdrove.garminmcp.application.port.out.CourseCodec;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import org.springframework.stereotype.Component;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import static io.github.trekkerdrove.garminmcp.application.dto.CourseData.*;

@Component
final class CourseFormats implements CourseCodec {
    private static final String GPX = "http://www.topografix.com/GPX/1/1";
    private static final String TCX = "http://www.garmin.com/xmlschemas/TrainingCenterDatabase/v2";
    private static final double DEGREES = 180.0 / (1L << 31);
    private static final Set<String> TCX_TYPES = Set.of("Generic", "Summit", "Valley", "Water", "Food",
            "Danger", "Left", "Right", "Straight", "First Aid", "4th Category", "3rd Category",
            "2nd Category", "1st Category", "Hors Category", "Sprint");
    private final JsonMapper json = JsonMapper.builder().build();

    @Override
    public Trace readFile(Format format, byte[] bytes, Integer selectionIndex) {
        if (bytes.length > MAX_SOURCE_BYTES) throw new Problem("course_source_too_large");
        return read(format, bytes, selectionIndex);
    }

    @Override
    public Trace readNativeExport(Format format, byte[] bytes) {
        if (bytes.length > MAX_EXPORT_BYTES) throw new Problem("course_response_too_large");
        return read(format, bytes, null);
    }

    private Trace read(Format format, byte[] bytes, Integer selectionIndex) {
        if (bytes.length == 0 || selectionIndex != null && selectionIndex < 0) throw new Problem("invalid_input");
        try {
            return switch (format) {
                case gpx, tcx -> readXml(format, bytes, selectionIndex);
                case fit -> {
                    checkSingleSelection(selectionIndex);
                    yield readFit(bytes, "fit", false);
                }
                case geojson -> {
                    checkSingleSelection(selectionIndex);
                    yield readGeoJson(json.readValue(bytes, new TypeReference<Map<String, Object>>() {}));
                }
            };
        } catch (IOException | SAXException | ParserConfigurationException | JacksonException invalid) {
            throw new Problem("invalid_input");
        }
    }

    private static void checkSingleSelection(Integer selectionIndex) {
        if (selectionIndex != null && selectionIndex != 0) throw new Problem("invalid_input");
    }

    @Override
    public Trace readGeoJson(Map<String, Object> input) {
        if (input == null) throw new Problem("invalid_input");
        Map<?, ?> geometry = input;
        Map<?, ?> properties = Map.of();
        if ("Feature".equals(input.get("type"))) {
            if (!(input.get("geometry") instanceof Map<?, ?> feature)) throw new Problem("invalid_input");
            geometry = feature;
            if (input.get("properties") instanceof Map<?, ?> values) properties = values;
        }
        var segments = new ArrayList<List<Point>>();
        List<?> ordinates = array(geometry.get("coordinates"));
        List<?> lines = switch (String.valueOf(geometry.get("type"))) {
            case "LineString" -> List.of(ordinates);
            case "MultiLineString" -> ordinates;
            default -> throw new Problem("invalid_input");
        };
        var times = properties.get("pointTimestamps") == null ? null : array(properties.get("pointTimestamps"));
        if (times != null && times.size() != lines.size()) throw new Problem("invalid_input");
        int count = 0;
        for (int i = 0; i < lines.size(); i++) {
            var points = new ArrayList<Point>();
            var line = array(lines.get(i));
            if (line.size() < 2) throw new Problem("course_no_geometry");
            var lineTimes = times == null ? null : array(times.get(i));
            if (lineTimes != null && lineTimes.size() != line.size()) throw new Problem("invalid_input");
            for (int j = 0; j < line.size(); j++) {
                if (++count > MAX_POINTS) throw new Problem("course_source_too_large");
                List<?> position = array(line.get(j));
                if (position.size() < 2 || position.size() > 3) throw new Problem("invalid_input");
                points.add(new Point(number(position.get(1)), number(position.getFirst()),
                        position.size() == 3 ? number(position.get(2)) : null,
                        lineTimes == null ? null : text(lineTimes.get(j))));
            }
            segments.add(points);
        }
        var navigation = new ArrayList<NavigationPoint>();
        if (properties.get("coursePoints") != null) {
            for (Object value : array(properties.get("coursePoints"))) {
                if (!(value instanceof Map<?, ?> point)) throw new Problem("invalid_input");
                addNavigation(navigation, new NavigationPoint(text(point.get("name")), text(point.get("type")),
                        nullableNumber(point.get("latitude")), nullableNumber(point.get("longitude")),
                        nullableNumber(point.get("distanceMeters")), text(point.get("timestamp"))));
            }
        }
        var known = Set.of("name", "sport", "description", "coursePoints", "pointTimestamps");
        List<String> losses = properties.keySet().stream().allMatch(known::contains)
                ? List.of() : List.of("GeoJSON properties without a Garmin equivalent are omitted.");
        return trace(segments, navigation, text(properties.get("name")), text(properties.get("sport")),
                text(properties.get("description")), "geojson", losses);
    }

    private static List<?> array(Object value) {
        if (value instanceof List<?> list) return list;
        throw new Problem("invalid_input");
    }

    private static String text(Object value) {
        if (value == null || value instanceof String) return (String) value;
        throw new Problem("invalid_input");
    }

    private static double number(Object value) {
        if (value instanceof Number n && Double.isFinite(n.doubleValue())) return n.doubleValue();
        throw new Problem("invalid_input");
    }

    private static Double nullableNumber(Object value) {
        return value == null ? null : number(value);
    }

    private static Trace readXml(Format format, byte[] bytes, Integer selectionIndex)
            throws ParserConfigurationException, IOException, SAXException {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setAttribute("jdk.xml.maxElementDepth", "64");
        factory.setXIncludeAware(false);
        var builder = factory.newDocumentBuilder();
        builder.setErrorHandler(new DefaultHandler());
        Element root = builder.parse(new ByteArrayInputStream(bytes)).getDocumentElement();
        boolean gpx = format == Format.gpx;
        if (gpx ? !"gpx".equals(root.getLocalName()) || !Set.of("1.0", "1.1").contains(root.getAttribute("version"))
                : !"TrainingCenterDatabase".equals(root.getLocalName())) throw new Problem("invalid_input");
        var choices = new ArrayList<Element>();
        if (gpx) {
            choices.addAll(children(root, "trk", "rte"));
        } else {
            for (var section : children(root, "Courses", "Activities")) {
                for (var entry : children(section, "Course", "Activity", "MultiSportSession")) {
                    if ("MultiSportSession".equals(entry.getLocalName())) {
                        for (var leg : children(entry, "FirstSport", "NextSport")) {
                            choices.addAll(children(leg, "Activity"));
                        }
                    } else {
                        choices.add(entry);
                    }
                }
            }
        }
        if (choices.isEmpty()) throw new Problem("course_no_geometry");
        if (choices.size() > 1 && selectionIndex == null) {
            var options = new ArrayList<Map<String, Object>>();
            for (int i = 0; i < Math.min(choices.size(), 500); i++) {
                var choice = choices.get(i);
                var option = new LinkedHashMap<String, Object>();
                option.put("selectionIndex", i);
                option.put("kind", choice.getLocalName());
                option.put("name", value(choice, gpx ? "name" : "Name"));
                options.add(option);
            }
            throw new Problem("invalid_input", Map.of("choices", options, "totalChoices", choices.size()));
        }
        int index = selectionIndex == null ? 0 : selectionIndex;
        if (index >= choices.size()) throw new Problem("invalid_input");
        var chosen = choices.get(index);
        var segments = new ArrayList<List<Point>>();
        var navigation = new ArrayList<NavigationPoint>();
        var losses = new ArrayList<String>();
        int count = 0;
        if (gpx) {
            boolean route = "rte".equals(chosen.getLocalName());
            for (Element segment : route ? List.of(chosen) : children(chosen, "trkseg")) {
                var points = new ArrayList<Point>();
                for (Element entry : children(segment, route ? "rtept" : "trkpt")) {
                    if (++count > MAX_POINTS) throw new Problem("course_source_too_large");
                    points.add(new Point(attribute(entry, "lat"), attribute(entry, "lon"),
                            numeric(entry, "ele"), value(entry, "time")));
                    if (route && (value(entry, "type") != null || value(entry, "sym") != null)) {
                        addNavigation(navigation, gpxNavigation(entry));
                    } else if (value(entry, "name") != null) {
                        addOnce(losses, "Named track or route positions without an explicit navigation role are omitted.");
                    }
                    if (!children(entry, "desc", "cmt", "link", "url").isEmpty()) {
                        addOnce(losses, "Additional GPX position metadata is omitted.");
                    }
                }
                segments.add(points);
            }
            if (choices.size() == 1) {
                for (var waypoint : children(root, "wpt")) {
                    addNavigation(navigation, gpxNavigation(waypoint));
                    if (!children(waypoint, "ele", "desc", "cmt", "link", "url", "extensions").isEmpty()) {
                        addOnce(losses, "GPX waypoint metadata or extensions have no verified Garmin projection.");
                    }
                }
            } else if (!children(root, "wpt").isEmpty()) {
                losses.add("Unassigned waypoints in a GPX with multiple traces are omitted.");
            }
        } else {
            var tracks = new ArrayList<>(children(chosen, "Track"));
            for (var lap : children(chosen, "Lap")) tracks.addAll(children(lap, "Track"));
            for (var track : tracks) {
                var points = new ArrayList<Point>();
                for (var entry : children(track, "Trackpoint")) {
                    var position = element(entry, "Position");
                    if (position == null) {
                        if (!points.isEmpty()) {
                            segments.add(points);
                            points = new ArrayList<>();
                        }
                        continue;
                    }
                    if (++count > MAX_POINTS) throw new Problem("course_source_too_large");
                    points.add(new Point(requiredNumber(position, "LatitudeDegrees"),
                            requiredNumber(position, "LongitudeDegrees"), numeric(entry, "AltitudeMeters"), value(entry, "Time")));
                }
                if (!points.isEmpty()) segments.add(points);
            }
            for (var entry : children(chosen, "CoursePoint")) {
                var position = element(entry, "Position");
                addNavigation(navigation, new NavigationPoint(value(entry, "Name"), normalizedType(value(entry, "PointType")),
                        position == null ? null : numeric(position, "LatitudeDegrees"),
                        position == null ? null : numeric(position, "LongitudeDegrees"),
                        numeric(entry, "DistanceMeters"), value(entry, "Time")));
            }
            if (children(chosen, "CoursePoint").stream().anyMatch(p -> !children(p, "Notes", "AltitudeMeters").isEmpty())) {
                losses.add("TCX course point elevation or notes have no verified Garmin projection.");
            }
        }
        if (chosen.getElementsByTagNameNS("*", gpx ? "extensions" : "Extensions").getLength() > 0
                || !children(root, "extensions", "Extensions").isEmpty()) {
            losses.add("Source-specific XML extensions are omitted.");
        }
        String sport = gpx ? knownSport(value(chosen, "type")) : knownSport(chosen.getAttribute("Sport"));
        String description = value(chosen, gpx ? "desc" : "Notes");
        String name = value(chosen, gpx ? "name" : "Name");
        if (gpx && element(root, "metadata") != null) {
            var metadata = element(root, "metadata");
            if (description == null) description = value(metadata, "desc");
            if (name == null) name = value(metadata, "name");
            if (!children(metadata, "author", "copyright", "link", "keywords", "extensions").isEmpty()) {
                losses.add("GPX metadata without a Garmin equivalent is omitted.");
            }
        }
        if (gpx && "1.0".equals(root.getAttribute("version"))) {
            if (description == null) description = value(root, "desc");
            if (name == null) name = value(root, "name");
        }
        return trace(segments, navigation, name, sport, description, format.name(), losses);
    }

    private static NavigationPoint gpxNavigation(Element waypoint) {
        String type = value(waypoint, "type");
        if (type == null) type = value(waypoint, "sym");
        return new NavigationPoint(value(waypoint, "name"), normalizedType(type == null ? "generic" : type),
                attribute(waypoint, "lat"), attribute(waypoint, "lon"), null, value(waypoint, "time"));
    }

    private static String normalizedType(String type) {
        if (type == null) return null;
        var normalized = type.strip().toLowerCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        return switch (normalized) {
            case "4th_category" -> "fourth_category";
            case "3rd_category" -> "third_category";
            case "2nd_category" -> "second_category";
            case "1st_category" -> "first_category";
            case "hors_categorie" -> "hors_category";
            default -> normalized;
        };
    }

    private static String knownSport(String value) {
        String normalized = normalizedType(value);
        if (normalized == null) return null;
        return switch (normalized) {
            case "running", "trail_running", "hiking", "walking", "cycling", "road_biking", "mountain_biking",
                    "gravel_cycling", "obstacle_run", "other" -> normalized;
            case "biking" -> "cycling";
            default -> null;
        };
    }

    private static List<Element> children(Element parent, String... names) {
        var children = new ArrayList<Element>();
        var accepted = Set.of(names);
        for (var child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element && accepted.contains(element.getLocalName())
                    && java.util.Objects.equals(parent.getNamespaceURI(), element.getNamespaceURI())) children.add(element);
        }
        return children;
    }

    private static Element element(Element parent, String name) {
        var found = children(parent, name);
        return found.isEmpty() ? null : found.getFirst();
    }

    private static String value(Element parent, String name) {
        var found = element(parent, name);
        return found == null || found.getTextContent().isBlank() ? null : found.getTextContent().strip();
    }

    private static double attribute(Element element, String attribute) {
        return Double.parseDouble(element.getAttribute(attribute));
    }

    private static Double numeric(Element element, String name) {
        String value = value(element, name);
        return value == null ? null : Double.valueOf(value);
    }

    private static double requiredNumber(Element element, String name) {
        Double number = numeric(element, name);
        if (number == null) throw new Problem("invalid_input");
        return number;
    }

    private static void addNavigation(List<NavigationPoint> points, NavigationPoint point) {
        if (points.size() == MAX_COURSE_POINTS) throw new Problem("course_source_too_large");
        points.add(point);
    }

    private static void addOnce(List<String> warnings, String warning) {
        if (!warnings.contains(warning)) warnings.add(warning);
    }

    @Override
    public Trace readActivity(byte[] archive) {
        if (archive.length > MAX_SOURCE_BYTES) throw new Problem("course_source_too_large");
        try {
            byte[] member = GarminFitDecoder.extract(archive, System.nanoTime(), MAX_SOURCE_BYTES);
            if (member == null) throw new Problem("course_no_geometry");
            return readFit(member, "activity_fit", true);
        } catch (GarminFailure failure) {
            throw new Problem("fit_limit_exceeded".equals(failure.status()) ? "course_source_too_large" : "invalid_input");
        } catch (IOException malformed) {
            throw new Problem("invalid_input");
        }
    }

    private static Trace readFit(byte[] bytes, String source, boolean activityOnly) {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
        var parsed = new FitCourse();
        try {
            if (!new Decode().checkFileIntegrity(new ByteArrayInputStream(bytes))) throw new Problem("invalid_input");
            if (!new Decode().read(new ByteArrayInputStream(bytes), message -> {
                if (System.nanoTime() > deadline) throw new Problem("course_source_too_large");
                parsed.accept(message);
            })) throw new Problem("invalid_input");
        } catch (com.garmin.fit.FitRuntimeException invalid) {
            throw new Problem("invalid_input");
        }
        if (parsed.type != File.ACTIVITY && (activityOnly || parsed.type != File.COURSE)) throw new Problem("invalid_input");
        return trace(parsed.segments, parsed.navigation, parsed.name, parsed.sport, null, source, parsed.losses);
    }

    private static final class FitCourse {
        private File type;
        private String name;
        private String sport;
        private final List<List<Point>> segments = new ArrayList<>();
        private List<Point> segment;
        private int pointCount;
        private final List<NavigationPoint> navigation = new ArrayList<>();
        private final List<String> losses = new ArrayList<>();

        private void accept(com.garmin.fit.Mesg message) {
            switch (message.getNum()) {
                case MesgNum.FILE_ID -> {
                    if (type != null) throw new Problem("invalid_input");
                    type = new FileIdMesg(message).getType();
                }
                case MesgNum.COURSE -> {
                    var course = new CourseMesg(message);
                    name = course.getName();
                    sport = fitSport(course.getSport(), course.getSubSport());
                }
                case MesgNum.SESSION -> {
                    if (type == File.ACTIVITY) {
                        var session = new SessionMesg(message);
                        String sessionSport = fitSport(session.getSport(), session.getSubSport());
                        if (sport == null) sport = sessionSport;
                        else if (!sport.equals(sessionSport)) sport = "";
                    }
                }
                case MesgNum.RECORD -> {
                    var record = new RecordMesg(message);
                    Double latitude = degrees(record.getPositionLat());
                    Double longitude = degrees(record.getPositionLong());
                    if (latitude == null || longitude == null) {
                        segment = null;
                        return;
                    }
                    if (++pointCount > MAX_POINTS) throw new Problem("course_source_too_large");
                    if (segment == null) {
                        segment = new ArrayList<>();
                        segments.add(segment);
                    }
                    Float altitude = record.getEnhancedAltitude() == null ? record.getAltitude() : record.getEnhancedAltitude();
                    segment.add(new Point(latitude, longitude, altitude == null ? null : altitude.doubleValue(),
                            instant(record.getTimestamp())));
                }
                case MesgNum.COURSE_POINT -> {
                    var point = new CoursePointMesg(message);
                    addNavigation(navigation, new NavigationPoint(point.getName(),
                            point.getType() == null ? null : normalizedType(point.getType().name()),
                            degrees(point.getPositionLat()), degrees(point.getPositionLong()),
                            point.getDistance() == null ? null : point.getDistance().doubleValue(), instant(point.getTimestamp())));
                    if (point.getFavorite() == com.garmin.fit.Bool.TRUE) addOnce(losses, "FIT favorite flags are omitted.");
                }
                default -> { }
            }
        }
    }

    private static Double degrees(Integer semicircles) {
        return semicircles == null || semicircles == Integer.MAX_VALUE ? null : semicircles * DEGREES;
    }

    private static String instant(com.garmin.fit.DateTime time) {
        return time == null ? null : time.getDate().toInstant().toString();
    }

    private static String fitSport(Sport sport, SubSport subSport) {
        if (sport == Sport.RUNNING) {
            if (subSport == SubSport.TRAIL) return "trail_running";
            return subSport == SubSport.OBSTACLE ? "obstacle_run" : "running";
        }
        if (sport == Sport.HIKING || sport == Sport.WALKING) return "hiking";
        if (sport == Sport.CYCLING) {
            if (subSport == SubSport.MOUNTAIN) return "mountain_biking";
            if (subSport == SubSport.GRAVEL_CYCLING) return "gravel_cycling";
            return "road_biking";
        }
        return null;
    }

    private static Trace trace(List<List<Point>> segments, List<NavigationPoint> navigation, String name, String sport,
                               String description, String source, List<String> losses) {
        var metrics = CourseGeometry.metrics(segments);
        if (metrics.distanceMeters() <= 0) throw new Problem("course_no_geometry");
        return new Trace(segments.stream().filter(s -> !s.isEmpty()).toList(), navigation, name,
                sport == null || sport.isBlank() ? null : sport, description, source, metrics, List.of(), losses);
    }

    @Override
    public Export reconstruct(Snapshot course, Format format) {
        var content = course.content();
        var warnings = new ArrayList<>(course.warnings());
        warnings.add("Reconstructed from the course read back from Garmin.");
        if (format == Format.fit) throw new Problem("course_export_unsupported");
        if (content.segments().isEmpty()) throw new Problem("course_no_geometry");
        byte[] bytes = format == Format.geojson ? geoJson(content) : xml(content, format, warnings);
        if (bytes.length > MAX_EXPORT_BYTES) throw new Problem("course_response_too_large");
        return new Export(bytes, "reconstructed", warnings);
    }

    private byte[] geoJson(Content content) {
        if (content.segments().stream().anyMatch(segment -> segment.size() < 2)) {
            throw new Problem("course_export_unsupported", "GeoJSON requires two positions per segment.");
        }
        var coordinates = content.segments().stream().map(segment -> segment.stream().map(point -> point.elevationMeters() == null
                ? List.of(point.longitude(), point.latitude()) : List.of(point.longitude(), point.latitude(), point.elevationMeters())).toList()).toList();
        var properties = new LinkedHashMap<String, Object>();
        properties.put("name", content.name());
        properties.put("description", content.description());
        properties.put("sport", content.sport());
        properties.put("coursePoints", content.coursePoints());
        if (content.segments().stream().flatMap(List::stream).anyMatch(p -> p.timestamp() != null)) {
            properties.put("pointTimestamps", content.segments().stream().map(segment -> segment.stream().map(Point::timestamp).toList()).toList());
        }
        return json.writeValueAsBytes(Map.of("type", "Feature", "properties", properties, "geometry",
                coordinates.size() == 1 ? Map.of("type", "LineString", "coordinates", coordinates.getFirst())
                        : Map.of("type", "MultiLineString", "coordinates", coordinates)));
    }

    private static byte[] xml(Content content, Format format, List<String> warnings) {
        if (content.segments().stream().flatMap(List::stream).anyMatch(p -> p.longitude() == 180)
                || content.coursePoints().stream().anyMatch(p -> p.longitude() != null && p.longitude() == 180)) {
            warnings.add("Longitude +180 is expressed as −180 at the same position in the XML format.");
        }
        try {
            var output = new StringWriter();
            var out = XMLOutputFactory.newFactory().createXMLStreamWriter(output);
            out.writeStartDocument("UTF-8", "1.0");
            if (format == Format.gpx) writeGpx(content, warnings, out);
            else writeTcx(content, warnings, out);
            out.writeEndDocument();
            out.close();
            return output.toString().getBytes(StandardCharsets.UTF_8);
        } catch (XMLStreamException invalid) {
            throw new Problem("course_export_unsupported");
        }
    }

    private static void writeGpx(Content content, List<String> warnings, XMLStreamWriter out) throws XMLStreamException {
        out.writeStartElement("gpx");
        out.writeDefaultNamespace(GPX);
        out.writeAttribute("version", "1.1");
        out.writeAttribute("creator", "garmin-mcp");
        for (var point : content.coursePoints()) {
            if (point.latitude() == null || point.longitude() == null) {
                addOnce(warnings, "GPX omits navigation points without coordinates.");
                continue;
            }
            startGpxPoint(out, "wpt", point.latitude(), point.longitude());
            element(out, "time", point.timestamp());
            element(out, "name", point.name());
            element(out, "type", point.type());
            if (point.distanceMeters() != null) addOnce(warnings, "GPX does not preserve navigation distances.");
            out.writeEndElement();
        }
        out.writeStartElement("trk");
        element(out, "name", content.name());
        element(out, "desc", content.description());
        element(out, "type", content.sport());
        for (var segment : content.segments()) {
            out.writeStartElement("trkseg");
            for (var point : segment) {
                startGpxPoint(out, "trkpt", point.latitude(), point.longitude());
                element(out, "ele", point.elevationMeters());
                element(out, "time", point.timestamp());
                out.writeEndElement();
            }
            out.writeEndElement();
        }
        out.writeEndElement();
        out.writeEndElement();
    }

    private static void startGpxPoint(XMLStreamWriter out, String name, double lat, double lon) throws XMLStreamException {
        out.writeStartElement(name);
        out.writeAttribute("lat", java.math.BigDecimal.valueOf(lat).toPlainString());
        out.writeAttribute("lon", java.math.BigDecimal.valueOf(lon == 180 ? -180 : lon).toPlainString());
    }

    private static void writeTcx(Content content, List<String> warnings, XMLStreamWriter out) throws XMLStreamException {
        if (content.segments().stream().flatMap(List::stream).anyMatch(p -> p.timestamp() == null)) {
            throw new Problem("course_export_unsupported", "TCX Trackpoint requires a timestamp.");
        }
        if (content.sport() != null) warnings.add("TCX Course has no sport field.");
        out.writeStartElement("TrainingCenterDatabase");
        out.writeDefaultNamespace(TCX);
        out.writeStartElement("Courses");
        out.writeStartElement("Course");
        element(out, "Name", tcxName(content.name(), 15, warnings));
        for (var segment : content.segments()) {
            out.writeStartElement("Track");
            for (var point : segment) {
                out.writeStartElement("Trackpoint");
                element(out, "Time", point.timestamp());
                tcxPosition(out, point.latitude(), point.longitude());
                element(out, "AltitudeMeters", point.elevationMeters());
                out.writeEndElement();
            }
            out.writeEndElement();
        }
        element(out, "Notes", content.description());
        for (var point : content.coursePoints()) {
            String type = tcxType(point.type());
            if (point.name() == null || point.name().isBlank() || point.timestamp() == null
                    || point.latitude() == null || point.longitude() == null || type == null) {
                addOnce(warnings, "TCX omits navigation points lacking a required name, position, time, or supported type.");
                continue;
            }
            out.writeStartElement("CoursePoint");
            element(out, "Name", tcxName(point.name(), 10, warnings));
            element(out, "Time", point.timestamp());
            tcxPosition(out, point.latitude(), point.longitude());
            element(out, "PointType", type);
            out.writeEndElement();
            if (point.distanceMeters() != null) addOnce(warnings, "TCX does not preserve navigation distances.");
        }
        out.writeEndElement();
        out.writeEndElement();
        out.writeEndElement();
    }

    private static String tcxType(String type) {
        if (type == null) return null;
        return TCX_TYPES.stream().filter(candidate -> normalizedType(candidate).equals(type)).findFirst().orElse(null);
    }

    private static String tcxName(String name, int maximum, List<String> warnings) {
        if (name == null || name.isBlank()) throw new Problem("course_export_unsupported");
        String normalized = name.replaceAll("\\s+", " ").strip();
        if (normalized.codePointCount(0, normalized.length()) > maximum) {
            normalized = normalized.substring(0, normalized.offsetByCodePoints(0, maximum)).strip();
        }
        if (!normalized.equals(name)) addOnce(warnings, "Names were adjusted to fit TCX length and whitespace rules.");
        return normalized;
    }

    private static void tcxPosition(XMLStreamWriter out, double latitude, double longitude) throws XMLStreamException {
        out.writeStartElement("Position");
        element(out, "LatitudeDegrees", latitude);
        element(out, "LongitudeDegrees", longitude == 180 ? -180.0 : longitude);
        out.writeEndElement();
    }

    private static void element(XMLStreamWriter out, String name, Object value) throws XMLStreamException {
        if (value != null) {
            out.writeStartElement(name);
            out.writeCharacters(value instanceof Double number ? java.math.BigDecimal.valueOf(number).toPlainString() : value.toString());
            out.writeEndElement();
        }
    }
}
