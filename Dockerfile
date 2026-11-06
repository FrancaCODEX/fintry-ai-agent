FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B dependency:go-offline
COPY src src
RUN ./mvnw -B verify

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN groupadd --system fintry && useradd --system --gid fintry fintry
COPY --from=build --chown=fintry:fintry /app/target/fintry-ai-agent.jar app.jar
USER fintry
EXPOSE 8092
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
