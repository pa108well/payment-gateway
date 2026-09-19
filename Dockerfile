FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /app
COPY pom.xml .
COPY src src
RUN --mount=type=cache,target=/root/.m2 mvn -B package -DskipTests
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /app/target/payment-gateway-1.0.0.jar app.jar
USER 10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
