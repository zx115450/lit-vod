# 基础镜像已放到 Harbor，避免流水线每次从外网拉取。
# 地址：192.168.150.104:8089/lite-vod/temurin:17-jre-jammy
FROM 192.168.150.104:8089/lite-vod/temurin:17-jre-jammy

WORKDIR /app
COPY target/vod-api-*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
