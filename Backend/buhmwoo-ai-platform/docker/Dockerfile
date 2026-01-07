FROM eclipse-temurin:21-jre
WORKDIR /app
RUN apt-get update \
    && apt-get install -y libreoffice \
    && rm -rf /var/lib/apt/lists/*
COPY demo-0.0.1-SNAPSHOT.jar /app/app.jar
COPY libs/ /app/libs/
ENV TZ=Asia/Seoul
EXPOSE 8000
ENTRYPOINT ["java","-cp","/app/app.jar:/app/libs/*","org.springframework.boot.loader.launch.JarLauncher"]
