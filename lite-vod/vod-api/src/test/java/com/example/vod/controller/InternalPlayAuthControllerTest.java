package com.example.vod.controller;

import com.example.vod.service.PlayAuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(InternalPlayAuthController.class)
class InternalPlayAuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PlayAuthService playAuthService;

    // PlayGatewayFilter / PlayPlaylistController 在 WebMvcTest 切片中可能装载
    @MockBean
    private com.example.vod.service.PlayPlaylistService playPlaylistService;

    @MockBean
    private com.example.vod.common.storage.MinioStorage minioStorage;

    @Test
    void shouldReturn200WhenAuthorized() throws Exception {
        String uri = "/hls/f7c2a1b0e9d84f6a/index.m3u8?e=1&exper=0&sign=abc";
        when(playAuthService.authorizeOriginalUri(eq(uri))).thenReturn(true);

        mockMvc.perform(get("/internal/play-auth").header("X-Original-URI", uri))
                .andExpect(status().isOk())
                .andExpect(content().string(""));
    }

    @Test
    void shouldReturn403WhenUnauthorized() throws Exception {
        when(playAuthService.authorizeOriginalUri(eq("/hls/x/index.m3u8"))).thenReturn(false);

        mockMvc.perform(get("/internal/play-auth").header("X-Original-URI", "/hls/x/index.m3u8"))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldReturn403WhenHeaderMissing() throws Exception {
        when(playAuthService.authorizeOriginalUri(eq(null))).thenReturn(false);

        mockMvc.perform(get("/internal/play-auth"))
                .andExpect(status().isForbidden());
    }
}
