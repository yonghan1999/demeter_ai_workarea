# syntax=docker/dockerfile:1.7
FROM eclipse-temurin:17.0.20_8-jdk-jammy@sha256:400014962ad7224461f945bb1cc3d7d5a1927ce15b8245b72d9cedcda554cd2a AS build
WORKDIR /workspace

COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q -DskipTests dependency:go-offline

COPY src src
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q -DskipTests package

FROM eclipse-temurin:17.0.20_8-jre-jammy@sha256:e17d77fb030dd4b642dc078d048a5fb9efcb3676ee20305d905949105a6ccd5a
RUN groupadd --system --gid 10001 demeter \
    && useradd --system --uid 10001 --gid demeter --home-dir /app --shell /usr/sbin/nologin demeter \
    && mkdir -p /app /var/lib/demeter/ocr \
    && chown -R demeter:demeter /app /var/lib/demeter

WORKDIR /app
COPY --from=build --chown=demeter:demeter /workspace/target/demeter-backend-*.jar app.jar
COPY --chown=demeter:demeter docker-entrypoint.sh /app/docker-entrypoint.sh

USER 10001:10001
EXPOSE 8080 9090
ENV SPRING_PROFILES_ACTIVE=prod \
    MANAGEMENT_PORT=9090 \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/urandom"

HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
  CMD ["/bin/bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/${MANAGEMENT_PORT}; printf 'GET /actuator/health/readiness HTTP/1.0\r\nHost: localhost\r\n\r\n' >&3; grep -q ' 200 ' <&3"]

ENTRYPOINT ["/bin/sh", "/app/docker-entrypoint.sh"]
CMD ["api"]
