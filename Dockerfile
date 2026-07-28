# ─────────────────────────────────────────────────────────────────────────────
# Multi-stage Dockerfile for Distributed Task Queue
# ─────────────────────────────────────────────────────────────────────────────

# ── Stage 1: Build the Application ───────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21-alpine AS builder
WORKDIR /app

# Cache dependencies
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Build the JAR package
COPY src ./src
RUN mvn clean package -DskipTests -Dspring.classformat.ignore=true

# ── Stage 2: Lightweight Runtime ──────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Copy the built JAR from the builder stage
COPY --from=builder /app/target/*.jar app.jar

# Configuration environment defaults
ENV JAVA_OPTS="-Dspring.classformat.ignore=true"

EXPOSE 8080 8081

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
