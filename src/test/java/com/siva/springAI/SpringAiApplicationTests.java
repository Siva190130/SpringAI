package com.siva.springAI;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.ai.openai.base-url=https://example.openai.azure.com/openai/v1",
        "spring.ai.openai.api-key=test-placeholder",
        "spring.ai.openai.chat.model=test-deployment",
        "app.security.api-key=test-access-key"
})
@AutoConfigureMockMvc
class SpringAiApplicationTests {
    @Autowired MockMvc mvc;

    @Test
    void contextLoads() {
    }

    @Test
    void healthIsPublicButMetricsRequireKey() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/actuator/metrics")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/metrics").header("X-API-Key", "test-access-key"))
                .andExpect(status().isOk());
    }
}
