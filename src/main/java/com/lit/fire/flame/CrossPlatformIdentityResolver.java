package com.lit.fire.flame;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Service
public class CrossPlatformIdentityResolver {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final String CREATE_TABLE_SQL =
            "CREATE TABLE IF NOT EXISTS user_identity_link (" +
            "global_user_id VARCHAR(255) PRIMARY KEY, " +
            "normalized_author VARCHAR(255) UNIQUE)";

    // UNION over every platform: an identity should be created for any author seen
    // on any source table, not only authors who appear on both X and Instagram.
    // Authors collapsing to empty after normalisation (e.g. names made entirely of
    // punctuation) are excluded.
    private static final String COLLECT_AUTHORS_SQL =
            "SELECT DISTINCT normalized_author FROM (" +
            "  SELECT REGEXP_REPLACE(LOWER(author), '[^a-zA-Z0-9]', '', 'g') AS normalized_author" +
            "    FROM x_posts          WHERE author IS NOT NULL AND author <> ''" +
            "  UNION ALL" +
            "  SELECT REGEXP_REPLACE(LOWER(author), '[^a-zA-Z0-9]', '', 'g')" +
            "    FROM youtube_comments WHERE author IS NOT NULL AND author <> ''" +
            "  UNION ALL" +
            "  SELECT REGEXP_REPLACE(LOWER(author), '[^a-zA-Z0-9]', '', 'g')" +
            "    FROM reddit_posts     WHERE author IS NOT NULL AND author <> ''" +
            "  UNION ALL" +
            "  SELECT REGEXP_REPLACE(LOWER(author), '[^a-zA-Z0-9]', '', 'g')" +
            "    FROM instagram_posts  WHERE author IS NOT NULL AND author <> ''" +
            ") combined " +
            "WHERE normalized_author <> ''";

    private static final String INSERT_SQL =
            "INSERT INTO user_identity_link (global_user_id, normalized_author) " +
            "VALUES (?, ?) ON CONFLICT (normalized_author) DO NOTHING";

    @PostConstruct
    public void init() {
        jdbcTemplate.execute(CREATE_TABLE_SQL);
    }

    @Transactional
    public int resolveIdentities() {
        List<String> normalizedAuthors = jdbcTemplate.queryForList(COLLECT_AUTHORS_SQL, String.class);
        if (normalizedAuthors.isEmpty()) {
            return 0;
        }

        List<Object[]> batchArgs = new ArrayList<>(normalizedAuthors.size());
        for (String normalized : normalizedAuthors) {
            batchArgs.add(new Object[]{"user-" + UUID.randomUUID(), normalized});
        }
        int[] results = jdbcTemplate.batchUpdate(INSERT_SQL, batchArgs);
        return Arrays.stream(results).map(r -> r > 0 ? r : 0).sum();
    }
}
