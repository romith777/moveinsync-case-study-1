# Multi-stage build for MoveInSync Distance Reconstruction Service
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /app

# Copy maven wrapper and build descriptor
COPY pom.xml .
COPY src ./src

# Install maven and compile application package
RUN apt-get update && apt-get install -y maven && mvn clean package -DskipTests

# Runtime image
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

EXPOSE 8080
COPY --from=build /app/target/distance-reconstruction-1.0.0.jar app.jar

ENV JAVA_OPTS="-Xms512m -Xmx1024m -XX:+UseG1GC"
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
