# Runtime image
FROM registry.access.redhat.com/ubi9/openjdk-21-runtime:latest
WORKDIR /deployments
USER 185
# App
COPY target/quarkus-app/lib/ lib/
COPY target/quarkus-app/*.jar ./
COPY target/quarkus-app/app/ app/
COPY target/quarkus-app/quarkus/ quarkus/
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "quarkus-run.jar"]
