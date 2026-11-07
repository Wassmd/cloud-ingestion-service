# syntax=docker/dockerfile:1

# ---- Build stage ----
# Build natively on the host architecture (the JAR is platform independent);
# only the runtime stage needs to match the target platform (linux/amd64).
FROM --platform=$BUILDPLATFORM eclipse-temurin:25-jdk AS build
WORKDIR /workspace

# Cache Maven dependencies separately from the sources
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline

COPY src src
RUN ./mvnw -B -q package -DskipTests

# ---- Runtime stage ----
FROM eclipse-temurin:25-jre
WORKDIR /app

RUN useradd --system --no-create-home appuser
USER appuser

COPY --from=build /workspace/target/cloud-ingestion-service-0.0.1-SNAPSHOT.jar app.jar

# Cloud Run sets PORT (default 8080); Spring Boot listens on 8080 by default
EXPOSE 8080

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
