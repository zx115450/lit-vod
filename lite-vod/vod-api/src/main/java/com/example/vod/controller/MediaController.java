package com.example.vod.controller;

import com.example.vod.common.domain.media.AssetType;
import com.example.vod.common.domain.media.SplitRule;
import com.example.vod.controller.dto.AbortMultipartRequest;
import com.example.vod.controller.dto.ChaptersResponse;
import com.example.vod.controller.dto.CommitMediaRequest;
import com.example.vod.controller.dto.CompleteMultipartRequest;
import com.example.vod.controller.dto.MediaDto;
import com.example.vod.controller.dto.MultipartUploadRequest;
import com.example.vod.controller.dto.MultipartUploadSignatureResponse;
import com.example.vod.controller.dto.ObjectSignatureResponse;
import com.example.vod.controller.dto.PageResult;
import com.example.vod.controller.dto.PlaySignatureResponse;
import com.example.vod.controller.dto.UploadSignatureResponse;
import com.example.vod.service.MediaService;
import com.example.vod.service.ObjectSignatureService;
import com.example.vod.service.PlaySignatureService;
import com.example.vod.service.UploadSignatureService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 媒资相关接口。
 */
@RestController
@RequestMapping("/vod")
public class MediaController {

    private final UploadSignatureService uploadSignatureService;
    private final MediaService mediaService;
    private final PlaySignatureService playSignatureService;
    private final ObjectSignatureService objectSignatureService;

    public MediaController(UploadSignatureService uploadSignatureService,
                           MediaService mediaService,
                           PlaySignatureService playSignatureService,
                           ObjectSignatureService objectSignatureService) {
        this.uploadSignatureService = uploadSignatureService;
        this.mediaService = mediaService;
        this.playSignatureService = playSignatureService;
        this.objectSignatureService = objectSignatureService;
    }

    /**
     * 申请直传 MinIO 的上传凭证。
     * <p>本地 / debug 可用；生产由书城鉴权后调
     * {@code GET /internal/medias/upload-signature} + {@code X-Internal-Token}。
     *
     * @param assetType 资产类型，缺省 VIDEO；CHAPTER 不可上传
     */
    @GetMapping("/signature/upload")
    public UploadSignatureResponse uploadSignature(
            @RequestParam(value = "assetType", required = false) String assetType
    ) {
        // 公开面仅联调；生产走 internal（方案 B，验权在书城）
        AssetType type;
        try {
            type = AssetType.fromParam(assetType);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "unsupported assetType: " + assetType);
        }
        return uploadSignatureService.create(type);
    }

    /**
     * 二期：申请 multipart 分片上传凭证。
     * <p>需 {@code VOD_UPLOAD_MULTIPART_ENABLED=true}，否则返回 501。
     * 返回 fileId + uploadId + 各片预签名 URL（一次发齐）。
     */
    @PostMapping("/signature/upload/multipart")
    public MultipartUploadSignatureResponse multipartUploadSignature(
            @Valid @RequestBody MultipartUploadRequest request
    ) {
        // TODO: 接入管理端鉴权
        return uploadSignatureService.createMultipart(request);
    }

    /**
     * 二期：完成分片合并。
     * <p>客户端提交 uploadId + parts[{partNumber, etag}]，服务端调 MinIO CompleteMultipartUpload。
     * 成功后才能 POST /vod/medias（commit），HeadObject 才能通过。
     */
    @PostMapping("/uploads/{fileId}/complete")
    public void completeMultipart(@PathVariable String fileId,
                                  @Valid @RequestBody CompleteMultipartRequest request) {
        uploadSignatureService.complete(fileId, request);
    }

    /**
     * 二期：中止分片上传，丢弃未完成分片，避免桶内残留计费。
     */
    @PostMapping("/uploads/{fileId}/abort")
    public void abortMultipart(@PathVariable String fileId,
                               @Valid @RequestBody AbortMultipartRequest request) {
        uploadSignatureService.abort(fileId, request);
    }

    /**
     * 签发可播放 URL（HMAC-SHA256），对应步骤 10。
     * 仅可播媒资可签发；{@code preview=true} 时走试看（时长取媒资 previewSeconds，L2 绑 preview.m3u8）。
     */
    @GetMapping("/signature/play")
    public PlaySignatureResponse playSignature(
            @RequestParam String fileId,
            @RequestParam(required = false, defaultValue = "false") boolean preview
    ) {
        return playSignatureService.sign(fileId, preview);
    }

    /**
     * 签发 DOCUMENT / CHAPTER / IMAGE 的 MinIO 预签名 GET（L4）。
     * VIDEO → 400；调用方用返回 URL 直打桶，字节不过本服务。
     * <p>用途：管理 / debug。生产读章走 {@code /internal/medias/{fileId}/object-url} + Internal-Token。
     */
    @GetMapping("/signature/object")
    public ObjectSignatureResponse objectSignature(
            @RequestParam String fileId,
            @RequestParam(required = false) Integer ttl
    ) {
        // TODO: 接入管理端鉴权（方案 A：保留公开路径，后续加登录；勿把 URL 下发给未授权浏览器）
        return objectSignatureService.sign(fileId, ttl);
    }

    /**
     * 确认直传完成，写入 filename、size，按 assetType 分流建任务
     * （VIDEO→PROCEDURE，DOCUMENT→SPLIT_CHAPTER，IMAGE→THUMBNAIL）。
     * <p>本地 / debug 可用；生产由书城调 {@code POST /internal/medias} + Token。
     */
    @PostMapping("/medias")
    public MediaDto commitMedia(@Valid @RequestBody CommitMediaRequest request) {
        // 公开面仅联调；生产走 internal（方案 B）
        AssetType assetType = null;
        if (request.assetType() != null && !request.assetType().isBlank()) {
            try {
                assetType = AssetType.fromParam(request.assetType());
            } catch (IllegalArgumentException ex) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "unsupported assetType: " + request.assetType());
            }
        }
        SplitRule splitRule = null;
        if (request.splitRule() != null && !request.splitRule().isBlank()) {
            try {
                splitRule = SplitRule.fromParam(request.splitRule());
            } catch (IllegalArgumentException ex) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "unsupported splitRule: " + request.splitRule());
            }
        }
        return mediaService.commit(
                request.fileId(),
                request.filename(),
                request.progressive(),
                request.previewSeconds(),
                assetType,
                splitRule);
    }

    /**
     * 按 fileId 查询媒资详情。
     */
    @GetMapping("/medias/{fileId}")
    public MediaDto detail(@PathVariable String fileId) {
        return mediaService.detail(fileId);
    }

    /**
     * DOCUMENT 章目录：按 chapterNo 排序；PROCESSING / FAILED 时返回空数组便于轮询。
     */
    @GetMapping("/medias/{sourceFileId}/chapters")
    public ChaptersResponse chapters(@PathVariable String sourceFileId) {
        return mediaService.listChapters(sourceFileId);
    }

    /**
     * 分页查询媒资列表，支持按 filename 模糊过滤。
     */
    @GetMapping("/medias")
    public PageResult<MediaDto> list(
            @RequestParam(required = false, defaultValue = "1") int pageNo,
            @RequestParam(required = false, defaultValue = "10") int pageSize,
            @RequestParam(required = false) String name) {
        return mediaService.list(name, pageNo, pageSize);
    }

    /**
     * 步骤 14：删除媒资。
     * 不存在返回 404；处理中（PROCESSING 或存在 RUNNING 任务）返回 409；
     * 成功返回 204。先删 MinIO 对象再删库，对象删失败则接口失败。
     */
    @DeleteMapping("/medias/{fileId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String fileId) {
        // TODO: 接入管理端鉴权，校验当前用户是否有权操作该 fileId
        mediaService.delete(fileId);
    }
}
