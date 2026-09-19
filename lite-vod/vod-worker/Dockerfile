FROM eclipse-temurin:17-jre-jammy

# Worker 后续需要调用 FFmpeg 进行转码，因此在镜像内预装 ffmpeg
RUN sed -i \
      -e 's#http://archive.ubuntu.com/ubuntu#https://mirrors.aliyun.com/ubuntu#g' \
      -e 's#https://archive.ubuntu.com/ubuntu#https://mirrors.aliyun.com/ubuntu#g' \
      -e 's#http://security.ubuntu.com/ubuntu#https://mirrors.aliyun.com/ubuntu#g' \
      -e 's#https://security.ubuntu.com/ubuntu#https://mirrors.aliyun.com/ubuntu#g' \
      /etc/apt/sources.list \
    && apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY target/vod-worker-*.jar app.jar

EXPOSE 8081

ENTRYPOINT ["java", "-jar", "app.jar"]
