package com.example.vod.controller;

import com.example.vod.common.domain.media.AssetType;
import com.example.vod.common.domain.media.MediaStatus;
import com.example.vod.controller.dto.AbortMultipartRequest;
import com.example.vod.controller.dto.CompleteMultipartRequest;
import com.example.vod.controller.dto.MediaDto;
import com.example.vod.controller.dto.MultipartUploadRequest;
import com.example.vod.controller.dto.MultipartUploadSignatureResponse;
import com.example.vod.controller.dto.ObjectSignatureResponse;
import com.example.vod.controller.dto.PartUrl;
import com.example.vod.controller.dto.UploadSignatureResponse;
import com.example.vod.service.InternalTokenValidator;
import com.example.vod.service.MediaService;
import com.example.vod.service.ObjectSignatureService;
import com.example.vod.service.PlayAuthService;
import com.example.vod.service.PlayPlaylistService;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(InternalMediaController.class)
class InternalMediaControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private InternalTokenValidator tokenValidator;

    @MockBean
    private ObjectSignatureService objectSignatureService;

    @MockBean
    private UploadSignatureService uploadSignatureService;

    @MockBean
    private MediaService mediaService;

    @MockBean
    private PlayAuthService playAuthService;

    @MockBean
    private PlayPlaylistService playPlaylistService;

    @MockBean
    private com.example.vod.common.storage.MinioStorage minioStorage;

    @Test
    void uploadSignatureShouldRequireToken() throws Exception {
        doNothing().when(tokenValidator).requireValid("tok");
        when(uploadSignatureService.create(AssetType.DOCUMENT)).thenReturn(
                new UploadSignatureResponse("doc1", "http://put", "raw/doc1/source.bin", 1710000000L)
        );

        mockMvc.perform(get("/internal/medias/upload-signature")
                        .param("assetType", "DOCUMENT")
                        .header(InternalTokenValidator.HEADER, "tok"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fileId").value("doc1"))
                .andExpect(jsonPath("$.uploadUrl").value("http://put"));

        verify(tokenValidator).requireValid("tok");
        verify(uploadSignatureService).create(AssetType.DOCUMENT);
    }

    @Test
    void multipartUploadSignatureShouldRequireToken() throws Exception {
        doNothing().when(tokenValidator).requireValid("tok");
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

        mockMvc.perform(post("/internal/medias/upload-signature/multipart")
                        .header(InternalTokenValidator.HEADER, "tok")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filename\":\"lesson01.mp4\",\"contentType\":\"video/mp4\","
                                + "\"contentLength\":26214400,\"partSize\":10485760}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fileId").value("f7c2a1b0e9d84f6a"))
                .andExpect(jsonPath("$.uploadId").value("upload-id-abc"))
                .andExpect(jsonPath("$.partCount").value(3));

        verify(tokenValidator).requireValid("tok");
        verify(uploadSignatureService).createMultipart(any(MultipartUploadRequest.class));
    }

    @Test
    void completeMultipartShouldRequireToken() throws Exception {
        doNothing().when(tokenValidator).requireValid("tok");
        doNothing().when(uploadSignatureService)
                .complete(eq("f7c2a1b0e9d84f6a"), any(CompleteMultipartRequest.class));

        mockMvc.perform(post("/internal/medias/uploads/f7c2a1b0e9d84f6a/complete")
                        .header(InternalTokenValidator.HEADER, "tok")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"uploadId\":\"uid\","
                                + "\"parts\":[{\"partNumber\":1,\"etag\":\"\\\"a\\\"\"},"
                                + "{\"partNumber\":2,\"etag\":\"\\\"b\\\"\"}]}"))
                .andExpect(status().isOk());

        verify(tokenValidator).requireValid("tok");
        verify(uploadSignatureService).complete(eq("f7c2a1b0e9d84f6a"), any(CompleteMultipartRequest.class));
    }

    @Test
    void abortMultipartShouldRequireToken() throws Exception {
        doNothing().when(tokenValidator).requireValid("tok");
        doNothing().when(uploadSignatureService)
                .abort(eq("f7c2a1b0e9d84f6a"), any(AbortMultipartRequest.class));

        mockMvc.perform(post("/internal/medias/uploads/f7c2a1b0e9d84f6a/abort")
                        .header(InternalTokenValidator.HEADER, "tok")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"uploadId\":\"uid\"}"))
                .andExpect(status().isOk());

        verify(tokenValidator).requireValid("tok");
        verify(uploadSignatureService).abort(eq("f7c2a1b0e9d84f6a"), any(AbortMultipartRequest.class));
    }

    @Test
    void multipartShouldReturn401WithoutToken() throws Exception {
        doThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid"))
                .when(tokenValidator).requireValid(isNull());

        mockMvc.perform(post("/internal/medias/upload-signature/multipart")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filename\":\"lesson01.mp4\",\"contentType\":\"video/mp4\","
                                + "\"contentLength\":26214400,\"partSize\":10485760}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void commitShouldRequireToken() throws Exception {
        doNothing().when(tokenValidator).requireValid("tok");
        when(mediaService.commit(eq("doc1"), eq("redis.md"), isNull(), isNull(),
                eq(AssetType.DOCUMENT), eq(com.example.vod.common.domain.media.SplitRule.MARKDOWN)))
                .thenReturn(sampleDocDto());

        mockMvc.perform(post("/internal/medias")
                        .header(InternalTokenValidator.HEADER, "tok")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileId\":\"doc1\",\"filename\":\"redis.md\",\"assetType\":\"DOCUMENT\",\"splitRule\":\"MARKDOWN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fileId").value("doc1"))
                .andExpect(jsonPath("$.assetType").value("DOCUMENT"));
    }

    @Test
    void objectUrlShouldRequireTokenAndReturnDto() throws Exception {
        doNothing().when(tokenValidator).requireValid("tok");
        when(objectSignatureService.sign(eq("chap-1"), isNull())).thenReturn(
                new ObjectSignatureResponse(
                        "chap-1",
                        "http://minio/vod/chap/x/001.md?sig=1",
                        "chap/x/001.md",
                        AssetType.CHAPTER,
                        1710000060L
                )
        );

        mockMvc.perform(get("/internal/medias/chap-1/object-url")
                        .header(InternalTokenValidator.HEADER, "tok"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fileId").value("chap-1"))
                .andExpect(jsonPath("$.objectUrl").value("http://minio/vod/chap/x/001.md?sig=1"))
                .andExpect(jsonPath("$.assetType").value("CHAPTER"));

        verify(tokenValidator).requireValid("tok");
    }

    @Test
    void shouldReturn401WithoutToken() throws Exception {
        doThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid"))
                .when(tokenValidator).requireValid(isNull());

        mockMvc.perform(get("/internal/medias/upload-signature").param("assetType", "DOCUMENT"))
                .andExpect(status().isUnauthorized());
    }

    private static MediaDto sampleDocDto() {
        return new MediaDto(
                1L, "doc1", AssetType.DOCUMENT, "raw/doc1/source.bin", "redis.md",
                "application/octet-stream", null, null, null, null, null, null, 100L,
                MediaStatus.PROCESSING, "处理中", null, null,
                LocalDateTime.of(2024, 1, 1, 10, 0), LocalDateTime.of(2024, 1, 1, 10, 0)
        );
    }
}
