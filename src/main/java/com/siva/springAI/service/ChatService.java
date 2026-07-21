package com.siva.springAI.service;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
// Lombok generates a constructor for all final fields — Spring picks
// this up as the single constructor and performs constructor injection
// automatically (no @Autowired needed on single-constructor classes
// since Spring 4.3). Constructor injection over field injection because
// it makes the dependency explicit, allows the field to be final
// (immutable, thread-safe reference), and fails fast at context startup
// if the bean can't be wired — field injection fails silently until
// first use (NPE at runtime).
public class ChatService {

    private final ChatClient chatClient;

    public String chat(String userMessage) {
        /*
         * chatClient.prompt() starts a fluent DefaultChatClientRequestSpec.
         * .user(msg) sets the user message content.
         * .call() executes SYNCHRONOUSLY — this blocks the calling thread
         *          (a Tomcat request-handling thread) until Ollama returns
         *          a full response. For a local model this can be
         *          seconds. Under concurrent load this exhausts your
         *          Tomcat thread pool fast (default max 200 threads) —
         *          this is the same thread-pool-exhaustion problem you'd
         *          see with any slow blocking I/O call in a servlet
         *          thread. For production, either:
         *            (a) use .stream() returning Flux<String> and expose
         *                over SSE/WebFlux, or
         *            (b) enable virtual threads:
         *                spring.threads.virtual.enabled=true (Java 21+)
         *                so blocking calls don't pin a platform thread.
         * .content() extracts just the text; the raw ChatResponse also
         *          carries metadata (token usage, finish reason, model
         *          name) if you need it for logging/observability.
         */
        return chatClient.prompt()
                .user(userMessage)
                .call()
                .content();
    }
}