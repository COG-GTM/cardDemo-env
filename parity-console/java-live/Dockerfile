# syntax=docker/dockerfile:1
FROM maven:3.9-eclipse-temurin-21 AS build
# Optional Maven Central mirror (e.g. when repo.maven.apache.org rate-limits the build host).
ARG MAVEN_MIRROR_URL=
RUN if [ -n "$MAVEN_MIRROR_URL" ]; then mkdir -p /root/.m2 && printf \
    '<settings><mirrors><mirror><id>mirror</id><mirrorOf>central</mirrorOf><url>%s</url></mirror></mirrors></settings>' \
    "$MAVEN_MIRROR_URL" > /root/.m2/settings.xml; fi
WORKDIR /build
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2/repository mvn -q -B dependency:go-offline
COPY src src
RUN --mount=type=cache,target=/root/.m2/repository mvn -q -B -DskipTests package \
    && cp target/xferfee-java-live.jar /build/app.jar

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/app.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
