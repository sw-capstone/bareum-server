# syntax=docker/dockerfile:1

FROM eclipse-temurin:25-jdk-noble@sha256:95e350ad1eb738f943e141640f7e3d797e483359a3b33c3864255bfd1ad71f8a AS build

WORKDIR /workspace

COPY gradlew build.gradle.kts settings.gradle.kts ./
COPY gradle/ gradle/
COPY src/main/ src/main/

RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon bootJar -Dorg.gradle.java.installations.auto-download=false

RUN java -Djarmode=tools -jar build/libs/app.jar extract --layers --destination extracted

FROM eclipse-temurin:25-jre-noble@sha256:a98128a57de944a72bd31fbc79b898edd5a5864d3652071d5bbd2160ab7d7dce AS runtime

WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

RUN groupadd --gid 10001 app \
    && useradd --uid 10001 --gid app --no-create-home --shell /usr/sbin/nologin app

COPY --from=build --chown=app:app /workspace/extracted/dependencies/ ./
COPY --from=build --chown=app:app /workspace/extracted/spring-boot-loader/ ./
COPY --from=build --chown=app:app /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=app:app /workspace/extracted/application/ ./
COPY --from=build --chown=app:app /workspace/build/kiwi/models/cong/base/ /app/models/cong/base/

ENV KIWI_MODEL_PATH=/app/models/cong/base

USER app

EXPOSE 8080

ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "-jar", "/app/app.jar"]
