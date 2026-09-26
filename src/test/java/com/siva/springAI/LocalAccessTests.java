package com.siva.springAI;

import com.siva.springAI.config.ApiAccessFilter;
import com.siva.springAI.config.ApiAccessPolicy;
import com.siva.springAI.controller.ChatController;
import com.siva.springAI.service.ChatService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = ChatController.class, properties = {
        "app.security.api-key=", "app.chat.requests-per-minute=1"
})
@Import({ApiAccessFilter.class, ApiAccessPolicy.class})
class LocalAccessTests {
    @Autowired MockMvc mvc;
    @MockitoBean ChatService service;

    @Test
    void preservesLocalAccessAndReturnsRateLimitWithoutCallingProviderAgain() throws Exception {
        when(service.chat("Hello")).thenReturn("Hi");
        mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"Hello\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.reply").value("Hi"));
        mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"Hello\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string("Retry-After", "60"));
        verify(service, times(1)).chat("Hello");
    }
}
