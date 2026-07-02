FROM amazoncorretto:17-al2023-headless

WORKDIR /app

COPY build/libs/*-SNAPSHOT.jar app.jar

ENTRYPOINT ["java", "-jar", "app.jar"]