# Runtime image
FROM registry.access.redhat.com/ubi9/openjdk-21-runtime:latest
WORKDIR /deployments
USER 185
# App
COPY target/transaction-engine-1.0.0-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
