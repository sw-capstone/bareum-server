# syntax=docker/dockerfile:1

FROM eclipse-temurin:25-jdk-noble@sha256:4ddc1a8bdaf283421df5487411aff10b797ccf1e06a339041005114c8f489b5c AS build

WORKDIR /workspace

COPY gradlew build.gradle.kts settings.gradle.kts ./
COPY gradle/ gradle/
COPY src/main/ src/main/

RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon bootJar -Dorg.gradle.java.installations.auto-download=false

RUN java -Djarmode=tools -jar build/libs/app.jar extract --layers --destination extracted

FROM eclipse-temurin:25-jre-noble@sha256:f3dd0838e69b881cc382237d5901e74ae3705b1056ad964b2e0e523b174fef99 AS runtime

ARG TARGETARCH

WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        curl fontconfig fonts-liberation fonts-nanum fonts-noto-cjk libreoffice-writer \
    && rm -rf /var/lib/apt/lists/* \
    && fc-cache -f

RUN case "$TARGETARCH" in \
        arm64) rhwp_arch=aarch64; rhwp_sha=85d17b51b96badadafd4a1f890c0d182a44d3100ff1a9f41c9235bf901f4cdd8 ;; \
        amd64) rhwp_arch=x86_64; rhwp_sha=9283dd9ecd3182ddb50eeba9a2f26d2d23b5386e39defb081e52a3706916e65d ;; \
        *) echo "Unsupported rhwp architecture: $TARGETARCH" >&2; exit 1 ;; \
    esac \
    && curl --fail --silent --show-error --location --retry 3 \
        "https://github.com/edwardkim/rhwp/releases/download/v0.9.1/rhwp-v0.9.1-linux-${rhwp_arch}.tar.gz" \
        -o /tmp/rhwp.tar.gz \
    && printf '%s  %s\n' "$rhwp_sha" /tmp/rhwp.tar.gz | sha256sum --check --status \
    && mkdir /opt/rhwp \
    && tar --no-same-owner --strip-components=1 -xzf /tmp/rhwp.tar.gz -C /opt/rhwp \
    && ln -s /opt/rhwp/rhwp /usr/local/bin/rhwp \
    && rm /tmp/rhwp.tar.gz

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
