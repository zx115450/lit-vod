package com.example.vod.controller;

import com.example.vod.gateway.PlayPathSupport;
import com.example.vod.service.PlayAuthService;
import com.example.vod.service.PlayPlaylistService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PlayPlaylistController.class)
@TestPropertySource(properties = "play-sign.gateway-filter-enabled=false")
class PlayPlaylistControllerTest {

    private static final String FILE_ID = "f7c2a1b0e9d84f6a";
    private static final String PATH = "/hls/" + FILE_ID + "/index.m3u8";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PlayAuthService playAuthService;

    @MockBean
    private PlayPlaylistService playPlaylistService;

    @MockBean
    private com.example.vod.common.storage.MinioStorage minioStorage;

    @Test
    void shouldReturnRewrittenPlaylist() throws Exception {
        when(playAuthService.authorize(any(PlayPathSupport.HlsRequest.class), eq("1"), eq("abc"), eq("0")))
                .thenReturn(true);
        byte[] body = "#EXTM3U\nsegment_000.ts?e=1&exper=0&sign=abc\n".getBytes(StandardCharsets.UTF_8);
        when(playPlaylistService.loadRewritten(any(PlayPathSupport.HlsRequest.class), eq("1"), eq("abc"), eq("0")))
                .thenReturn(Optional.of(new PlayPlaylistService.RewrittenPlaylist(
                        body, "application/vnd.apple.mpegurl")));

        mockMvc.perform(get(PATH).param("e", "1").param("exper", "0").param("sign", "abc"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "private, max-age=60"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.parseMediaType("application/vnd.apple.mpegurl")))
                .andExpect(content().bytes(body));
    }

    @Test
    void shouldReturn403WhenUnauthorized() throws Exception {
        when(playAuthService.authorize(any(PlayPathSupport.HlsRequest.class), any(), any(), any()))
                .thenReturn(false);

        mockMvc.perform(get(PATH).param("e", "1").param("sign", "bad"))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldReturn404ForTsPath() throws Exception {
        mockMvc.perform(get("/hls/" + FILE_ID + "/segment_000.ts").param("e", "1").param("sign", "abc"))
                .andExpect(status().isNotFound());
    }
}
