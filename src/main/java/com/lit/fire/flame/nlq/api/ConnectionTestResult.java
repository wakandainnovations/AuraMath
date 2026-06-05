package com.lit.fire.flame.nlq.api;

/**
 * Outcome of {@code POST /api/ask/test-connection}. Deliberately contains no echo of the request
 * credentials — only whether the probe succeeded and what the target database reported.
 */
public class ConnectionTestResult {

    private final boolean connected;
    private final String databaseProductName;
    private final String databaseProductVersion;
    private final String error;

    private ConnectionTestResult(boolean connected, String databaseProductName,
                                 String databaseProductVersion, String error) {
        this.connected = connected;
        this.databaseProductName = databaseProductName;
        this.databaseProductVersion = databaseProductVersion;
        this.error = error;
    }

    public static ConnectionTestResult success(String productName, String productVersion) {
        return new ConnectionTestResult(true, productName, productVersion, null);
    }

    public static ConnectionTestResult failure(String error) {
        return new ConnectionTestResult(false, null, null, error);
    }

    public boolean isConnected() {
        return connected;
    }

    public String getDatabaseProductName() {
        return databaseProductName;
    }

    public String getDatabaseProductVersion() {
        return databaseProductVersion;
    }

    public String getError() {
        return error;
    }
}
