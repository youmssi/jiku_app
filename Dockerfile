# Build stage — Gradle + Java 25 JDK
FROM eclipse-temurin:25-jdk-noble AS builder
WORKDIR /app

# Copy Gradle wrapper files first so dependency downloads are cached
# as a separate layer, invalidated only when build files change.
COPY gradle gradle
COPY gradlew gradlew.bat settings.gradle.kts build.gradle.kts ./
RUN chmod +x gradlew

# Pre-download all dependencies (cached layer)
RUN ./gradlew dependencies --no-daemon -q

# Copy source and produce the fat JAR (tests run in CI, skip here)
COPY src ./src
RUN ./gradlew bootJar -x test --no-daemon

# Production stage — JRE only, no build tools
FROM eclipse-temurin:25-jre-noble
WORKDIR /app

# Run as a non-root user
RUN groupadd --system tum && useradd --system --gid tum --no-create-home tum
USER tum

COPY --from=builder /app/build/libs/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", "app.jar"]
