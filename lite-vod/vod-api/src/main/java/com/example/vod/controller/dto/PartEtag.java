package com.example.vod.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * 单个分片的完成信息。
 *
 * @param partNumber 分片编号，从 1 开始
 * @param etag       该片 PUT 成功后响应头里的 ETag，应原样（含引号）
 */
public record PartEtag(
        @Positive int partNumber,
        @NotBlank String etag
) {
}
