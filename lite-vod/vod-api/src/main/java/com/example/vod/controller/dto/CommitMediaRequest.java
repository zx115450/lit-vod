package com.example.vod.controller.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 确认上传请求。
 *
 * @param progressive 是否渐进式转码；{@code null} 时回退到 {@code vod.abr.progressive-enabled}。
 *                    {@code true}=先出低档可播再补档；{@code false}=一次出齐。
 */
public record CommitMediaRequest(
        @NotBlank String fileId,
        @NotBlank String filename,
        Boolean progressive
) {
}
