package com.example.vod.controller.dto;

import java.util.List;

/**
 * 通用分页结果。
 */
public record PageResult<T>(
        List<T> records,
        long total,
        int pageNo,
        int pageSize,
        int pages
) {
}
