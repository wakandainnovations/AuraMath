package com.lit.fire.flame;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Read-only queries over the F4 ({@code causal_precedence_edges} / {@code causal_precedence_chains}),
 * F5 ({@code nonobvious_lever_findings}) and F7 ({@code playbook_patterns}) tables. All three are
 * populated by standalone batch jobs that connect directly to the shared 'aura' Postgres DB
 * (aura-precedence-batch/precedence_pipeline.py, aura-lever-miner-batch/lever_miner_pipeline.py,
 * AuraService's python-batch-jobs/playbook_pattern_miner.py) - this service only reads what
 * they've already written; it never computes or persists any of these tables itself.
 */
@Service
public class CausalIntelQueryService {

    @Autowired private JdbcTemplate jdbc;

    /**
     * The (industry, language) cohort key for an entity, in the exact {@code "{industry}|{language}"}
     * format the precedence/lever/playbook batch jobs write (nulls normalized to "UNKNOWN", matching
     * precedence_pipeline.load_entity_cohorts / VmiComputationService's own CohortKey). Returns null
     * if the entity has no managed_entities row at all.
     */
    public String resolveCohort(long entityId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT industry, language FROM managed_entities WHERE id = ?", entityId);
        if (rows.isEmpty()) return null;
        Map<String, Object> row = rows.get(0);
        String industry = row.get("industry") != null ? row.get("industry").toString() : "UNKNOWN";
        String language = row.get("language") != null ? row.get("language").toString() : "UNKNOWN";
        return industry + "|" + language;
    }

    /** causal_precedence_chains rows for one cohort, strongest (highest path_score) first. */
    public List<Map<String, Object>> chainsForCohort(String cohort) {
        return jdbc.queryForList(
                "SELECT chain_json, path_score FROM causal_precedence_chains " +
                "WHERE cohort = ? ORDER BY path_score DESC",
                cohort);
    }

    /**
     * causal_precedence_edges rows for one cohort - used to attach n_entities_supporting (not
     * carried in causal_precedence_chains.chain_json) to each step of a chain.
     */
    public List<Map<String, Object>> edgesForCohort(String cohort) {
        return jdbc.queryForList(
                "SELECT from_series, to_series, lag_days, fdr_q_value, effect_size_r2, n_entities_supporting " +
                "FROM causal_precedence_edges WHERE cohort = ?",
                cohort);
    }

    /** The pooled ('ALL' cohort) nonobvious_lever_findings rows - F5 always pools given small per-cohort N. */
    public List<Map<String, Object>> nonobviousLeversPooled() {
        return jdbc.queryForList(
                "SELECT feature_name, test_statistic, p_value, fdr_q_value, direction, n_entities " +
                "FROM nonobvious_lever_findings WHERE cohort = 'ALL' ORDER BY fdr_q_value ASC");
    }

    /** playbook_patterns rows for one cohort, strongest (lowest fdr_q_value) first. */
    public List<Map<String, Object>> playbookForCohort(String cohort) {
        return jdbc.queryForList(
                "SELECT pattern_sequence, support_top_tier, support_bottom_tier, p_value, fdr_q_value, n_entities " +
                "FROM playbook_patterns WHERE cohort = ? ORDER BY fdr_q_value ASC",
                cohort);
    }
}
