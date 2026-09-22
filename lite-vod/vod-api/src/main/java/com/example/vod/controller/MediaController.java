package com.example.vod.controller;

import com.example.vod.controller.dto.AbortMultipartRequest;
import com.example.vod.controller.dto.CommitMediaRequest;
import com.example.vod.controller.dto.CompleteMultipartRequest;
import com.example.vod.controller.dto.MediaDto;
import com.example.vod.controller.dto.MultipartUploadRequest;
import com.example.vod.controller.dto.MultipartUploadSignatureResponse;
import com.example.vod.controller.dto.PageResult;
import com.example.vod.controller.dto.PlaySignatureResponse;
import com.example.vod.controller.dto.UploadSignatureResponse;
import com.example.vod.service.MediaService;
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

/**
 * 媒资相关接口。
 */
@RestController
@RequestMapping("/vod")
public class MediaController {

    private final UploadSignatureService uploadSignatureService;
    private final MediaService mediaService;
    private final PlaySignatureService playSignatureService;

    public MediaController(UploadSignatureService uploadSignatureService,
                           MediaService mediaService,
                           PlaySignatureService playSignatureService) {
        this.uploadSignatureService = uploadSignatureService;
        this.mediaService = mediaService;
        this.playSignatureService = playSignatureService;
    }

    /**
     * 申请直传 MinIO 的上传凭证。
     * 管理端需登录；首期演示可暂不加登录，但预留 Authorization Header 注释便于后续扩展。
     */
    @GetMapping("/signature/upload")
    public UploadSignatureResponse uploadSignature() {
        // TODO: 接入管理端鉴权，从 Authorization Header 解析当前用户
        return uploadSignatureService.create();
    }

    /**
     * 二期：申请 multipart 分片上传凭证。
     * <p>需 {@code VOD_UPLOAD_MULTIPART_ENABLED=true}，否则返回 501。
     * 返回 fileId + uploadId + 各片预签名 URL（一次发齐）。
     */
    @PostMapping("/signature/upload/multipart")
    public MultipartUploadSignatureResponse multipartUploadSignature(@Valid @RequestBody MultipartUploadRequest request) {
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
     * 仅转码完成（FINISHED）的媒资可签发；exper 为试看秒数，不传视为 0。
     */
    @GetMapping("/signature/play")
    public PlaySignatureResponse playSignature(
            @RequestParam String fileId,
            @RequestParam(required = false, defaultValue = "0") int exper) {
        return playSignatureService.sign(fileId, exper);
    }

    /**
     * 确认直传完成，写入 filename、size，并把状态推进为 UPLOADED。
     */
    @PostMapping("/medias")
    public MediaDto commitMedia(@Valid @RequestBody CommitMediaRequest request) {
        // TODO: 接入管理端鉴权，校验当前用户是否有权操作该 fileId
        return mediaService.commit(request.fileId(), request.filename(), request.progressive());
    }

    /**
     * 按 fileId 查询媒资详情。
     */
    @GetMapping("/medias/{fileId}")
    public MediaDto detail(@PathVariable String fileId) {
        return mediaService.detail(fileId);
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
