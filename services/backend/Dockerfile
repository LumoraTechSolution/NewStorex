# The cloud backend, as one portable image.
#
# The same image runs on a free host today and on ECS, Azure Container Apps or a
# client's VM tomorrow — nothing in here names a provider. What changes between
# them is environment variables, and DEPLOYMENT.md is the contract for those.
#
#   docker build -t lumora-backend:0.0.1 services/backend
#
# This is the *cloud* tier only. The desktop profile ships inside the Electron
# installer (M5-01) and never sees a container.

# ---------------------------------------------------------------------------
# Stage 1 — build
# ---------------------------------------------------------------------------
FROM eclipse-temurin:17-jdk-jammy AS build

WORKDIR /build

# The wrapper first, and the pom on its own, so that `dependency:go-offline`
# becomes a cached layer. A change under src/ then costs a compile, not a
# re-download of Maven Central.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./

# mvnw is CRLF in the repo, because it was checked out on Windows. Linux then
# reads the shebang as `/bin/sh\r`, looks for an interpreter of that name, and
# reports "./mvnw: not found" about a file that is plainly there. Strip the
# carriage returns here rather than converting the file, which would break it
# for the Windows developer who runs it every day.
# chmod for the neighbouring problem: core.fileMode can drop the executable bit.
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw

RUN ./mvnw -B -q dependency:go-offline

COPY src/ src/

# Tests are skipped here deliberately, and it is not a shortcut: they run against
# a real Postgres (the db-test compose container) because correctness rests on
# ON CONFLICT upserts and partial indexes that H2 would fake. Testcontainers does
# not work on the build machine either — see CLAUDE.md. So there is no database
# for them to reach from inside a build container. `./mvnw -B test` on the dev
# machine is the gate; this stage is packaging.
RUN ./mvnw -B -q -DskipTests package

# Split the fat jar into its four layers. The dependency layer is ~50 MB and
# changes only when pom.xml does; the application layer is ~1 MB and changes
# every commit. Most deploys then push a megabyte.
#
# `-Djarmode=tools` is the Boot 3.3 spelling; -Djarmode=layertools still works
# but warns that it is deprecated.
RUN java -Djarmode=tools -jar target/lumora-backend-*.jar extract --layers --launcher --destination extracted

# ---------------------------------------------------------------------------
# Stage 2 — runtime
# ---------------------------------------------------------------------------
# JRE, not JDK: ~180 MB against ~330 MB, and nothing here compiles.
# jammy, not alpine: glibc is the tested path for the Postgres driver's TLS and
# SCRAM. Forty megabytes is not worth a class of bug that only appears in
# production.
FROM eclipse-temurin:17-jre-jammy AS runtime

# curl is here for HEALTHCHECK. The base image has neither curl nor wget, and the
# bash /dev/tcp alternative is clever in a way that no one will thank us for at
# three in the morning.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# A numeric USER, not a name: Kubernetes and Azure Container Apps enforce
# runAsNonRoot by reading the value, and a name means resolving /etc/passwd.
RUN useradd --system --uid 10001 --create-home --shell /usr/sbin/nologin lumora

WORKDIR /app

COPY --from=build --chown=10001:10001 /build/extracted/dependencies/ ./
COPY --from=build --chown=10001:10001 /build/extracted/spring-boot-loader/ ./
COPY --from=build --chown=10001:10001 /build/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=10001:10001 /build/extracted/application/ ./

USER 10001

# The JVM's default MaxRAMPercentage is 25 — on a 512 MB container that is a
# 128 MB heap, which GC-thrashes under Flyway plus Hibernate's 27 entities.
# SerialGC because G1's concurrent threads are pure overhead on one vCPU.
# The egd flag avoids an entropy stall at boot, which matters here because
# PlatformBootstrap draws from SecureRandom on the first start of an empty
# database.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:InitialRAMPercentage=40 -XX:+UseSerialGC -XX:MaxMetaspaceSize=128m -Djava.security.egd=file:/dev/urandom"

# application-cloud.yml hardcodes 8082. Hosts that dictate a port (Render injects
# $PORT) override this variable rather than the file: Spring's relaxed binding
# maps SERVER_PORT to server.port, and environment variables outrank profile
# YAML. SERVER_PORT is the portable spelling — identical on ECS, ACA and a VM.
#
# Deliberately not an entrypoint shell script reading $PORT: that puts a shell at
# PID 1, and a shell that does not forward SIGTERM would silently break
# `server.shutdown: graceful`.
ENV SERVER_PORT=8082
EXPOSE 8082

# Readiness, not liveness: liveness includes the disk-space indicator, and this
# container writes nothing to disk. The 90 s start period is Flyway running the
# cloud migrations against a database whose compute may be cold.
#
# Render and ACA ignore this and use their own configured probe — point both at
# the same path. /actuator/** sits outside the auth filter's /api/* pattern
# precisely so a load balancer can ask without holding a shop's key.
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
    CMD curl -fsS "http://127.0.0.1:${SERVER_PORT}/actuator/health/readiness" || exit 1

# launch.JarLauncher — the package moved in Boot 3.2. The pre-3.2 spelling
# (org.springframework.boot.loader.JarLauncher) throws ClassNotFoundException.
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
