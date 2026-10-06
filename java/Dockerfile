# Runtime image for the event-mode chain (COG-1251): four services, the outbox relay and parity-replay.
FROM maven:3.9-eclipse-temurin-21 AS build
ARG MAVEN_MIRROR_URL=
WORKDIR /src
COPY . .
RUN if [ -n "$MAVEN_MIRROR_URL" ]; then \
      mkdir -p /root/.m2 && printf '<settings><mirrors><mirror><id>mirror</id><url>%s</url><mirrorOf>central</mirrorOf></mirror></mirrors></settings>' \
        "$MAVEN_MIRROR_URL" > /root/.m2/settings.xml; \
    fi
RUN --mount=type=cache,target=/root/.m2/repository mvn -B -q -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/event-services/target/event-services.jar /app/event-services.jar
COPY --from=build /src/outbox-relay/target/outbox-relay.jar /app/outbox-relay.jar
COPY --from=build /src/parity-replay/target/parity-replay.jar /app/parity-replay.jar
