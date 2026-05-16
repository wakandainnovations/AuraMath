package com.lit.fire.flame.mappers;

import com.lit.fire.flame.models.UniversalPost;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Service
public class PostMapper {

    public UniversalPost map(ResultSet rs, String tableName) throws SQLException {
        return switch (tableName) {
            case "x_posts" -> mapXPost(rs);
            case "youtube_comments" -> mapYouTubeComment(rs);
            case "reddit_posts" -> mapRedditPost(rs);
            case "instagram_posts" -> mapInstagramPost(rs);
            default -> throw new IllegalArgumentException("Unknown table name: " + tableName);
        };
    }

    private UniversalPost mapXPost(ResultSet rs) throws SQLException {
        String postId = rs.getString("id");
        String authorId = rs.getString("author");
        String content = rs.getString("text");
        LocalDateTime timestamp = rs.getTimestamp("created_at").toLocalDateTime();
        String platform = "x_posts";

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("keyword", rs.getString("keyword"));
        metadata.put("sentiment_score", rs.getDouble("sentiment_score"));
        metadata.put("likes", rs.getInt("likes_count"));
        metadata.put("comments", rs.getInt("comment_count"));
        metadata.put("views", rs.getInt("views_count"));
        metadata.put("permalink", rs.getString("permalink"));

        return new UniversalPost(postId, authorId, content, timestamp, platform, metadata);
    }

    private UniversalPost mapYouTubeComment(ResultSet rs) throws SQLException {
        String postId = rs.getString("id");
        String authorId = rs.getString("author");
        String content = rs.getString("text");
        LocalDateTime timestamp = rs.getTimestamp("published_at").toLocalDateTime();
        String platform = "youtube_comments";

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("keyword", rs.getString("keyword"));
        metadata.put("sentiment_score", rs.getDouble("sentiment_score"));
        metadata.put("likes", rs.getInt("likes_count"));
        metadata.put("comments", rs.getInt("reply_count"));
        metadata.put("permalink", rs.getString("permalink"));

        return new UniversalPost(postId, authorId, content, timestamp, platform, metadata);
    }

    private UniversalPost mapRedditPost(ResultSet rs) throws SQLException {
        String postId = rs.getString("id");
        String authorId = rs.getString("author");
        String content = rs.getString("title") + " " + rs.getString("text");
        LocalDateTime timestamp = rs.getTimestamp("created_at").toLocalDateTime();
        String platform = "reddit_posts";

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("keyword", rs.getString("keyword"));
        metadata.put("sentiment_score", rs.getDouble("sentiment_score"));
        metadata.put("likes", rs.getInt("score"));
        metadata.put("comments", rs.getInt("num_comments"));
        metadata.put("platformSpecificScore", rs.getInt("score"));
        metadata.put("permalink", rs.getString("permalink"));

        return new UniversalPost(postId, authorId, content, timestamp, platform, metadata);
    }

    private UniversalPost mapInstagramPost(ResultSet rs) throws SQLException {
        String postId = rs.getString("id");
        String authorId = rs.getString("author");
        String content = rs.getString("text");
        LocalDateTime timestamp = rs.getTimestamp("timestamp").toLocalDateTime();
        String platform = "instagram_posts";

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("keyword", rs.getString("keyword"));
        metadata.put("sentiment_score", rs.getDouble("sentiment_score"));
        metadata.put("likes", rs.getInt("like_count"));
        metadata.put("comments", rs.getInt("comments_count"));
        metadata.put("permalink", rs.getString("permalink"));

        return new UniversalPost(postId, authorId, content, timestamp, platform, metadata);
    }
}
