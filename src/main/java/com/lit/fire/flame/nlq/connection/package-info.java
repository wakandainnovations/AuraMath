/**
 * Per-request target datasource construction and isolation for the Ask engine.
 *
 * <p>Connections to the database being questioned are supplied per-request (Postgres, SQLite,
 * or MySQL) and must be kept fully separate from AuraMath's own
 * {@link com.lit.fire.flame.DataSourceConfig} datasource. Read-only connection flags and
 * connection/query timeouts are applied here.
 */
package com.lit.fire.flame.nlq.connection;
