package com.example.vod.controller;

import com.example.vod.controller.dto.AbortMultipartRequest;
import com.example.vod.controller.dto.CommitMediaRequest;
import com.example.vod.controller.dto.CompleteMultipartRequest;
import com.example.vod.controller.dto.MediaDto;
import com.example.vod.controller.dto.MultipartUploadRequest;
import com.example.vod.controller.dto.MultipartUploadSignatureResponse;
import com.example.vod.controller.dto.PageResult;
import com.example.vod.controller.dto.PartEtag;
import com.example.vod.controller.dto.PartUrl;
import com.example.vod.controller.dto.PlaySignatureResponse;
import com.example.vod.controller.dto.UploadSignatureResponse;
import com.example.vod.common.domain.media.AssetType;
import com.example.vod.common.domain.media.MediaStatus;
import com.example.vod.service.MediaService;
import com.example.vod.service.PlaySignatureService;
import com.example.vod.service.UploadSignatureService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(MediaController.class)
class MediaControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private UploadSignatureService uploadSignatureService;

    @MockBean
    private MediaService mediaService;

    @MockBean
    private PlaySignatureService playSignatureService;

    @MockBean
    private com.example.vod.service.ObjectSignatureService objectSignatureService;

    // PlayGatewayFilter / PlayPlaylistController 依赖，@WebMvcTest 会装载，需一并 mock
    @MockBean
    private com.example.vod.service.PlayAuthService playAuthService;

    @MockBean
    private com.example.vod.service.PlayPlaylistService playPlaylistService;

    @MockBean
    private com.example.vod.common.storage.MinioStorage minioStorage;

    @Test
    void uploadSignatureShouldReturnDto() throws Exception {
        when(uploadSignatureService.create(AssetType.VIDEO)).thenReturn(
                new UploadSignatureResponse(
                        "f7c2a1b0e9d84f6a",
                        "http://localhost:9000/vod/raw/f7c2a1b0e9d84f6a/source.mp4?X-Amz-Algorithm=...",
                        "raw/f7c2a1b0e9d84f6a/source.mp4",
                        1710000000L
                )
        );

        mockMvc.perform(get("/vod/signature/upload"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fileId").value("f7c2a1b0e9d84f6a"))
                .andExpect(jsonPath("$.uploadUrl").isString())
                .andExpect(jsonPath("$.objectKey").value("raw/f7c2a1b0e9d84f6a/source.mp4"))
                .andExpect(jsonPath("$.expireAt").value(1710000000));
    }

    @Test
    void uploadSignatureWithDocumentAssetTypeShouldDelegate() throws Exception {
        when(uploadSignatureService.create(AssetType.DOCUMENT)).thenReturn(
                new UploadSignatureResponse(
                        "docfileid00000000000000000000001",
                        "http://localhost:9000/vod/raw/doc/source.bin?X-Amz-Algorithm=...",
                        "raw/docfileid00000000000000000000001/source.bin",
                        1710000000L
                )
        );

        mockMvc.perform(get("/vod/signature/upload").param("assetType", "DOCUMENT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.objectKey").value("raw/docfileid00000000000000000000001/source.bin"));
    }

    @Test
    void uploadSignatureWithInvalidAssetTypeShouldReturn400() throws Exception {
        mockMvc.perform(get("/vod/signature/upload").param("assetType", "UNKNOWN"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void multipartUploadSignatureShouldReturnDto() throws Exception {
        when(uploadSignatureService.createMultipart(any(MultipartUploadRequest.class)))
                .thenReturn(new MultipartUploadSignatureResponse(
                        "f7c2a1b0e9d84f6a",
                        "raw/f7c2a1b0e9d84f6a/source.mp4",
                        "upload-id-abc",
                        10485760L,
                        3,
                        List.of(
                                new PartUrl(1, "http://minio/...?partNumber=1"),
                                new PartUrl(2, "http://minio/...?partNumber=2"),
                                new PartUrl(3, "http://minio/...?partNumber=3")
                        ),
                        1710000000L
                ));

        mockMvc.perform(post("/vod/signature/upload/multipart")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filename\":\"lesson01.mp4\",\"contentType\":\"video/mp4\","
                                + "\"contentLength\":26214400,\"partSize\":10485760}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fileId").value("f7c2a1b0e9d84f6a"))
                .andExpect(jsonPath("$.uploadId").value("upload-id-abc"))
                .andExpect(jsonPath("$.objectKey").value("raw/f7c2a1b0e9d84f6a/source.mp4"))
                .andExpect(jsonPath("$.partCount").value(3))
                .andExpect(jsonPath("$.parts[0].partNumber").value(1))
                .andExpect(jsonPath("$.parts[2].partNumber").value(3));
    }

    @Test
    void completeMultipartShouldReturn200() throws Exception {
        doNothing().when(uploadSignatureService)
                .complete(eq("f7c2a1b0e9d84f6a"), any(CompleteMultipartRequest.class));

        mockMvc.perform(post("/vod/uploads/f7c2a1b0e9d84f6a/complete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"uploadId\":\"uid\","
                                + "\"parts\":[{\"partNumber\":1,\"etag\":\"\\\"a\\\"\"},"
                                + "{\"partNumber\":2,\"etag\":\"\\\"b\\\"\"}]}"))
                .andExpect(status().isOk());
    }

    @Test
    void abortMultipartShouldReturn200() throws Exception {
        doNothing().when(uploadSignatureService)
                .abort(eq("f7c2a1b0e9d84f6a"), any(AbortMultipartRequest.class));

        mockMvc.perform(post("/vod/uploads/f7c2a1b0e9d84f6a/abort")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"uploadId\":\"uid\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void commitMediaShouldReturnDto() throws Exception {
        when(mediaService.commit(any(String.class), any(String.class), any(), any(), any(), any())).thenReturn(sampleMediaDto());

        mockMvc.perform(post("/vod/medias")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileId\":\"f7c2a1b0e9d84f6a\",\"filename\":\"lesson01.mp4\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fileId").value("f7c2a1b0e9d84f6a"))
                .andExpect(jsonPath("$.filename").value("lesson01.mp4"))
                .andExpect(jsonPath("$.size").value(1024))
                .andExpect(jsonPath("$.status").value("PROCESSING"))
                .andExpect(jsonPath("$.statusText").value("处理中"));

        verify(mediaService).commit("f7c2a1b0e9d84f6a", "lesson01.mp4", null, null, null, null);
    }

    @Test
    void commitMediaShouldPassProgressiveFlag() throws Exception {
        when(mediaService.commit(any(String.class), any(String.class), any(), any(), any(), any())).thenReturn(sampleMediaDto());

        mockMvc.perform(post("/vod/medias")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileId\":\"f7c2a1b0e9d84f6a\",\"filename\":\"lesson01.mp4\",\"progressive\":true}"))
                .andExpect(status().isOk());

        verify(mediaService).commit("f7c2a1b0e9d84f6a", "lesson01.mp4", true, null, null, null);
    }

    @Test
    void commitMediaShouldPassPreviewSeconds() throws Exception {
        when(mediaService.commit(any(String.class), any(String.class), any(), any(), any(), any())).thenReturn(sampleMediaDto());

        mockMvc.perform(post("/vod/medias")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileId\":\"f7c2a1b0e9d84f6a\",\"filename\":\"lesson01.mp4\",\"previewSeconds\":120}"))
                .andExpect(status().isOk());

        verify(mediaService).commit("f7c2a1b0e9d84f6a", "lesson01.mp4", null, 120, null, null);
    }

    @Test
    void commitMediaShouldPassDocumentAssetTypeAndSplitRule() throws Exception {
        when(mediaService.commit(any(String.class), any(String.class), any(), any(), any(), any())).thenReturn(sampleMediaDto());

        mockMvc.perform(post("/vod/medias")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileId\":\"doc1\",\"filename\":\"redis.md\",\"assetType\":\"DOCUMENT\",\"splitRule\":\"MARKDOWN\"}"))
                .andExpect(status().isOk());

        verify(mediaService).commit("doc1", "redis.md", null, null,
                AssetType.DOCUMENT, com.example.vod.common.domain.media.SplitRule.MARKDOWN);
    }

    @Test
    void detailShouldReturnDto() throws Exception {
        when(mediaService.detail("f7c2a1b0e9d84f6a")).thenReturn(sampleMediaDto());

        mockMvc.perform(get("/vod/medias/f7c2a1b0e9d84f6a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fileId").value("f7c2a1b0e9d84f6a"))
                .andExpect(jsonPath("$.statusText").value("处理中"))
                .andExpect(jsonPath("$.objectKey").value("raw/f7c2a1b0e9d84f6a/source.mp4"));
    }

    @Test
    void chaptersShouldReturnOrderedList() throws Exception {
        when(mediaService.listChapters("doc-source-1")).thenReturn(
                new com.example.vod.controller.dto.ChaptersResponse(
                        "doc-source-1",
                        List.of(
                                new com.example.vod.controller.dto.ChapterDto(
                                        1, "持久化", "chap-1", 120, AssetType.CHAPTER),
                                new com.example.vod.controller.dto.ChapterDto(
                                        2, "复制", "chap-2", 80, AssetType.CHAPTER)
                        )
                )
        );

        mockMvc.perform(get("/vod/medias/doc-source-1/chapters"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceFileId").value("doc-source-1"))
                .andExpect(jsonPath("$.chapters.length()").value(2))
                .andExpect(jsonPath("$.chapters[0].chapterNo").value(1))
                .andExpect(jsonPath("$.chapters[0].title").value("持久化"))
                .andExpect(jsonPath("$.chapters[0].fileId").value("chap-1"))
                .andExpect(jsonPath("$.chapters[0].wordCount").value(120))
                .andExpect(jsonPath("$.chapters[0].assetType").value("CHAPTER"))
                .andExpect(jsonPath("$.chapters[1].chapterNo").value(2));
    }

    @Test
    void chaptersShouldReturnEmptyWhileProcessing() throws Exception {
        when(mediaService.listChapters("doc-processing")).thenReturn(
                new com.example.vod.controller.dto.ChaptersResponse("doc-processing", List.of())
        );

        mockMvc.perform(get("/vod/medias/doc-processing/chapters"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chapters.length()").value(0));
    }

    @Test
    void listShouldReturnPageResult() throws Exception {
        when(mediaService.list(null, 1, 10)).thenReturn(
                new PageResult<>(List.of(sampleMediaDto()), 1, 1, 10, 1)
        );

        mockMvc.perform(get("/vod/medias"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.pageNo").value(1))
                .andExpect(jsonPath("$.pageSize").value(10))
                .andExpect(jsonPath("$.pages").value(1))
                .andExpect(jsonPath("$.records[0].fileId").value("f7c2a1b0e9d84f6a"));
    }

    @Test
    void listWithNameFilterShouldReturnPageResult() throws Exception {
        when(mediaService.list("lesson", 1, 10)).thenReturn(
                new PageResult<>(List.of(sampleMediaDto()), 1, 1, 10, 1)
        );

        mockMvc.perform(get("/vod/medias").param("name", "lesson"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records[0].filename").value("lesson01.mp4"));
    }

    @Test
    void playSignatureShouldReturnSignedUrl() throws Exception {
        when(playSignatureService.sign("f7c2a1b0e9d84f6a", true)).thenReturn(
                new PlaySignatureResponse(
                        "f7c2a1b0e9d84f6a",
                        "http://localhost/hls/f7c2a1b0e9d84f6a/preview.m3u8?e=1710003600&exper=120&sign=deadbeef",
                        "deadbeef",
                        1710003600L
                )
        );

        mockMvc.perform(get("/vod/signature/play")
                        .param("fileId", "f7c2a1b0e9d84f6a")
                        .param("preview", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fileId").value("f7c2a1b0e9d84f6a"))
                .andExpect(jsonPath("$.signature").value("deadbeef"))
                .andExpect(jsonPath("$.expireAt").value(1710003600))
                .andExpect(jsonPath("$.playUrl").value(
                        "http://localhost/hls/f7c2a1b0e9d84f6a/preview.m3u8?e=1710003600&exper=120&sign=deadbeef"));
    }

    @Test
    void playSignatureShouldDefaultPreviewToFalse() throws Exception {
        when(playSignatureService.sign("f7c2a1b0e9d84f6a", false)).thenReturn(
                new PlaySignatureResponse(
                        "f7c2a1b0e9d84f6a",
                        "http://localhost/hls/f7c2a1b0e9d84f6a/index.m3u8?e=1710003600&exper=0&sign=cafe",
                        "cafe",
                        1710003600L
                )
        );

        mockMvc.perform(get("/vod/signature/play").param("fileId", "f7c2a1b0e9d84f6a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.playUrl").value(
                        "http://localhost/hls/f7c2a1b0e9d84f6a/index.m3u8?e=1710003600&exper=0&sign=cafe"));
    }

    @Test
    void objectSignatureShouldReturnDto() throws Exception {
        when(objectSignatureService.sign("chap-1", 60)).thenReturn(
                new com.example.vod.controller.dto.ObjectSignatureResponse(
                        "chap-1",
                        "http://minio/vod/chap/s/001.md?sig=1",
                        "chap/s/001.md",
                        AssetType.CHAPTER,
                        1710000060L
                )
        );

        mockMvc.perform(get("/vod/signature/object")
                        .param("fileId", "chap-1")
                        .param("ttl", "60"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fileId").value("chap-1"))
                .andExpect(jsonPath("$.objectUrl").value("http://minio/vod/chap/s/001.md?sig=1"))
                .andExpect(jsonPath("$.assetType").value("CHAPTER"));
    }

    @Test
    void deleteShouldReturn204WhenMediaFinished() throws Exception {
        doNothing().when(mediaService).delete("f7c2a1b0e9d84f6a");

        mockMvc.perform(delete("/vod/medias/f7c2a1b0e9d84f6a"))
                .andExpect(status().isNoContent());
    }

    @Test
    void deleteShouldReturn404WhenMediaNotFound() throws Exception {
        doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "media not found"))
                .when(mediaService).delete("missing");

        mockMvc.perform(delete("/vod/medias/missing"))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteShouldReturn409WhenMediaProcessing() throws Exception {
        doThrow(new ResponseStatusException(HttpStatus.CONFLICT, "media is processing"))
                .when(mediaService).delete("f7c2a1b0e9d84f6a");

        mockMvc.perform(delete("/vod/medias/f7c2a1b0e9d84f6a"))
                .andExpect(status().isConflict());
    }

    private MediaDto sampleMediaDto() {
        return new MediaDto(
                1L,
                "f7c2a1b0e9d84f6a",
                AssetType.VIDEO,
                "raw/f7c2a1b0e9d84f6a/source.mp4",
                "lesson01.mp4",
                "video/mp4",
                null,
                null,
                null,
                null,
                null,
                null,
                1024L,
                MediaStatus.PROCESSING,
                "处理中",
                30,
                null,
                LocalDateTime.of(2024, 1, 1, 10, 0, 0),
                LocalDateTime.of(2024, 1, 1, 10, 30, 0)
        );
    }
}
