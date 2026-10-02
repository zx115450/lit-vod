package com.example.vod.controller.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 确认上传请求。
 *
 * @param progressive     是否渐进式转码；{@code null} 时回退到 {@code vod.abr.progressive-enabled}。
 *                        仅 VIDEO 有效。
 * @param previewSeconds  试看秒数；{@code null} 用配置；{@code <=0} 不生成试看。仅 VIDEO 有效。
 * @param assetType       可选；须与上传时落库的类型一致；缺省以库中为准。
 * @param splitRule       切章规则 {@code MARKDOWN}|{@code TXT_CHAPTER}；仅 DOCUMENT 有效。
 */
public record CommitMediaRequest(
        @NotBlank String fileId,
        @NotBlank String filename,
        Boolean progressive,
        Integer previewSeconds,
        String assetType,
        String splitRule
) {
}
