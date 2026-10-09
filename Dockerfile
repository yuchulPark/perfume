# syntax=docker/dockerfile:1
FROM eclipse-temurin:17-jdk-noble AS build
WORKDIR /workspace

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
# A Windows checkout may contain CRLF or lack the Linux executable permission.
RUN sed -i 's/\r$//' mvnw && chmod 0755 mvnw
COPY src/ src/
# Run the ordinary suite. No API key, live-test gates or database credentials are supplied.
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -ntp package

FROM eclipse-temurin:17-jre-noble AS runtime
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system --gid 10001 perfume \
    && useradd --system --uid 10001 --gid 10001 --home-dir /app perfume
WORKDIR /app
COPY --from=build --chown=10001:10001 /workspace/target/*.jar /app/app.jar
USER 10001:10001
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
# CLI properties override application.properties and environment defaults.
CMD ["--server.port=8081", "--scentrev.brand-import=false", "--spring.jpa.hibernate.ddl-auto=validate", "--spring.sql.init.mode=never", "--spring.jpa.show-sql=false"]
