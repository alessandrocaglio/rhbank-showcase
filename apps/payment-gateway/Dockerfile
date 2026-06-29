# Stage 1: download OTel agent
FROM registry.access.redhat.com/ubi9/openjdk-21:latest AS agent-downloader
RUN curl -fsSL -o /tmp/opentelemetry-javaagent.jar \
    https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.10.0/opentelemetry-javaagent.jar

# Stage 2: runtime image
FROM registry.access.redhat.com/ubi9/openjdk-21-runtime:latest
WORKDIR /deployments
# OTel agent (root required to write to /opt)
USER root
RUN mkdir -p /opt/otel
COPY --from=agent-downloader /tmp/opentelemetry-javaagent.jar /opt/otel/opentelemetry-javaagent.jar
RUN chmod 644 /opt/otel/opentelemetry-javaagent.jar
USER 185
# App
COPY target/payment-gateway-1.0.0-SNAPSHOT.jar app.jar
EXPOSE 8090
ENTRYPOINT ["java", "-jar", "app.jar"]
