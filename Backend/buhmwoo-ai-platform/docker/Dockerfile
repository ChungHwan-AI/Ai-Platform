# buhmwoo-ai-platform/docker/Dockerfile
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY target/demo-0.0.1-SNAPSHOT.jar /app/app.jar
COPY target/libs/ /app/libs/
ENV TZ=Asia/Seoul
EXPOSE 8000
ENTRYPOINT ["java","-cp","/app/app.jar:/app/libs/*","org.springframework.boot.loader.launch.JarLauncher"]

