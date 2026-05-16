package com.lit.fire.flame;

import com.lit.fire.flame.HawkesIntensityCalculator.HawkesParameters;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Map;
import java.util.HashMap;
import java.util.stream.Collectors;


public class DatabaseManager {

    private Connection connection;
    private HawkesIntensityCalculator hawkesCalculator;
    private AspectSentimentAnalyzer aspectAnalyzer;


    public DatabaseManager() {
        try {
            Properties dbProperties = new Properties();
            try (InputStream in = getClass().getClassLoader().getResourceAsStream("secrets.txt")) {
                if (in != null) {
                    dbProperties.load(in);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }

            String dbUrl = dbProperties.getProperty("db.url", "jdbc:postgresql://localhost:5432/aura");
            String dbUser = dbProperties.getProperty("db.user", "postgres");
            String dbPassword = dbProperties.getProperty("db.password", "postgres");

            connection = DriverManager.getConnection(dbUrl, dbUser, dbPassword);

            // Initialize HawkesIntensityCalculator with a default beta value.
            // This can be made configurable.
            double defaultBeta = 1.0;
            hawkesCalculator = new HawkesIntensityCalculator(connection, defaultBeta);
            aspectAnalyzer = new AspectSentimentAnalyzer();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public Map<String, Map<String, Double>> getAspectSentimentByAuthor() {
        Map<String, List<XPost>> postsByAuthor = getXPosts().stream()
                .collect(Collectors.groupingBy(XPost::getAuthor));

        Map<String, Map<String, Double>> authorAspectSentiments = new HashMap<>();

        for (Map.Entry<String, List<XPost>> entry : postsByAuthor.entrySet()) {
            String author = entry.getKey();
            List<XPost> posts = entry.getValue();
            Map<String, List<Double>> aspectScores = new HashMap<>();

            for (XPost post : posts) {
                Map<String, Double> postAspects = aspectAnalyzer.analyze(post.getText(), post.getSentimentScore());
                for (Map.Entry<String, Double> aspectEntry : postAspects.entrySet()) {
                    aspectScores.computeIfAbsent(aspectEntry.getKey(), k -> new ArrayList<>()).add(aspectEntry.getValue());
                }
            }

            Map<String, Double> averageAspectSentiments = new HashMap<>();
            for (Map.Entry<String, List<Double>> aspectEntry : aspectScores.entrySet()) {
                double average = aspectEntry.getValue().stream().mapToDouble(d -> d).average().orElse(0.0);
                averageAspectSentiments.put(aspectEntry.getKey(), average);
            }
            authorAspectSentiments.put(author, averageAspectSentiments);
        }

        return authorAspectSentiments;
    }

    public List<XPost> getXPosts() {
        List<XPost> posts = new ArrayList<>();
        String sql = "SELECT * FROM x_posts";
        try (PreparedStatement stmt = connection.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                XPost post = new XPost();
                post.setId(rs.getLong("id"));
                post.setText(rs.getString("text"));
                post.setCreatedAt(rs.getTimestamp("created_at"));
                post.setKeyword(rs.getString("keyword"));
                post.setSentimentCategory(rs.getString("sentiment_category"));
                post.setSentimentScore(rs.getDouble("sentiment_score"));
                post.setPermalink(rs.getString("permalink"));
                post.setAuthor(rs.getString("author"));
                post.setLikesCount(rs.getInt("likes_count"));
                post.setCommentCount(rs.getInt("comment_count"));
                post.setViewsCount(rs.getInt("views_count"));
                posts.add(post);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return posts;
    }

    public void insertXPost(XPost post) {
        String sql = "INSERT INTO x_posts (text, created_at, keyword, sentiment_category, sentiment_score, permalink, author, likes_count, comment_count, views_count) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, post.getText());
            stmt.setTimestamp(2, post.getCreatedAt());
            stmt.setString(3, post.getKeyword());
            stmt.setString(4, post.getSentimentCategory());
            stmt.setDouble(5, post.getSentimentScore());
            stmt.setString(6, post.getPermalink());
            stmt.setString(7, post.getAuthor());
            stmt.setInt(8, post.getLikesCount());
            stmt.setInt(9, post.getCommentCount());
            stmt.setInt(10, post.getViewsCount());
            stmt.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public List<YoutubeComment> getYoutubeComments() {
        List<YoutubeComment> comments = new ArrayList<>();
        String sql = "SELECT * FROM youtube_comments";
        try (PreparedStatement stmt = connection.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                YoutubeComment comment = new YoutubeComment();
                comment.setId(rs.getLong("id"));
                comment.setVideoId(rs.getString("video_id"));
                comment.setVideoTitle(rs.getString("video_title"));
                comment.setText(rs.getString("text"));
                comment.setAuthor(rs.getString("author"));
                comment.setPublishedAt(rs.getTimestamp("published_at"));
                comment.setPermalink(rs.getString("permalink"));
                comment.setKeyword(rs.getString("keyword"));
                comment.setSentimentCategory(rs.getString("sentiment_category"));
                comments.add(comment);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return comments;
    }

    public void insertYoutubeComment(YoutubeComment comment) {
        String sql = "INSERT INTO youtube_comments (video_id, video_title, text, author, published_at, permalink, keyword, sentiment_category) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, comment.getVideoId());
            stmt.setString(2, comment.getVideoTitle());
            stmt.setString(3, comment.getText());
            stmt.setString(4, comment.getAuthor());
            stmt.setTimestamp(5, comment.getPublishedAt());
            stmt.setString(6, comment.getPermalink());
            stmt.setString(7, comment.getKeyword());
            stmt.setString(8, comment.getSentimentCategory());
            stmt.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public List<RedditPost> getRedditPosts() {
        List<RedditPost> posts = new ArrayList<>();
        String sql = "SELECT * FROM reddit_posts";
        try (PreparedStatement stmt = connection.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                RedditPost post = new RedditPost();
                post.setId(rs.getLong("id"));
                post.setTitle(rs.getString("title"));
                post.setText(rs.getString("text"));
                post.setCreatedAt(rs.getTimestamp("created_at"));
                post.setKeyword(rs.getString("keyword"));
                post.setSentimentCategory(rs.getString("sentiment_category"));
                post.setPermalink(rs.getString("permalink"));
                post.setAuthor(rs.getString("author"));
                post.setScore(rs.getInt("score"));
                post.setNumComments(rs.getInt("num_comments"));
                posts.add(post);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return posts;
    }

    public void insertRedditPost(RedditPost post) {
        String sql = "INSERT INTO reddit_posts (title, text, created_at, keyword, sentiment_category, permalink,. author, score, num_comments) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, post.getTitle());
            stmt.setString(2, post.getText());
            stmt.setTimestamp(3, post.getCreatedAt());
            stmt.setString(4, post.getKeyword());
            stmt.setString(5, post.getSentimentCategory());
            stmt.setString(6, post.getPermalink());
            stmt.setString(7, post.getAuthor());
            stmt.setInt(8, post.getScore());
            stmt.setInt(9, post.getNumComments());
            stmt.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public List<InstagramPost> getInstagramPosts() {
        List<InstagramPost> posts = new ArrayList<>();
        String sql = "SELECT * FROM instagram_posts";
        try (PreparedStatement stmt = connection.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                InstagramPost post = new InstagramPost();
                post.setId(rs.getLong("id"));
                post.setText(rs.getString("text"));
                post.setMediaType(rs.getString("media_type"));
                post.setMediaUrl(rs.getString("media_url"));
                post.setPermalink(rs.getString("permalink"));
                post.setTimestamp(rs.getTimestamp("timestamp"));
                post.setKeyword(rs.getString("keyword"));
                post.setSentimentCategory(rs.getString("sentiment_category"));
                post.setAuthor(rs.getString("author"));
                post.setLikeCount(rs.getInt("like_count"));
                post.setCommentsCount(rs.getInt("comments_count"));
                posts.add(post);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return posts;
    }

    public void insertInstagramPost(InstagramPost post) {
        String sql = "INSERT INTO instagram_posts (text, media_type, media_url, permalink, timestamp, keyword, sentiment_category, author, like_count, comments_count) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, post.getText());
            stmt.setString(2, post.getMediaType());
            stmt.setString(3, post.getMediaUrl());
            stmt.setString(4, post.getPermalink());
            stmt.setTimestamp(5, post.getTimestamp());
            stmt.setString(6, post.getKeyword());
            stmt.setString(7, post.getSentimentCategory());
            stmt.setString(8, post.getAuthor());
            stmt.setInt(9, post.getLikeCount());
            stmt.setInt(10, post.getCommentsCount());
            stmt.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    /**
     * Estimates the Hawkes process parameters (mu and alpha) for a given author.
     *
     * @param author The author for whom to estimate the parameters.
     * @return A {@link HawkesParameters} object containing the estimated mu and alpha.
     * @throws SQLException if a database access error occurs.
     */
    public HawkesParameters estimateHawkesParameters(String author) throws SQLException {
        return hawkesCalculator.estimateParameters(author);
    }

    /**
     * Identifies if an author is a "Super Spreader" based on their infectivity factor (alpha).
     *
     * @param author The author to evaluate.
     * @param alphaThreshold The threshold for the alpha parameter to be considered a Super Spreader.
     * @return true if the author's alpha is above the threshold, false otherwise.
     * @throws SQLException if a database access error occurs during parameter estimation.
     */
    public boolean isSuperSpreader(String author, double alphaThreshold) throws SQLException {
        return hawkesCalculator.isSuperSpreader(author, alphaThreshold);
    }

    /**
     * Updates the beta parameter for the Hawkes process model.
     * @param beta The new decay rate for the exponential kernel.
     */
    public void setHawkesBeta(double beta) {
        this.hawkesCalculator = new HawkesIntensityCalculator(this.connection, beta);
    }

    public Connection getConnection() {
        return connection;
    }

    public void closeConnection() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException e) {
                e.printStackTrace();
            }
        }
    }
}
