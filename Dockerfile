# Render builds these stages on its servers. No local Docker installation needed.
FROM node:24-bookworm-slim AS frontend
WORKDIR /workspace/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run lint && npm run build

FROM maven:3.9.16-eclipse-temurin-21-noble AS backend
WORKDIR /workspace/myBooks/myBooks
COPY myBooks/myBooks/pom.xml ./
COPY myBooks/myBooks/src/ src/
COPY --from=frontend /workspace/frontend/dist/ /workspace/frontend/dist/
# Run the backend tests while building; tests use disposable local databases.
# Show dependency downloads so the first build's progress is visible.
RUN mvn -B package

FROM eclipse-temurin:21-jre-jammy AS runtime
RUN useradd --system --uid 10001 --create-home bookbuddy
WORKDIR /app
COPY --from=backend --chown=10001:10001 /workspace/myBooks/myBooks/target/myBooks-0.0.1-SNAPSHOT.jar ./bookbuddy.jar
USER 10001:10001
ENV SPRING_PROFILES_ACTIVE=prod
EXPOSE 10000
# Leave room within a small host's memory limit for JVM native memory and threads.
# This is a budget for the first demo, not a capacity guarantee.
ENTRYPOINT ["java", "-Xms64m", "-Xmx256m", "-XX:MaxMetaspaceSize=128m", "-XX:ReservedCodeCacheSize=48m", "-XX:MaxDirectMemorySize=32m", "-XX:+UseSerialGC", "-Xss512k", "-jar", "/app/bookbuddy.jar"]
