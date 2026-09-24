# Build stage: the host needs Docker only, not a JDK. The jar does not depend on the processor, so
# this stage runs natively on the build machine even when the image is for another architecture.
FROM --platform=$BUILDPLATFORM eclipse-temurin:25-jdk AS build
WORKDIR /workspace

# The wrapper and build scripts come first, so the dependency download stays cached until they change.
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
RUN chmod +x gradlew && ./gradlew --no-daemon --quiet dependencies > /dev/null

# Set by the release workflow from the git tag. Left out, Gradle reports 0.0.0-dev.
ARG VERSION
COPY src src
RUN ./gradlew --no-daemon ${VERSION:+-PreleaseVersion=$VERSION} bootJar

FROM eclipse-temurin:25-jre

RUN useradd --system --create-home --shell /usr/sbin/nologin mcphub
WORKDIR /app
COPY --from=build --chown=mcphub:mcphub /workspace/build/libs/mcphub-*.jar app.jar

USER mcphub
# Inside the container the server must listen on every interface, otherwise a published port
# reaches nothing; only the host side of the port binding limits access.
ENV SERVER_ADDRESS=0.0.0.0
# A file rather than a directory: a directory that cannot be read would be skipped silently,
# leaving a server with no environments, while a missing file stops the start with an error.
ENV SPRING_CONFIG_ADDITIONAL_LOCATION=file:/config/application.yaml
EXPOSE 8282
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
