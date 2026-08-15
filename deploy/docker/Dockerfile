# Token Limit Server 镜像
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY tokenlimit-java/pom.xml ./
COPY tokenlimit-java/tokenlimit-common ./tokenlimit-common
COPY tokenlimit-java/tokenlimit-server ./tokenlimit-server
COPY tokenlimit-java/tokenlimit-client-java ./tokenlimit-client-java
RUN mvn -pl tokenlimit-server -am clean package -DskipTests

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /build/tokenlimit-server/target/tokenlimit-server.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
