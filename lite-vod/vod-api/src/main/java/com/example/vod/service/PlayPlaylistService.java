package com.example.vod.service;

import com.example.vod.common.storage.MinioStorage;
import com.example.vod.gateway.HlsPlaylistRewriter;
import com.example.vod.gateway.PlayPathSupport;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * 验签通过后读取 m3u8 并改写媒体 URI（补 e/exper/sign），供 Filter 与 Nginx 反代共用。
 */
@Service
public class PlayPlaylistService {

    private final MinioStorage minioStorage;

    public PlayPlaylistService(MinioStorage minioStorage) {
        this.minioStorage = minioStorage;
    }

    public record RewrittenPlaylist(byte[] body, String contentType) {
    }

    /**
     * @return empty 表示对象不存在；调用方需已完成验签
     */
    public Optional<RewrittenPlaylist> loadRewritten(PlayPathSupport.HlsRequest hls,
                                                     String eParam,
                                                     String sign,
                                                     String experParam) {
        try (InputStream in = minioStorage.openStream(hls.objectKey())) {
            if (in == null) {
                return Optional.empty();
            }
            String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            int experVal = 0;
            try {
                experVal = experParam == null || experParam.isBlank() ? 0 : Integer.parseInt(experParam);
            } catch (NumberFormatException ignored) {
                // 验签已通过，此处仅拼 query
            }
            String query = "e=" + eParam + "&exper=" + Math.max(0, experVal) + "&sign=" + sign;
            byte[] rewritten = HlsPlaylistRewriter.appendQueryToMediaUris(body, query)
                    .getBytes(StandardCharsets.UTF_8);
            return Optional.of(new RewrittenPlaylist(rewritten, PlayPathSupport.contentType(hls.relativePath())));
        } catch (Exception e) {
            throw new IllegalStateException("load playlist failed: " + hls.objectKey(), e);
        }
    }
}
