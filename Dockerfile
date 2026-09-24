# Build stage: the host needs Docker only, not a JDK.
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace

# The wrapper and build scripts come first, so the dependency download stays cached until they change.
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
RUN chmod +x gradlew && ./gradlew --no-daemon --quiet dependencies > /dev/null

COPY src src
RUN ./gradlew --no-daemon bootJar

FROM eclipse-temurin:25-jre

RUN useradd --system --create-home --shell /usr/sbin/nologin mcphub
WORKDIR /app
COPY --from=build --chown=mcphub:mcphub /workspace/build/libs/mcphub-*.jar app.jar

USER mcphub
EXPOSE 8282
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
