package com.siva.springAI;

import com.siva.springAI.config.ApiAccessFilter;
import com.siva.springAI.config.ApiAccessPolicy;
import com.siva.springAI.controller.ChatController;
import com.siva.springAI.controller.ChatSessionController;
import com.siva.springAI.exception.ChatSessionException;
import com.siva.springAI.service.ChatService;
import com.siva.springAI.service.ConversationMemory;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = {ChatController.class, ChatSessionController.class}, properties = {
        "app.security.api-key=test-access-key", "app.chat.requests-per-minute=10000"
})
@Import({ApiAccessFilter.class, ApiAccessPolicy.class})
class ChatSessionControllerTests {
    @Autowired MockMvc mvc;
    @MockitoBean ChatService service;
    @MockitoBean ConversationMemory memory;

    @Test
    void createsUsesAndDeletesSession() throws Exception {
        UUID session = UUID.randomUUID();
        UUID request = UUID.randomUUID();
        when(memory.create()).thenReturn(session);
        mvc.perform(post("/api/chat/sessions").header("X-API-Key", "test-access-key"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.sessionId").value(session.toString()))
                .andExpect(header().string("Cache-Control", "no-store"));
        when(service.chat("Hello", session, request)).thenReturn("Hi");
        mvc.perform(post("/api/chat").header("X-API-Key", "test-access-key")
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"message":"Hello","sessionId":"%s","requestId":"%s"}
                        """.formatted(session, request)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.reply").value("Hi"));
        verify(service).chat("Hello", session, request);
        mvc.perform(delete("/api/chat/sessions/" + session).header("X-API-Key", "test-access-key"))
                .andExpect(status().isNoContent());
        verify(memory).delete(session);
    }

    @Test
    void validatesIdentifiersAndRequiresAccessKeyForSessionEndpoints() throws Exception {
        mvc.perform(post("/api/chat/sessions")).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/chat/sessions/" + UUID.randomUUID())).andExpect(status().isUnauthorized());
        for (String body : new String[] {
                "{\"message\":\"Hello\",\"sessionId\":\"bad\",\"requestId\":\"bad\"}",
                "{\"message\":\"Hello\",\"sessionId\":\"" + UUID.randomUUID() + "\"}",
                "{\"message\":\"Hello\",\"requestId\":\"" + UUID.randomUUID() + "\"}"}) {
            mvc.perform(post("/api/chat").header("X-API-Key", "test-access-key")
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service, memory);
    }

    @Test
    void preservesSessionErrorsAsProblemDetails() throws Exception {
        when(service.chat(anyString(), any(UUID.class), any(UUID.class)))
                .thenThrow(new ChatSessionException(HttpStatus.GONE, "This conversation has expired. Please start a new chat."));
        mvc.perform(post("/api/chat").header("X-API-Key", "test-access-key")
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"message":"Hello","sessionId":"%s","requestId":"%s"}
                        """.formatted(UUID.randomUUID(), UUID.randomUUID())))
                .andExpect(status().isGone()).andExpect(jsonPath("$.status").value(410));
    }
}
