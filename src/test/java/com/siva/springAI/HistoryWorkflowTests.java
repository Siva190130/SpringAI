package com.siva.springAI;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.ai.openai.base-url=https://example.openai.azure.com/openai/v1",
        "spring.ai.openai.api-key=test-placeholder", "spring.ai.openai.chat.model=test-deployment",
        "app.security.api-key=test-access-key", "app.chat.requests-per-minute=10000"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HistoryWorkflowTests {
    @Autowired MockMvc mvc;
    @MockitoBean ChatModel model;

    @Test
    void completesTheHttpHistoryLifecycleAgainstTheMigratedDatabase() throws Exception {
        when(model.getOptions()).thenReturn(ChatOptions.builder().build());
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("Hello Siva")))));
        String body = mvc.perform(post("/api/chat/sessions").header("X-API-Key", "test-access-key"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(body, "$.sessionId");
        String request = """
                {"message":"My name is Siva","sessionId":"%s","requestId":"%s"}
                """.formatted(id, UUID.randomUUID());
        try {
            for (int attempt = 0; attempt < 2; attempt++) {
                mvc.perform(post("/api/chat").header("X-API-Key", "test-access-key")
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                        .andExpect(status().isOk()).andExpect(jsonPath("$.reply").value("Hello Siva"));
            }
            verify(model, times(1)).call(any(Prompt.class));
            mvc.perform(get("/api/chat/sessions/" + id).header("X-API-Key", "test-access-key"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.turns.length()").value(1))
                    .andExpect(jsonPath("$.conversation.title").value("My name is Siva"))
                    .andExpect(jsonPath("$.turns[0].assistantMessage").value("Hello Siva"));
            mvc.perform(patch("/api/chat/sessions/" + id).header("X-API-Key", "test-access-key")
                    .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Introductions\"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.title").value("Introductions"));
        } finally {
            mvc.perform(delete("/api/chat/sessions/" + id).header("X-API-Key", "test-access-key"))
                    .andExpect(status().isNoContent());
        }
        mvc.perform(get("/api/chat/sessions/" + id).header("X-API-Key", "test-access-key"))
                .andExpect(status().isGone());
    }
}
