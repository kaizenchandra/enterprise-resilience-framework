FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /src
COPY . .
RUN --mount=type=cache,target=/root/.m2 mvn -B -DskipTests package
FROM eclipse-temurin:21-jre
ARG SERVICE=order-service
WORKDIR /app
COPY --from=build /src/examples/${SERVICE}/target/${SERVICE}-1.0.0-SNAPSHOT.jar /app/service.jar
USER 10001
ENTRYPOINT ["java","-XX:MaxRAMPercentage=70","-jar","/app/service.jar"]
