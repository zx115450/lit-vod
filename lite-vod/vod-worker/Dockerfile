FROM eclipse-temurin:17-jre-jammy

# Worker 后续需要调用 FFmpeg 进行转码，因此在镜像内预装 ffmpeg
RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY target/vod-worker-*.jar app.jar

EXPOSE 8081

ENTRYPOINT ["java", "-jar", "app.jar"]
