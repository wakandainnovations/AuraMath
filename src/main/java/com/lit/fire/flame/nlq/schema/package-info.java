/**
 * Database schema introspection and skip-list filtering for the Ask engine.
 *
 * <p>Reads tables, columns, types, and relationships from a target connection and renders a
 * compact schema description for the LLM. Tables/columns named in a request's skip-list are
 * excluded here before the schema is ever shown to the model.
 */
package com.lit.fire.flame.nlq.schema;
