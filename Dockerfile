# ---- build ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package -DskipTests

# ---- run ----
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 10001 vishwas && mkdir -p /app/data && chown vishwas /app/data
COPY --from=build /src/target/vishwas.jar app.jar
USER vishwas
# Hosting platforms inject PORT; Spring reads it (server.port=${PORT:8080}). JSON logs in containers.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75" LOG_FORMAT=json
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s CMD wget -qO- http://localhost:8080/api/health/live || exit 1
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
