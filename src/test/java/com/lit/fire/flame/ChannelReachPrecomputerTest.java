package com.lit.fire.flame;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Drives {@link ChannelReachPrecomputer}'s publish phases over mocked JdbcTemplates/transactions
 * (same mocking style as {@link UserEngagementRatingServiceTest}), since there's no Postgres in
 * this test environment. readTx/writeTx are replaced with a trivial pass-through TransactionTemplate
 * so {@code executeWithoutResult} just runs the callback synchronously.
 */
public class ChannelReachPrecomputerTest {

    private JdbcTemplate jdbc;
    private JdbcTemplate streamingJdbc;
    private ChannelReachPrecomputer precomputer;
    private final List<Object[]> capturedGenreInserts = new ArrayList<>();
    private final List<String> capturedUpdateSql = new ArrayList<>();

    @BeforeEach
    public void setUp() {
        DataSource dataSource = mock(DataSource.class);
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        precomputer = new ChannelReachPrecomputer(dataSource, txManager, new GenreClassifier());

        jdbc = mock(JdbcTemplate.class);
        streamingJdbc = mock(JdbcTemplate.class);
        ReflectionTestUtils.setField(precomputer, "jdbc", jdbc);
        ReflectionTestUtils.setField(precomputer, "streamingJdbc", streamingJdbc);
        ReflectionTestUtils.setField(precomputer, "readTx", passThroughTransactionTemplate());
        ReflectionTestUtils.setField(precomputer, "writeTx", passThroughTransactionTemplate());

        doAnswer(invocation -> {
            capturedUpdateSql.add(invocation.getArgument(0));
            return 0;
        }).when(jdbc).update(anyString());
        doAnswer(invocation -> {
            capturedUpdateSql.add(invocation.getArgument(0));
            return 0;
        }).when(jdbc).update(anyString(), any(Object.class));
        doAnswer(invocation -> {
            List<Object[]> rows = invocation.getArgument(1);
            capturedGenreInserts.addAll(rows);
            return new int[rows.size()];
        }).when(jdbc).batchUpdate(anyString(), any(List.class));
    }

    /** {@code executeWithoutResult} just invokes the callback with a dummy status — no real transaction. */
    private static TransactionTemplate passThroughTransactionTemplate() {
        TransactionTemplate tx = mock(TransactionTemplate.class);
        doAnswer(invocation -> {
            Consumer<TransactionStatus> action = invocation.getArgument(0);
            action.accept(mock(TransactionStatus.class));
            return null;
        }).when(tx).executeWithoutResult(any());
        return tx;
    }

    private void stubXPostsScan(List<ResultSet> rows) {
        doAnswer(invocation -> {
            RowCallbackHandler handler = invocation.getArgument(1);
            for (ResultSet rs : rows) {
                handler.processRow(rs);
            }
            return null;
        }).when(streamingJdbc).query(argThat((String sql) -> sql != null && sql.contains("FROM x_posts")),
                any(RowCallbackHandler.class));
    }

    private static ResultSet mockPost(String text, String keyword, long metric) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("id")).thenReturn("post-1");
        when(rs.getString("text")).thenReturn(text);
        when(rs.getString("keyword")).thenReturn(keyword);
        when(rs.getLong("metric")).thenReturn(metric);
        return rs;
    }

    @Test
    public void postMatchingTwoGenresContributesReachToBoth() throws Exception {
        // "vampire" -> Horror, "battle"+"gun" -> Action. Both qualify (score >= 1.0 threshold).
        ResultSet twoGenrePost = mockPost("The vampire started a battle with a gun", "movie", 100L);
        // "zombie" -> Horror only.
        ResultSet horrorOnlyPost = mockPost("Another zombie encounter in the dark", "movie", 50L);
        // No genre keywords at all.
        ResultSet noMatchPost = mockPost("Just had a nice walk outside today", "movie", 30L);
        stubXPostsScan(List.of(twoGenrePost, horrorOnlyPost, noMatchPost));

        ReflectionTestUtils.invokeMethod(precomputer, "publishGenreReach");

        long horrorReach = capturedGenreInserts.stream()
                .filter(r -> "horror".equals(r[0]) && "x".equals(r[1]))
                .mapToLong(r -> (long) r[2]).sum();
        long horrorCount = capturedGenreInserts.stream()
                .filter(r -> "horror".equals(r[0]) && "x".equals(r[1]))
                .mapToLong(r -> (long) r[3]).sum();
        long actionReach = capturedGenreInserts.stream()
                .filter(r -> "action".equals(r[0]) && "x".equals(r[1]))
                .mapToLong(r -> (long) r[2]).sum();

        assertEquals(150L, horrorReach, "horror should get reach from both the two-genre post and the horror-only post");
        assertEquals(2L, horrorCount);
        assertEquals(100L, actionReach, "action should get reach only from the two-genre post, not weighted down");
    }

    @Test
    public void truncatePrecedesInsertForGenreReach() throws Exception {
        stubXPostsScan(List.of(mockPost("scary ghost story", "movie", 10L)));

        ReflectionTestUtils.invokeMethod(precomputer, "publishGenreReach");

        assertTrue(capturedUpdateSql.get(0).contains("TRUNCATE"), "TRUNCATE must run before the batch insert");
    }

    @Test
    public void keywordReachIssuesOneInsertSelectPerPlatform() {
        ReflectionTestUtils.invokeMethod(precomputer, "publishKeywordReach");

        assertEquals(5, capturedUpdateSql.size(), "1 TRUNCATE + 4 per-platform INSERT...SELECT");
        assertTrue(capturedUpdateSql.get(0).contains("TRUNCATE"));
        assertTrue(capturedUpdateSql.stream().anyMatch(s -> s.contains("x_posts") && s.contains("views_count")));
        assertTrue(capturedUpdateSql.stream().anyMatch(s -> s.contains("youtube_comments") && s.contains("likes_count")));
        assertTrue(capturedUpdateSql.stream().anyMatch(s -> s.contains("reddit_posts") && s.contains("num_comments")));
        assertTrue(capturedUpdateSql.stream().anyMatch(s -> s.contains("instagram_posts") && s.contains("like_count")));
    }
}
