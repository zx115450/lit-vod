# 腾讯云点播（VOD）开发文档总览

> 依据官方文档中心整理：[云点播简介 / 操作指南 / 开发指南](https://cloud.tencent.com/document/product/266)。  
> 面向要对接腾讯云 VOD，或对照本仓库 Lite VOD 理解「云厂商完整能力」的读者。  
> 官方文档会改版，接口与计费以控制台和 API 文档为准。

开发时抓住这一条主线即可：

**客户端不持有密钥 → 服务端签发上传 / 播放凭证 → 媒资用 `FileId` 贯穿全链路 → 处理异步完成靠事件通知 → 播放用 `appId + fileId + psign`。**

---

## 一、产品定位

腾讯云点播（Video on Demand，VOD）面向音视频、图片，提供制作上传、存储、转码、媒体处理、媒体 AI、加速分发播放、版权保护等一体化服务。它不是单纯的对象存储，而是媒资平台。

| 模块 | 做什么 |
| --- | --- |
| 媒体上传 | 控制台、服务端、客户端、URL 拉取、直播录制 |
| 媒资管理 | `FileId`、分类、标签、过期删除、检索、存储分层 |
| 媒体处理 | 转码、自适应码流、截图、水印、审核、AI、AIGC |
| 分发播放 | CDN 加速、超级播放器、Key 防盗链、DRM、试看 |
| 事件通知 | 上传完成、任务流完成等异步结果回传 |

开通后默认分配重庆存储地域（`ap-chongqing`）。其他地域可再开，开通后不能关闭。

2023 年 12 月 25 日后开通的账号，访问资源时必须带应用 ID（`SubAppId`）。

---

## 二、必须先理解的概念

| 概念 | 含义 |
| --- | --- |
| `AppId` | 腾讯云账号的点播应用 ID |
| `SubAppId` / `vodSubAppId` | 点播「应用」（类似多租户）。不填、填 `0` 或填主 `AppId` = 默认应用 |
| `FileId` | 上传成功后平台分配的全局唯一媒资 ID；查询、转码、播放、删除都靠它 |
| `SecretId` / `SecretKey` | 云 API 密钥，只能放服务端，绝不能下发到浏览器 / App |
| `Procedure` | 任务流模板名。上传时带上，上传完成后自动转码 / 截图 / 审核 |
| `VodSessionKey` | 申请上传后的会话密钥，确认上传（`CommitUpload`）时要带回去 |
| `psign` | 播放器签名，服务端用播放密钥签发，播放器凭它拉流 |
| API 域名 | `vod.tencentcloudapi.com` |
| API 版本 | 常用 `2018-07-17`，鉴权走 TC3-HMAC-SHA256 |

---

## 三、最快上手路径（控制台）

官方快速入门是这 5 步，适合先验证账号和计费是否打通：

1. 注册并实名，开通云点播
2. 控制台「媒资管理」本地上传（可先选「只上传，不处理」）
3. 可选：创建水印模板
4. 对已上传文件发起转码（选预置模板，例如 `100010` 对应 360P、`100020` 对应 540P）
5. 在媒资详情里复制转码后的播放 URL，浏览器直接打开

业务系统不要停在这一步，要走 SDK / API。

官方文档：[快速入门](https://cloud.tencent.com/document/product/266/8757)

---

## 四、媒体上传

官方文档：[媒体上传综述](https://cloud.tencent.com/document/product/266/9760)

### 4.1 六种上传方式

| 方式 | 适用场景 |
| --- | --- |
| 控制台本地上传 | 少量人工上传 |
| 控制台 URL 拉取 | 少量从公网地址导入 |
| 服务端 SDK 上传 | 后台已有文件，要自动化入库。有 Java / C# / PHP / Python / Node.js / Go SDK |
| 客户端 SDK 上传 | UGC / PGC：用户从 Web、App、小程序、Flutter 直传 |
| API `PullUpload` | 大批量 URL 迁移 |
| 直播录制 | 直播流归档、剪辑、回看 |

### 4.2 服务端上传

官方文档：[服务端上传指引](https://cloud.tencent.com/document/product/266/9759)

官方强烈建议用上传 SDK，不要手写分片。

手写 API 时是三步：

1. `ApplyUpload`：申请上传，拿到存储桶、对象键、临时密钥、`VodSessionKey`
2. 把文件传到点播底层存储（大文件要自己做分片）
3. `CommitUpload`：用 `VodSessionKey` 确认上传，拿到 `FileId` 和播放地址

`ApplyUpload` 关键参数：`MediaType`（如 `mp4`）、可选 `Procedure`、存储地域、封面类型、过期时间。

### 4.3 客户端直传（业务最常见）

密钥不能给前端，所以流程是：

```text
客户端 → 业务后台申请上传签名
客户端 SDK 带签名直传 VOD
SDK 内部自动完成 ApplyUpload → 传文件 → CommitUpload
上传完成 → 事件通知带回 FileId / SourceContext
```

Web SDK 典型用法：构造 `TcVod`，传入 `getSignature()`，再调用 `tcVod.upload({ mediaFile }).done()`。

官方文档：

- [客户端上传指引](https://www.tencentcloud.com/zh/document/product/266/33921)
- [客户端上传签名](https://cloud.tencent.com/document/product/266/9221)
- [Web 端上传 SDK](https://cloud.tencent.com/document/product/266/9239)

上传签名（服务端生成）核心字段：

| 字段 | 作用 |
| --- | --- |
| `secretId` | 云 API SecretId |
| `currentTimeStamp` / `expireTime` | 有效期，最长 90 天 |
| `random` | 防重放 |
| `procedure` | 上传后自动跑任务流 |
| `classId` | 分类 |
| `sourceContext` | 透传业务信息（用户 ID、课程 ID），回调原样返回 |
| `oneTimeValid` | `1` 表示签名只能用一次，更安全，失败必须重新领签 |
| `vodSubAppId` | 目标应用 ID |

Web SDK 还支持断点续传、封面同传、分片大小（默认 8MB）、园区竞速。

### 4.4 上传时可顺带做的事

- 指定过期时间：到期自动删源文件和转码产物
- 指定任务流：上传完自动转码 / 截封面 / 审核
- 指定分类、封面、存储地域
- `sourceContext` 把业务主键带到回调里

---

## 五、媒体处理

处理是异步任务。两种发起方式：

1. **显式发起**：`ProcessMedia`（按任务参数）或 `ProcessMediaByProcedure`（按任务流模板名）
2. **上传时自动发起**：上传参数 / 签名里带 `Procedure`

官方文档：[如何对视频进行转码](https://cloud.tencent.com/document/product/266/45688)、[ProcessMedia](https://cloud.tencent.com/document/product/266/33427)

### 5.1 ProcessMedia 能做什么

- 转码（可加水印）
- 转动图
- 指定时间点截图、采样截图、雪碧图、封面图
- 转自适应码流（HLS / DASH，可加密）
- 内容审核（更推荐 `ReviewAudioVideo` / `ReviewImage`）
- 内容分析（标签、分类、封面；HLS 暂不支持）
- 内容识别（片头片尾、人脸、OCR、ASR、物体）

输入用 `FileId`（或存储路径）。`OutputAsIndependentMedia=ON` 时，转码结果会生成新的 `FileId`（会多一份存储费）。

### 5.2 任务流模板

`CreateProcedureTemplate` 把「转码 + 截图 + 审核」打包成一个名字，例如 `LongVideoPreset`。上限约 50 个。上传或 `ProcessMediaByProcedure` 时只传这个名字。

自适应码流用 `CreateAdaptiveDynamicStreamingTemplate`：格式 HLS / DASH，最多 10 路子流，可配 DRM（FairPlay 等）。这是多码率流畅播放的标准做法。

### 5.3 转码规格

- 预置模板：如 `STD-H264-MP4-360P`（ID `100010`）、`540P`（`100020`）
- 自定义模板：分辨率、码率、编码、水印
- 极速高清：更小体积、更好观感，按更高规格计费

---

## 六、媒资管理

上传成功后，业务库通常只存 `FileId`，细节随时用 API 查。

| 接口 | 用途 |
| --- | --- |
| `DescribeMediaInfos` | 按 `FileId` 拉详情。一次最多 20 个。可用 `Filters` 只要 `basicInfo` / `metaData` / `transcodeInfo` / `adaptiveDynamicStreamingInfo` 等 |
| `SearchMedia` | 按名称、分类、标签、来源、存储地域、过期时间等检索 |
| `ModifyMediaInfo` | 改名称、描述、分类、标签、封面、打点、字幕、过期时间 |
| `DeleteMedia` | 删媒资及附属文件 |
| `CreateClass` / `ModifyClass` / `DeleteClass` / `DescribeAllClass` | 分类树。一个文件只能属于一个分类 |

`DescribeMediaInfos` 的 `Filters` 建议按需取，避免每次拉全量产物列表。

一个媒体文件只能属于一个分类。`SearchMedia` 指定父分类时，会带上其子分类下的媒体。

---

## 七、播放与安全

### 7.1 两种播放方式

1. **直接播 URL**：媒资详情或转码结果里的地址。适合内网或已做 Key 防盗链的场景
2. **FileId 播放（推荐）**：播放器传入 `appId + fileId + psign`。超级播放器会自己解析该播哪路流

超级播放器覆盖 Web（TCPlayer）、iOS、Android、Flutter。Web 示例：

```javascript
TCPlayer("player-container-id", {
  fileID: "387xxxxx",
  appID: "1400329073",
  psign: "eyJhbGciOi..."
});
```

`contentInfo` 指定播哪一种内容：

- 自适应码流（可加密 / 未加密）
- 某路转码输出
- 原始上传文件

播放器本身还要单独申请 License。

### 7.2 播放器签名 psign

官方文档：[播放器签名](https://cloud.tencent.com/document/product/266/45554)

由业务后台签发，本质是 JWT 风格令牌：Header + Payload，用「默认分发配置」里的播放密钥做 HMAC-SHA256。

Payload 必填：

- `appId`、`fileId`
- `contentInfo`（播什么）
- `currentTimeStamp`
- 建议填 `expireTimeStamp`（不填则不过期，不安全）

可选：

- `urlAccessInfo`：Key 防盗链参数 `t` / `exper` / `rlimit` / `us`
- `drmLicenseInfo`：DRM License
- `ghostWatermarkInfo`：幽灵水印

终端只在签名有效期内能播。常见播放错误：

| 错误 | 含义 |
| --- | --- |
| `1009` | psign 校验失败 |
| `1008` | 缺少防盗链信息 |
| `403` | 防盗链鉴权失败 |
| `1001` | 视频文件不存在 |
| `1005` | 没有找到可播放的自适应码流 |

### 7.3 Key 防盗链

官方文档：[Key 防盗链](https://www.tencentcloud.com/zh/document/product/266/33986)

开启后，CDN 校验 URL 查询参数。签名计算：

```text
sign = md5(KEY + Dir + t + exper + rlimit + us + ...)
```

| 参数 | 含义 |
| --- | --- |
| `KEY` | 控制台开启防盗链时填写的密钥，8～20 位字母数字，只放服务端 |
| `Dir` | 原始 URL 的 PATH 中除去文件名的那部分路径 |
| `t` | 过期时间，Unix 时间的十六进制 |
| `exper` | 试看秒数，至少 30 秒；`0` 或不填 = 完整播放 |
| `rlimit` | 允许的不同 IP 数 |
| `us` | 链接唯一标识，防拷贝 URL |

Query 参数顺序必须是：`t`、`exper`、`rlimit`、`us`、`sign`。过期或签名错会返回 403。过期时间不要太短，并预留约 5 分钟时钟误差。

---

## 八、事件通知

官方文档：[事件通知综述](https://cloud.tencent.com/document/product/266/33779)

上传、转码、删除都是异步的，要用回调把状态写回业务库。

### 8.1 两类回调

| 模式 | 机制 | 可靠性 |
| --- | --- | --- |
| 普通回调 | 点播 HTTP POST JSON 到你的 URL | 可能丢，适合演示 |
| 可靠回调 | 你轮询 `PullEvents`，处理完再 `ConfirmEvents` | 更高，生产建议用这个 |

可靠回调要点：

- 长轮询，最多挂起 5 秒，客户端超时建议 10 秒
- 每次最多 16 条
- 拉到后必须在 30 秒内确认，否则会再投递
- 未消费事件最多保留约 4 天

### 8.2 开发最常接的两种事件

| EventType | 含义 | 关键字段 |
| --- | --- | --- |
| `NewFileUpload` | 上传完成 | `FileId`、`MediaUrl`、`SourceContext`、`ProcedureTaskId` |
| `ProcedureStateChanged` | 任务流状态变化 | `Status=FINISH` 时带转码 URL、分辨率、码率等 |

控制台「回调设置」里勾选需要的事件。上传时带的 `sourceContext` / `SessionContext` 会原样回来，用来把回调对上业务订单。

其他常见事件：URL 拉取完成、视频删除完成、音视频审核完成、视频编辑 / 合成 / 拆分完成等。

---

## 九、服务端 API 速查

公共约定：HTTPS，推荐 POST + `application/json` + 签名 v3。包体不要超过 1MB。

| 分类 | 接口 |
| --- | --- |
| 上传 | `ApplyUpload`、`CommitUpload`、`PullUpload` |
| 处理 | `ProcessMedia`、`ProcessMediaByProcedure` |
| 任务流 / 模板 | `CreateProcedureTemplate`、`CreateAdaptiveDynamicStreamingTemplate` 及各类转码 / 水印 / 截图模板接口 |
| 媒资 | `DescribeMediaInfos`、`SearchMedia`、`ModifyMediaInfo`、`DeleteMedia` |
| 分类 | `CreateClass`、`ModifyClass`、`DeleteClass`、`DescribeAllClass` |
| 事件 | `PullEvents`、`ConfirmEvents` |

常见错误：

| 错误码 | 含义 |
| --- | --- |
| `AuthFailure` | 签名 / 鉴权错误 |
| `FailedOperation.InvalidVodUser` | 没有开通点播业务 |
| `InvalidParameterValue.SubAppId` | 应用 ID 不对 |
| `InvalidParameterValue.VodSessionKey` | 上传会话无效 |
| `UnauthorizedOperation` | 未授权操作 |

Java 等语言优先用官方 SDK，不要手写签名。

---

## 十、SDK 地图

**上传**

- 服务端：Java / C# / PHP / Python / Node.js / Go
- 客户端：Android / iOS / Web（TcVod）/ 小程序 / Flutter

**播放**

- Web：TCPlayer（`fileID` + `appID` + `psign`）
- iOS / Android：超级播放器 `SuperPlayerView` + `SuperPlayerModel`
- Flutter：超级播放器

---

## 十一、计费（开发时就要知道）

官方文档：[计费概述](https://cloud.tencent.com/document/product/266/2838)

费用大致分：

1. **媒资管理 / 存储**：按存储类型和日峰值容量。标准 / 低频（至少 30 天）/ 归档（90 天）/ 深度归档（180 天）。提前删也按最短时长计
2. **媒体处理**：按输出编码、分辨率、时长；不足 1 分钟按 1 分钟。失败不收费。自适应码流按内部每一路转码分别计
3. **加速分发（CDN）**：播放流量
4. **其他**：数据取回、审核、DRM、智能媒资等

可用后付费或资源包。买了资源包仍可能出费用，通常是用量超出或计费项不在包内。

境内标准存储刊例价约 `0.0048` 元 / GB / 日，境外更高。具体以官网报价为准。

---

## 十二、推荐的业务接入架构

```text
管理端 / 学员端
    │ ① 登录后向业务 API 申请上传签名
    ▼
业务后端（持有 SecretId / SecretKey、播放密钥）
    │ ② 签发上传签名 / 播放 psign
    │ ③ 调 ProcessMedia 或只依赖 Procedure
    │ ④ PullEvents / 接收回调，更新媒资状态
    ▼
腾讯云点播
    上传存储 → 转码 / HLS → CDN 分发
    │
    ▼
播放器：appId + fileId + psign（不要把永久裸 URL 给前端）
```

安全底线：

- `SecretKey`、防盗链 `KEY`、播放密钥只在服务端
- 上传签名设短过期，必要时 `oneTimeValid=1`
- 播放签名设 `expireTimeStamp`，需要时加 `rlimit`、`us`
- 生产用可靠回调，并用 `sourceContext` 关联业务单号

---

## 十三、和本仓库 Lite VOD 的对照

Lite VOD 实现的是腾讯云 VOD 的常用子集。对照关系：

| 腾讯云 VOD | 本仓库 Lite VOD |
| --- | --- |
| 客户端上传签名 | [05-申请上传凭证](./05-分步实现指南/05-申请上传凭证.md) |
| `CommitUpload` | [06-确认上传与媒资落库](./05-分步实现指南/06-确认上传与媒资落库.md) |
| `DescribeMediaInfos` / `SearchMedia` | [07-媒资查询](./05-分步实现指南/07-媒资查询.md) |
| `ProcessMedia` + 任务流 | [08-转码任务投递](./05-分步实现指南/08-转码任务投递.md)、[09-Worker 转码闭环](./05-分步实现指南/09-Worker转码闭环.md) |
| `psign` | [10-播放签名签发](./05-分步实现指南/10-播放签名签发.md) |
| Key 防盗链 | [11-播放网关验签](./05-分步实现指南/11-播放网关验签.md) |
| 事件通知 | [13-事件通知](./05-分步实现指南/13-事件通知.md) |
| `DeleteMedia` | [14-删除媒资](./05-分步实现指南/14-删除媒资.md) |

官方多出来、首期可不做的能力：自适应 HLS 多码率、DRM、AI 审核、存储分层。这些放到 [16-增强与对接天机学堂](./05-分步实现指南/16-增强与对接天机学堂.md)。

概念铺垫见 [点播与对象存储：前置知识](./03-点播与对象存储-前置知识.md)。实现细节见 [Lite VOD 实现文档](./04-轻量版云点播-Lite-VOD-实现文档.md)。

---

## 十四、官方文档索引

| 主题 | 文档 |
| --- | --- |
| 文档首页 | [product/266](https://cloud.tencent.com/document/product/266) |
| 快速入门 | [8757](https://cloud.tencent.com/document/product/266/8757) |
| 上传综述 | [9760](https://cloud.tencent.com/document/product/266/9760) |
| 服务端上传 | [9759](https://cloud.tencent.com/document/product/266/9759) |
| 客户端上传签名 | [9221](https://cloud.tencent.com/document/product/266/9221) |
| Web 上传 SDK | [9239](https://cloud.tencent.com/document/product/266/9239) |
| 转码实践 | [45688](https://cloud.tencent.com/document/product/266/45688) |
| ProcessMedia | [33427](https://cloud.tencent.com/document/product/266/33427) |
| 任务流模板 | [33897](https://cloud.tencent.com/document/product/266/33897) |
| 事件通知综述 | [33779](https://cloud.tencent.com/document/product/266/33779) |
| 拉取事件通知 | [33433](https://cloud.tencent.com/document/product/266/33433) |
| 播放器签名 | [45554](https://cloud.tencent.com/document/product/266/45554) |
| Key 防盗链 | [国际站 33986](https://www.tencentcloud.com/zh/document/product/266/33986) |
| 获取媒体详细信息 | [31763](https://cloud.tencent.com/document/product/266/31763) |
| 计费概述 | [2838](https://cloud.tencent.com/document/product/266/2838) |
