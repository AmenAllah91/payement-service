FROM maven:3.9.6-eclipse-temurin-17 AS build

WORKDIR /app

COPY pom.xml .

RUN mvn dependency:go-offline

COPY src ./src

RUN mvn clean package -DskipTests


FROM eclipse-temurin:17-jre-alpine

LABEL maintainer="kraiem.amenallah@gmail.com"

WORKDIR /app

COPY --from=build /app/target/payment-microservice-0.0.1-SNAPSHOT.jar payment-service.jar

EXPOSE 8099

ENTRYPOINT ["java", "-jar", "payment-service.jar"]
