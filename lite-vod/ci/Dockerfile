# CI 构建镜像：Maven 3.9、JDK 17、docker 命令都已装好。
# 地址：192.168.150.104:8089/lite-vod/ci-maven:3.9-17
# 流水线直接使用，不必每次 apt 安装 curl 再下载 docker。
FROM maven:3.9-eclipse-temurin-17

RUN set -eux; \
    if [ -f /etc/apt/sources.list ]; then \
      sed -i \
        -e 's#http://archive.ubuntu.com/ubuntu#https://mirrors.aliyun.com/ubuntu#g' \
        -e 's#https://archive.ubuntu.com/ubuntu#https://mirrors.aliyun.com/ubuntu#g' \
        -e 's#http://security.ubuntu.com/ubuntu#https://mirrors.aliyun.com/ubuntu#g' \
        -e 's#https://security.ubuntu.com/ubuntu#https://mirrors.aliyun.com/ubuntu#g' \
        /etc/apt/sources.list; \
    fi; \
    if [ -f /etc/apt/sources.list.d/ubuntu.sources ]; then \
      sed -i \
        -e 's#http://archive.ubuntu.com/ubuntu#https://mirrors.aliyun.com/ubuntu#g' \
        -e 's#https://archive.ubuntu.com/ubuntu#https://mirrors.aliyun.com/ubuntu#g' \
        -e 's#http://security.ubuntu.com/ubuntu#https://mirrors.aliyun.com/ubuntu#g' \
        -e 's#https://security.ubuntu.com/ubuntu#https://mirrors.aliyun.com/ubuntu#g' \
        /etc/apt/sources.list.d/ubuntu.sources; \
    fi; \
    apt-get update; \
    apt-get install -y --no-install-recommends ca-certificates curl; \
    curl -fsSL https://mirrors.aliyun.com/docker-ce/linux/static/stable/x86_64/docker-24.0.9.tgz -o /tmp/docker.tgz \
      || curl -fsSL https://download.docker.com/linux/static/stable/x86_64/docker-24.0.9.tgz -o /tmp/docker.tgz; \
    tar xzf /tmp/docker.tgz -C /tmp; \
    cp /tmp/docker/docker /usr/local/bin/docker; \
    chmod +x /usr/local/bin/docker; \
    rm -rf /tmp/docker /tmp/docker.tgz /var/lib/apt/lists/*; \
    docker --version
