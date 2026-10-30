# RUTERA standalone image: the React client and the Spring Boot backend in one jar.
# Usually built through compose (docker compose up -d --build); alone: docker build -t rutera .
#   1. frontend: npm ci + npm run build (frontend/dist)
#   2. backend:  mvn -Pfrontend -Dexec.skip=true package, which puts that dist into the jar as classpath:/static/
#   3. runtime:  JRE only, unprivileged user, /opt/rutera/{conf,logs,data} as in an installation without Docker
# JVM options: JAVA_TOOL_OPTIONS (e.g. -Xmx1g); application settings: RUTERA_* variables, see .env.example.

FROM node:22-alpine AS frontend
WORKDIR /build/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/ ./
# The production client talks to the real backend on the same origin, never to the in-browser mock.
ENV VITE_API_MOCK=false
RUN npm run build

FROM maven:3.9-eclipse-temurin-25 AS backend
WORKDIR /build
COPY pom.xml ./
COPY common/pom.xml common/
COPY core/pom.xml core/
COPY tacho/pom.xml tacho/
COPY common/src common/src
COPY core/src core/src
COPY tacho/src tacho/src
COPY --from=frontend /build/frontend/dist frontend/dist
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -Pfrontend -Dexec.skip=true -DskipTests package

FROM eclipse-temurin:25-jre
RUN groupadd --system rutera \
    && useradd --system --gid rutera --home-dir /opt/rutera --no-create-home --shell /usr/sbin/nologin rutera
WORKDIR /opt/rutera
COPY --from=backend /build/core/target/rutera.jar rutera.jar
RUN mkdir conf logs data && chown rutera:rutera logs data
USER rutera
ENV RUTERA_PORT=8080
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/opt/rutera/rutera.jar"]
