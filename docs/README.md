# Lite VOD 文档目录

工程代码在仓库根目录的 `lite-vod/`，SQL 在 `sql/`。本文档全部收在 `docs/`。

## 阅读顺序

1. [点播与对象存储：前置知识](./03-点播与对象存储-前置知识.md)
2. [轻量版云点播实现文档](./04-轻量版云点播-Lite-VOD-实现文档.md)
3. [分步实现指南](./05-分步实现指南/00-阅读说明.md)（从 01 做到 15）
4. 实现对象存储时对照 [MinIO Java SDK 使用说明](./MinIO-Java-SDK.md)
5. 实现 Worker 转码时对照 [Java 调用命令行程序使用指南](./Java-ProcessBuilder.md)
6. 实现 HLS / 封面回传时对照 [Worker 写回 MinIO 说明](./Worker-写回MinIO.md)
7. 实现播放签名 / 验签时对照 [密码学：开发实用指南](./密码学-开发实用指南.md)

## 文档一览

| 文档 | 用途 |
| --- | --- |
| [前置知识](./03-点播与对象存储-前置知识.md) | 点播、对象存储、预签名、HLS |
| [实现文档](./04-轻量版云点播-Lite-VOD-实现文档.md) | 架构、表结构、API 契约 |
| [分步指南](./05-分步实现指南/00-阅读说明.md) | 一步一验收 |
| [CI/CD 分步](./05-分步实现指南/17-CI与CD流水线/00-阅读说明.md) | GitLab → Harbor → Compose 流水线拆步 |
| [核心架构图](./06-核心架构图.md) | 全景与时序 |
| [tj-media 说明](./07-天机学堂-tj-media实现说明.md) | 业务对接 |
| [腾讯云 VOD 总览](./08-腾讯云点播-VOD-开发文档总览.md) | 云厂商对照 |
| [Demo 清单](./09-首期Demo清单.md) | 首期验收范围 |
| [扩展学习](./10-扩展学习与实习准备.md) | 知识补齐与实习 |
| [MinIO Java SDK](./MinIO-Java-SDK.md) | SDK 用法与本项目封装 |
| [Java 调用命令行](./Java-ProcessBuilder.md) | ProcessBuilder 调 ffmpeg / ffprobe |
| [Worker 写回 MinIO](./Worker-写回MinIO.md) | 转码产物 uploadObject / Content-Type / 落库 |
| [密码学实用指南](./密码学-开发实用指南.md) | 哈希 / HMAC / 加解密 / 编码与密钥，对照播放签名 |
