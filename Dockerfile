# syntax=docker/dockerfile:1

# ---- build stage: compile the jar with the Maven wrapper -------------------------------------
FROM eclipse-temurin:17-jdk AS build
WORKDIR /src

# Dependencies first, so they are cached until pom.xml changes.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw && ./mvnw -q -B dependency:go-offline

COPY src/ src/
RUN ./mvnw -q -B -DskipTests package && cp target/prism-gateway-*.jar /src/app.jar

# ---- runtime stage: JRE only, non-root ---------------------------------------------------------
FROM eclipse-temurin:17-jre
WORKDIR /app

RUN groupadd --system prism \
    && useradd --system --gid prism --home-dir /app --no-create-home prism \
    && mkdir -p /app/var \
    && chown prism:prism /app/var

COPY --from=build /src/app.jar /app/app.jar
# Seed keys, price table, gateway config and routing eval sets (read from ./data at startup).
COPY data/ /app/data/

USER prism
EXPOSE 8080
# H2 database file (keys, usage, request logs, cache entries) lives here; mount a volume to keep it.
VOLUME ["/app/var"]

# Provider URLs default to localhost from data/gateway_config.sample.json; override per provider with
# PRISM_PROVIDER_<NAME>_BASE_URL (see docker-compose.yml). Extra JVM flags go in JAVA_OPTS.
ENV JAVA_OPTS=""
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
