package com.example.vod.controller;

import com.example.vod.controller.dto.CommitMediaRequest;
import com.example.vod.controller.dto.MediaDto;
import com.example.vod.controller.dto.PageResult;
import com.example.vod.controller.dto.PlaySignatureResponse;
import com.example.vod.controller.dto.UploadSignatureResponse;
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
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
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

    // PlayGatewayFilter（步骤 11）依赖以下两个 bean，@WebMvcTest 会装载 Filter，需一并 mock
    @MockBean
    private com.example.vod.service.PlaySignService playSignService;

    @MockBean
    private com.example.vod.common.storage.MinioStorage minioStorage;

    @Test
    void uploadSignatureShouldReturnDto() throws Exception {
        when(uploadSignatureService.create()).thenReturn(
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
    void commitMediaShouldReturnDto() throws Exception {
        when(mediaService.commit(any(String.class), any(String.class))).thenReturn(sampleMediaDto());

        mockMvc.perform(post("/vod/medias")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileId\":\"f7c2a1b0e9d84f6a\",\"filename\":\"lesson01.mp4\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fileId").value("f7c2a1b0e9d84f6a"))
                .andExpect(jsonPath("$.filename").value("lesson01.mp4"))
                .andExpect(jsonPath("$.size").value(1024))
                .andExpect(jsonPath("$.status").value("PROCESSING"))
                .andExpect(jsonPath("$.statusText").value("处理中"));
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
        when(playSignatureService.sign("f7c2a1b0e9d84f6a", 300)).thenReturn(
                new PlaySignatureResponse(
                        "f7c2a1b0e9d84f6a",
                        "http://localhost/hls/f7c2a1b0e9d84f6a/index.m3u8?e=1710003600&exper=300&sign=deadbeef",
                        "deadbeef",
                        1710003600L
                )
        );

        mockMvc.perform(get("/vod/signature/play")
                        .param("fileId", "f7c2a1b0e9d84f6a")
                        .param("exper", "300"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fileId").value("f7c2a1b0e9d84f6a"))
                .andExpect(jsonPath("$.signature").value("deadbeef"))
                .andExpect(jsonPath("$.expireAt").value(1710003600))
                .andExpect(jsonPath("$.playUrl").value(
                        "http://localhost/hls/f7c2a1b0e9d84f6a/index.m3u8?e=1710003600&exper=300&sign=deadbeef"));
    }

    @Test
    void playSignatureShouldDefaultExperToZero() throws Exception {
        when(playSignatureService.sign("f7c2a1b0e9d84f6a", 0)).thenReturn(
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
                "raw/f7c2a1b0e9d84f6a/source.mp4",
                "lesson01.mp4",
                null,
                null,
                null,
                1024L,
                MediaStatus.PROCESSING,
                "处理中",
                null,
                LocalDateTime.of(2024, 1, 1, 10, 0, 0),
                LocalDateTime.of(2024, 1, 1, 10, 30, 0)
        );
    }
}
