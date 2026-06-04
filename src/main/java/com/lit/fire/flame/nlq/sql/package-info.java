/**
 * NL&rarr;SQL prompting, read-only validation, and execution for the Ask engine.
 *
 * <p>Builds the prompt that turns a question + schema into SQL, then validates the result is a
 * single read-only statement ({@code SELECT}/{@code WITH} only) that does not touch any
 * skip-listed table/column, and executes it against the isolated target connection under the
 * configured row cap and query timeout.
 */
package com.lit.fire.flame.nlq.sql;
