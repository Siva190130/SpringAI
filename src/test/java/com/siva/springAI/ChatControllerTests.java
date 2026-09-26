package com.siva.springAI;

import com.siva.springAI.config.ApiAccessPolicy;
import com.siva.springAI.config.ApiAccessFilter;
import com.siva.springAI.controller.ChatController;
import com.siva.springAI.exception.ChatCapacityException;
import com.siva.springAI.service.ChatService;
import com.openai.errors.OpenAIException;
import com.openai.errors.OpenAIIoException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.ResourceAccessException;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = ChatController.class, properties = {
        "app.security.api-key=test-access-key", "app.chat.requests-per-minute=10000"
})
@Import({ApiAccessFilter.class, ApiAccessPolicy.class})
class ChatControllerTests {
    @Autowired MockMvc mvc;
    @MockitoBean ChatService service;

    @Test
    void returnsExistingSuccessContract() throws Exception {
        when(service.chat("Hello")).thenReturn("Hi");
        mvc.perform(post("/api/chat").header("X-API-Key", "test-access-key")
                .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"Hello\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.reply").value("Hi"));
        verify(service).chat("Hello");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"message\":null}", "{\"message\":\"\"}",
            "{\"message\":\"   \"}", "{", ""})
    void rejectsInvalidInputWithoutCallingProvider(String body) throws Exception {
        mvc.perform(post("/api/chat").header("X-API-Key", "test-access-key")
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400));
        verifyNoInteractions(service);
    }

    @Test
    void rejectsOversizedMessage() throws Exception {
        mvc.perform(post("/api/chat").header("X-API-Key", "test-access-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"" + "x".repeat(16001) + "\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void acceptsMessageAtLimit() throws Exception {
        when(service.chat(anyString())).thenReturn("OK");
        mvc.perform(post("/api/chat").header("X-API-Key", "test-access-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"" + "x".repeat(16000) + "\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void requiresConfiguredKey() throws Exception {
        mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"Hello\"}")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/chat").header("X-API-Key", "wrong")
                .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"Hello\"}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void hidesInternalDetails() throws Exception {
        when(service.chat(anyString())).thenThrow(new IllegalStateException("secret-provider-detail"));
        mvc.perform(post("/api/chat").header("X-API-Key", "test-access-key")
                .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"Hello\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred."));
    }

    @Test
    void mapsConnectionFailure() throws Exception {
        when(service.chat(anyString())).thenThrow(new ResourceAccessException("secret-url"));
        mvc.perform(post("/api/chat").header("X-API-Key", "test-access-key")
                .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"Hello\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value("AI service temporarily unavailable. Please retry later."));
    }

    @Test
    void mapsCapacityFailure() throws Exception {
        when(service.chat(anyString())).thenThrow(new ChatCapacityException());
        mvc.perform(post("/api/chat").header("X-API-Key", "test-access-key")
                .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"Hello\"}"))
                .andExpect(status().isServiceUnavailable()).andExpect(header().string("Retry-After", "1"));
    }

    @Test
    void mapsSdkFailuresWithoutExposingDetails() throws Exception {
        when(service.chat(anyString())).thenThrow(new OpenAIIoException("secret-url"));
        mvc.perform(post("/api/chat").header("X-API-Key", "test-access-key")
                .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"Hello\"}"))
                .andExpect(status().isServiceUnavailable());
        doThrow(new OpenAIException("secret-provider-detail")).when(service).chat(anyString());
        mvc.perform(post("/api/chat").header("X-API-Key", "test-access-key")
                .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"Hello\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.detail").value("AI provider could not complete the request."));
    }

    @Test
    void preservesMvcMethodAndMediaTypeErrors() throws Exception {
        mvc.perform(get("/api/chat").header("X-API-Key", "test-access-key"))
                .andExpect(status().isMethodNotAllowed());
        mvc.perform(post("/api/chat").header("X-API-Key", "test-access-key")
                .contentType(MediaType.TEXT_PLAIN).content("hello"))
                .andExpect(status().isUnsupportedMediaType());
    }
}
