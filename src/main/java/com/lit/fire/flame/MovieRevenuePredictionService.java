package com.lit.fire.flame;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Feature 9: shells out to {@code scripts/predict_movie.py} for a single
 * upcoming-movie revenue prediction. The model itself is a Python job (the
 * champion artifact Feature 8 persists via joblib, plus Feature 4's
 * disclosure classifier) -- this is the first {@link ProcessBuilder} bridge
 * in the Java app, the same shape {@link MovieRevenuePredictionScheduler}
 * (Feature 10) reuses for the weekly full-corpus batch re-scoring job --
 * both go through {@link MovieRevenueModelDbConnectionDetails} rather than
 * each parsing {@code secrets.txt} a second time.
 *
 * {@code predict_movie.py} is written to keep its progress/diagnostic
 * messages on stderr and print exactly one JSON result line to stdout, so
 * this service can capture just the result even when other messages were
 * printed along the way.
 */
@Service
public class MovieRevenuePredictionService {

    private static final Logger log = LoggerFactory.getLogger(MovieRevenuePredictionService.class);

    private final ObjectMapper objectMapper;
    private final String pythonExecutable;
    private final String scriptPath;
    private final long timeoutSeconds;

    public MovieRevenuePredictionService(
            ObjectMapper objectMapper,
            @Value("${movie.revenue.model.python:python3}") String pythonExecutable,
            @Value("${movie.revenue.model.predict-script:scripts/predict_movie.py}") String scriptPath,
            @Value("${movie.revenue.model.predict-timeout-seconds:120}") long timeoutSeconds) {
        this.objectMapper = objectMapper;
        this.pythonExecutable = pythonExecutable;
        this.scriptPath = scriptPath;
        this.timeoutSeconds = timeoutSeconds;
    }

    public static class PredictionException extends RuntimeException {
        public PredictionException(String message) {
            super(message);
        }
        public PredictionException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Runs {@code predict_movie.py --from-json -}, feeding {@code attrs} as
     * the JSON payload on stdin, and returns the parsed JSON result. Throws
     * {@link PredictionException} on a non-zero exit code or unparseable
     * output -- the caller (the controller) turns that into a 4xx/5xx.
     */
    public Map<String, Object> predict(Map<String, Object> attrs) {
        MovieRevenueModelDbConnectionDetails db;
        try {
            db = MovieRevenueModelDbConnectionDetails.load();
        } catch (IllegalStateException e) {
            throw new PredictionException(e.getMessage(), e);
        }
        ProcessBuilder pb = new ProcessBuilder(
            pythonExecutable, scriptPath,
            "--db-host", db.host, "--db-port", db.port, "--db-name", db.name,
            "--db-user", db.user, "--db-password", db.password,
            "--from-json", "-");
        pb.redirectErrorStream(false);

        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            throw new PredictionException("Failed to start " + pythonExecutable + " " + scriptPath, e);
        }

        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(attrs);
        } catch (Exception e) {
            process.destroyForcibly();
            throw new PredictionException("Failed to serialize request body to JSON", e);
        }

        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();
        try (OutputStream stdin = process.getOutputStream()) {
            stdin.write(payloadJson.getBytes(StandardCharsets.UTF_8));
            stdin.flush();
        } catch (IOException e) {
            log.warn("Failed writing predict_movie.py stdin payload: {}", e.getMessage());
        }

        // Drain stdout/stderr on separate threads while waiting -- predict_movie.py's
        // DB round-trips over the full historical corpus can produce enough
        // stderr progress output to fill the pipe buffer and deadlock the
        // subprocess if it isn't being read concurrently with the wait below.
        Thread stdoutReader = drainStreamAsync(process.getInputStream(), stdout);
        Thread stderrReader = drainStreamAsync(process.getErrorStream(), stderr);

        boolean finished;
        try {
            finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new PredictionException("Interrupted while waiting for predict_movie.py", e);
        }
        if (!finished) {
            process.destroyForcibly();
            throw new PredictionException(
                "predict_movie.py timed out after " + timeoutSeconds + "s");
        }
        joinQuietly(stdoutReader);
        joinQuietly(stderrReader);

        if (!stderr.isEmpty()) {
            log.info("predict_movie.py stderr:\n{}", stderr);
        }
        if (process.exitValue() != 0) {
            throw new PredictionException(
                "predict_movie.py exited " + process.exitValue() + ": " + stderr);
        }

        String resultLine = lastNonBlankLine(stdout.toString());
        if (resultLine == null) {
            throw new PredictionException("predict_movie.py produced no output on stdout");
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> result = objectMapper.readValue(resultLine, Map.class);
            return result;
        } catch (Exception e) {
            throw new PredictionException("Could not parse predict_movie.py output as JSON: " + resultLine, e);
        }
    }

    private Thread drainStreamAsync(InputStream in, StringBuilder into) {
        Thread t = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    into.append(line).append('\n');
                }
            } catch (IOException e) {
                log.debug("Stream drain ended: {}", e.getMessage());
            }
        });
        t.setDaemon(true);
        t.start();
        return t;
    }

    private void joinQuietly(Thread t) {
        try {
            t.join(TimeUnit.SECONDS.toMillis(5));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String lastNonBlankLine(String text) {
        String[] lines = text.split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            if (!lines[i].isBlank()) {
                return lines[i];
            }
        }
        return null;
    }

}
