package com.example.vod.controller;

import com.example.vod.common.domain.media.AssetType;
import com.example.vod.common.domain.media.SplitRule;
import com.example.vod.controller.dto.AbortMultipartRequest;
import com.example.vod.controller.dto.CommitMediaRequest;
import com.example.vod.controller.dto.CompleteMultipartRequest;
import com.example.vod.controller.dto.MediaDto;
import com.example.vod.controller.dto.MultipartUploadRequest;
import com.example.vod.controller.dto.MultipartUploadSignatureResponse;
import com.example.vod.controller.dto.ObjectSignatureResponse;
import com.example.vod.controller.dto.PlaySignatureResponse;
import com.example.vod.controller.dto.UploadSignatureResponse;
import com.example.vod.service.InternalTokenValidator;
import com.example.vod.service.MediaService;
import com.example.vod.service.ObjectSignatureService;
import com.example.vod.service.PlaySignatureService;
import com.example.vod.service.UploadSignatureService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 书城 BFF 内部接口（方案 B）：用户/ADMIN 验权在书城，媒资只认 {@code X-Internal-Token}。
 *
 * <ul>
 *   <li>上传签发（整对象 / multipart）/ commit：书城管理端鉴权后调用</li>
 *   <li>object-url：书城读章鉴权后调用；字节由 BFF 直打 MinIO</li>
 *   <li>play-url：书城播放鉴权后调用；返回带 HMAC 的 HLS playUrl 给浏览器</li>
 * </ul>
 *
 * <p>公开 {@code /vod/signature/*} 仍保留给本地 debug。不影响 {@code /internal/play-auth}。
 */
@RestController
@RequestMapping("/internal/medias")
public class InternalMediaController {

    private final InternalTokenValidator tokenValidator;
    private final ObjectSignatureService objectSignatureService;
    private final PlaySignatureService playSignatureService;
    private final UploadSignatureService uploadSignatureService;
    private final MediaService mediaService;

    public InternalMediaController(InternalTokenValidator tokenValidator,
                                   ObjectSignatureService objectSignatureService,
                                   PlaySignatureService playSignatureService,
                                   UploadSignatureService uploadSignatureService,
                                   MediaService mediaService) {
        this.tokenValidator = tokenValidator;
        this.objectSignatureService = objectSignatureService;
        this.playSignatureService = playSignatureService;
        this.uploadSignatureService = uploadSignatureService;
        this.mediaService = mediaService;
    }

    /**
     * 生产上传：申请 PUT 预签名（浏览器仍直传 MinIO，凭证由书城代领）。
     */
    @GetMapping("/upload-signature")
    public UploadSignatureResponse uploadSignature(
            @RequestParam(value = "assetType", required = false) String assetType,
            @RequestHeader(value = InternalTokenValidator.HEADER, required = false) String token
    ) {
        tokenValidator.requireValid(token);
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
     * 生产分片上传：申请 multipart 凭证（body 同公开面）。
     * <p>需 {@code VOD_UPLOAD_MULTIPART_ENABLED=true}，否则 501。浏览器仍直传 MinIO 各片 URL。
     */
    @PostMapping("/upload-signature/multipart")
    public MultipartUploadSignatureResponse multipartUploadSignature(
            @Valid @RequestBody MultipartUploadRequest request,
            @RequestHeader(value = InternalTokenValidator.HEADER, required = false) String token
    ) {
        tokenValidator.requireValid(token);
        return uploadSignatureService.createMultipart(request);
    }

    /**
     * 生产分片上传：合并完成。成功后才能 {@code POST /internal/medias} commit。
     */
    @PostMapping("/uploads/{fileId}/complete")
    public void completeMultipart(
            @PathVariable String fileId,
            @Valid @RequestBody CompleteMultipartRequest request,
            @RequestHeader(value = InternalTokenValidator.HEADER, required = false) String token
    ) {
        tokenValidator.requireValid(token);
        uploadSignatureService.complete(fileId, request);
    }

    /**
     * 生产分片上传：中止会话，丢弃未完成分片。
     */
    @PostMapping("/uploads/{fileId}/abort")
    public void abortMultipart(
            @PathVariable String fileId,
            @Valid @RequestBody AbortMultipartRequest request,
            @RequestHeader(value = InternalTokenValidator.HEADER, required = false) String token
    ) {
        tokenValidator.requireValid(token);
        uploadSignatureService.abort(fileId, request);
    }

    /**
     * 生产 commit：直传完成后由书城调用，触发 DOCUMENT 切章 / VIDEO 转码。
     */
    @PostMapping
    public MediaDto commit(
            @Valid @RequestBody CommitMediaRequest request,
            @RequestHeader(value = InternalTokenValidator.HEADER, required = false) String token
    ) {
        tokenValidator.requireValid(token);
        return mediaService.commit(
                request.fileId(),
                request.filename(),
                request.progressive(),
                request.previewSeconds(),
                parseAssetType(request.assetType()),
                parseSplitRule(request.splitRule()));
    }

    /**
     * 生产读章：带 Token 签发预签名 GET URL。
     */
    @GetMapping("/{fileId}/object-url")
    public ObjectSignatureResponse objectUrl(
            @PathVariable String fileId,
            @RequestParam(required = false) Integer ttl,
            @RequestHeader(value = InternalTokenValidator.HEADER, required = false) String token
    ) {
        tokenValidator.requireValid(token);
        return objectSignatureService.sign(fileId, ttl);
    }

    /**
     * 生产播放签发：书城鉴权后调用，返回带 {@code e/exper/sign} 的 HLS playUrl。
     * <p>仅 VIDEO；非视频 / 未处理完成时与公开面相同 4xx。
     */
    @GetMapping("/{fileId}/play-url")
    public PlaySignatureResponse playUrl(
            @PathVariable String fileId,
            @RequestParam(required = false, defaultValue = "false") boolean preview,
            @RequestHeader(value = InternalTokenValidator.HEADER, required = false) String token
    ) {
        tokenValidator.requireValid(token);
        return playSignatureService.sign(fileId, preview);
    }

    private static AssetType parseAssetType(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return AssetType.fromParam(raw);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "unsupported assetType: " + raw);
        }
    }

    private static SplitRule parseSplitRule(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return SplitRule.fromParam(raw);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "unsupported splitRule: " + raw);
        }
    }
}
