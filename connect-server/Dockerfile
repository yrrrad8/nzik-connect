FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /source
COPY gradlew gradlew.bat settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
COPY extensions/innertube ./extensions/innertube
COPY extensions/invidious ./extensions/invidious
COPY extensions/ktor-client-brotli ./extensions/ktor-client-brotli
COPY connect-server ./connect-server
RUN sed -i 's|http://|https://|g' /etc/apt/sources.list \
    && apt-get update && apt-get install -y --no-install-recommends ffmpeg ca-certificates && rm -rf /var/lib/apt/lists/*
RUN --mount=type=cache,target=/root/.gradle sed -i 's/\r$//' gradlew && chmod +x gradlew \
    && ./gradlew --no-daemon -Dorg.gradle.jvmargs=-Xmx2g -PconnectOnly=true :connect-server:test :connect-server:installDist

FROM eclipse-temurin:21-jre-jammy
RUN sed -i 's|http://|https://|g' /etc/apt/sources.list \
    && apt-get update && apt-get install -y --no-install-recommends ffmpeg ca-certificates && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 nzik && useradd --uid 10001 --gid nzik --create-home nzik
WORKDIR /opt/nzik
COPY --from=build --chown=10001:10001 /source/connect-server/build/install/connect-server ./
COPY LICENSE ./LICENSE
USER 10001:10001
ENTRYPOINT ["/opt/nzik/bin/connect-server"]
CMD ["--help"]
