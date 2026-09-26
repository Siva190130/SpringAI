CREATE TABLE conversations (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    title VARCHAR(120) NOT NULL,
    title_custom BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    lease_id VARCHAR(36),
    lease_until TIMESTAMP(6) NULL,
    INDEX idx_conversations_updated (updated_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- A completed turn is atomic: a failed model call never leaves half a conversation.
CREATE TABLE conversation_turns (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    conversation_id VARCHAR(36) NOT NULL,
    request_id VARCHAR(36) NOT NULL,
    user_message TEXT NOT NULL,
    assistant_message MEDIUMTEXT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_turn_conversation FOREIGN KEY (conversation_id)
        REFERENCES conversations(id) ON DELETE CASCADE,
    CONSTRAINT uq_turn_request UNIQUE (conversation_id, request_id),
    INDEX idx_turn_conversation (conversation_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
