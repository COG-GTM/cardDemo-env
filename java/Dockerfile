# Runtime image for one module; jars are built on the host with `make java-build`.
ARG JAVA_BASE_IMAGE=eclipse-temurin:21-jre
FROM ${JAVA_BASE_IMAGE}
ARG MODULE
COPY ${MODULE}/target/${MODULE}.jar /app/app.jar
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
