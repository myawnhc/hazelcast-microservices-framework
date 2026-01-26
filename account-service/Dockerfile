# Account Service Dockerfile
# Expects JAR to be pre-built: mvn package -DskipTests

FROM eclipse-temurin:17-jre-jammy

LABEL maintainer="theyawns"
LABEL service="account-service"

WORKDIR /app

# Install curl for health checks
RUN apt-get update && apt-get install -y curl && rm -rf /var/lib/apt/lists/*

# Create non-root user for security
RUN groupadd -r appuser && useradd -r -g appuser appuser

# Copy the pre-built JAR
COPY target/account-service-*.jar app.jar

# Set ownership
RUN chown -R appuser:appuser /app

USER appuser

# Expose service port
EXPOSE 8081

# JVM options for containerized environment
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -Djava.security.egd=file:/dev/./urandom"

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:8081/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
