FROM eclipse-temurin:21-jdk-jammy@sha256:6adefddd4a20bceef702cedb0b03952fcd7691f9c7ccffe27014992abc0b46bd AS build
WORKDIR /workspace
COPY backend backend
COPY contracts contracts
RUN --mount=type=cache,target=/root/.gradle \
    backend/gradlew -p backend :app:bootJar --no-daemon --console=plain

FROM eclipse-temurin:21-jre-jammy@sha256:e9aaf73145bbd1f9f6ec7f6867dd75a44f34b1a6c32a813504bf4129be2d09d7
WORKDIR /app
COPY --from=build --chown=10001:10001 /workspace/backend/app/build/libs/edgeai-control-plane.jar /app/app.jar
ENV EDGEAI_BIND_ADDRESS=0.0.0.0 EDGEAI_API_PORT=18080
USER 10001:10001
EXPOSE 18080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
