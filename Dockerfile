# BuildKit cache mounts keep dependencies out of the runtime image.
# Official Temurin 17.0.20.1+1 / Ubuntu 24.04; reviewed 2026-10-12.
FROM eclipse-temurin:17-jdk-noble@sha256:fb79620c3f6ee8f1c7530a8dc4fd49e3b410bcef037694ad4858a1553bac4170 AS build
WORKDIR /build
ENV MAVEN_OPTS="-Xmx384m -XX:MaxMetaspaceSize=192m -XX:+ExitOnOutOfMemoryError"
COPY mvnw pom.xml ./
COPY .mvn/wrapper/maven-wrapper.properties .mvn/wrapper/maven-wrapper.properties
RUN chmod 0555 mvnw
RUN --mount=type=cache,target=/root/.m2,sharing=locked \
    ./mvnw --batch-mode --no-transfer-progress dependency:go-offline
COPY src/main/ src/main/
# Packaging follows the mandatory full verify gate; it does not replace regression testing.
ARG SOURCE_DATE_EPOCH=315532800
RUN --mount=type=cache,target=/root/.m2,sharing=locked \
    ./mvnw --batch-mode --no-transfer-progress -DskipTests \
    -Dproject.build.outputTimestamp=${SOURCE_DATE_EPOCH} package

FROM eclipse-temurin:17-jre-noble@sha256:900322f4cc1b9da9d730ae4bb56d8a1dabbaa9cfa32809fc8b542b600370a045 AS runtime
# Published Ubuntu security fixes newer than the base image; exact versions, no blanket upgrade.
RUN apt-get update && apt-get install -y --no-install-recommends \
    libfreetype6=2.13.2+dfsg-1ubuntu0.2 libpng16-16t64=1.6.43-5ubuntu0.7 && \
    rm -rf /var/lib/apt/lists/* /var/cache/apt/archives/* && \
    rm -f /var/log/apt/history.log /var/log/apt/term.log /var/log/dpkg.log
ARG REVISION=unknown
LABEL org.opencontainers.image.revision="${REVISION}" \
      inventory-management-system.health-path="/readyz"
RUN groupadd --gid 10001 app && \
    useradd --uid 10001 --gid 10001 --no-create-home --shell /usr/sbin/nologin app && \
    install -d -o root -g root -m 0755 /app
WORKDIR /app
COPY --from=build --chown=0:0 --chmod=0444 /build/target/demo-0.0.1-SNAPSHOT.jar /app/app.jar
ENV JAVA_TOOL_OPTIONS="-XX:InitialRAMPercentage=10 -XX:MaxRAMPercentage=25 -XX:MaxMetaspaceSize=160m -XX:MaxDirectMemorySize=32m -XX:ReservedCodeCacheSize=64m -Xss512k -XX:+ExitOnOutOfMemoryError"
USER 10001:10001
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=3 \
    CMD curl --fail --silent --max-time 2 http://127.0.0.1:8080/readyz >/dev/null || exit 1
ENTRYPOINT ["java","-jar","/app/app.jar"]
