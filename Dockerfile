# One image serves both the React app and the Spring Boot API from the same URL.
#   docker build -t proofpath .
#   docker run -p 8081:8081 --env-file .env proofpath

# 1) Build the React frontend
FROM node:20-alpine AS frontend
WORKDIR /app/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/ ./
RUN npm run build

# 2) Build the Spring Boot jar with the frontend bundled as static files
FROM maven:3.9-eclipse-temurin-17 AS backend
WORKDIR /app
COPY pom.xml ./
RUN mvn -q -B dependency:go-offline
COPY src ./src
COPY --from=frontend /app/frontend/dist ./src/main/resources/static
RUN mvn -q -B -DskipTests package

# 3) Small runtime image, running as an unprivileged user
FROM eclipse-temurin:17-jre
RUN useradd --system --create-home --uid 10001 proofpath
WORKDIR /app
COPY --from=backend --chown=proofpath:proofpath /app/target/*.jar app.jar
USER proofpath
# Hosting platforms set PORT; locally the app falls back to 8081.
EXPOSE 8081
# Sized for small (512 MB) free-tier instances; exit on OOM so the platform restarts the container cleanly.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError -Xss512k"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
