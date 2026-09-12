# ══════════════════════════════════════════════════════════
# STAGE 1: BUILD — compile the app into a fat JAR
# ══════════════════════════════════════════════════════════
FROM eclipse-temurin:21-jdk AS builder

WORKDIR /app

# Copy the Gradle wrapper + build scripts FIRST (layer-caching trick:
# dependencies re-download only when these files change, not on every code edit).
COPY gradlew .
COPY gradle ./gradle
COPY build.gradle settings.gradle ./
RUN chmod +x gradlew

# Warm the dependency cache. --no-daemon: no long-lived process in a container build.
RUN ./gradlew dependencies --no-daemon || true

# Now copy source (changes often → separate, later layer).
COPY src ./src

# Build the JAR, skip tests (tests need a live DB; they run separately/in CI).
RUN ./gradlew bootJar --no-daemon -x test

# ══════════════════════════════════════════════════════════
# STAGE 2: RUNTIME — only the JRE + the JAR
# ══════════════════════════════════════════════════════════
FROM eclipse-temurin:21-jre AS runtime

WORKDIR /app

# Non-root user (if the app is exploited, attacker isn't root inside the container).
RUN groupadd --system appgroup && useradd --system --gid appgroup appuser

# Copy only the built JAR from the build stage.
COPY --from=builder /app/build/libs/*.jar app.jar
RUN chown appuser:appgroup app.jar

USER appuser

EXPOSE 8080

# JVM respects the container's memory limit instead of the host's total RAM.
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"

# Docker knows the app is actually READY (not just "process started").
HEALTHCHECK --interval=15s --timeout=5s --retries=5 --start-period=45s \
  CMD wget -q --spider http://localhost:8080/actuator/health || exit 1

# sh -c so $JAVA_OPTS expands at runtime.
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
