# 基础镜像已预装 ffmpeg，见 Dockerfile.base
# 地址：192.168.150.104:8089/lite-vod/worker-base:ffmpeg
FROM 192.168.150.104:8089/lite-vod/worker-base:ffmpeg

WORKDIR /app
COPY target/vod-worker-*.jar app.jar

EXPOSE 8081

ENTRYPOINT ["java", "-jar", "app.jar"]
