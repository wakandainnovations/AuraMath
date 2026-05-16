package com.lit.fire.flame;

import java.sql.Timestamp;

public class XPost {
    private long id;
    private String text;
    private Timestamp createdAt;
    private String keyword;
    private String sentimentCategory;
    private double sentimentScore;
    private String permalink;
    private String author;
    private int likesCount;
    private int commentCount;
    private int viewsCount;

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public Timestamp getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Timestamp createdAt) {
        this.createdAt = createdAt;
    }

    public String getKeyword() {
        return keyword;
    }

    public void setKeyword(String keyword) {
        this.keyword = keyword;
    }

    public String getSentimentCategory() {
        return sentimentCategory;
    }

    public void setSentimentCategory(String sentimentCategory) {
        this.sentimentCategory = sentimentCategory;
    }

    public double getSentimentScore() {
        return sentimentScore;
    }

    public void setSentimentScore(double sentimentScore) {
        this.sentimentScore = sentimentScore;
    }

    public String getPermalink() {
        return permalink;
    }

    public void setPermalink(String permalink) {
        this.permalink = permalink;
    }

    public String getAuthor() {
        return author;
    }

    public void setAuthor(String author) {
        this.author = author;
    }

    public int getLikesCount() {
        return likesCount;
    }

    public void setLikesCount(int likesCount) {
        this.likesCount = likesCount;
    }

    public int getCommentCount() {
        return commentCount;
    }

    public void setCommentCount(int commentCount) {
        this.commentCount = commentCount;
    }

    public int getViewsCount() {
        return viewsCount;
    }

    public void setViewsCount(int viewsCount) {
        this.viewsCount = viewsCount;
    }
}
