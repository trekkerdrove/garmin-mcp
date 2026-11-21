FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY src ./src
RUN mvn -B verify

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/target/garmin-mcp.jar ./garmin-mcp.jar
USER 10001:10001
EXPOSE 10000
ENV PORT=10000
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=65.0", "-jar", "garmin-mcp.jar"]
