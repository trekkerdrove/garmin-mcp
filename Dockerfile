FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY src ./src
RUN mvn -B verify

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN apt-get update \
    && apt-get install -y --no-install-recommends python3-venv \
    && python3 -m venv /opt/garmin-python \
    && /opt/garmin-python/bin/pip install --no-cache-dir 'garminconnect==0.3.2' \
    && rm -rf /var/lib/apt/lists/*
COPY --from=build /workspace/target/garmin-mcp.jar ./garmin-mcp.jar
COPY scripts/garmin_workout_bridge.py scripts/garmin_courses_bridge.py ./scripts/
USER 10001:10001
EXPOSE 10000
ENV PORT=10000
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=65.0", "-jar", "garmin-mcp.jar"]
