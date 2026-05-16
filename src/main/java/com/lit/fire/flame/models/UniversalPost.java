package com.lit.fire.flame.models;

import java.time.LocalDateTime;
import java.util.Map;

public class UniversalPost {
    private String postId;
    private String authorId;
    private String content;
    private LocalDateTime timestamp;
    private String platform;
    private Map<String, Object> metadata;

    // Constructor
    public UniversalPost(String postId, String authorId, String content, LocalDateTime timestamp, String platform, Map<String, Object> metadata) {
        this.postId = postId;
        this.authorId = authorId;
        this.content = content;
        this.timestamp = timestamp;
        this.platform = platform;
        this.metadata = metadata;
    }

    // Getters
    public String getPostId() {
        return postId;
    }

    public String getAuthorId() {
        return authorId;
    }

    public String getContent() {
        return content;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    public String getPlatform() {
        return platform;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    // Setters
    public void setPostId(String postId) {
        this.postId = postId;
    }

    public void setAuthorId(String authorId) {
        this.authorId = authorId;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public void setTimestamp(LocalDateTime timestamp) {
        this.timestamp = timestamp;
    }

    public void setPlatform(String platform) {
        this.platform = platform;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }
}
