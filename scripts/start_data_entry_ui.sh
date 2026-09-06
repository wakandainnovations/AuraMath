#!/usr/bin/env bash
# Starts the movie data-entry UI on http://localhost:3030/.
# Creates/reuses a project-local virtualenv (.venv) so this doesn't fight
# Homebrew's externally-managed-environment guard, and only installs
# dependencies when they're actually missing.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
VENV_DIR="$PROJECT_ROOT/.venv"

if [ ! -d "$VENV_DIR" ]; then
    echo "Creating virtual environment at $VENV_DIR..."
    python3 -m venv "$VENV_DIR"
fi

# shellcheck disable=SC1091
source "$VENV_DIR/bin/activate"

if ! python3 -c "import flask, psycopg2, joblib, numpy, pandas, sklearn" >/dev/null 2>&1; then
    echo "Installing dependencies into $VENV_DIR..."
    python3 -m pip install --quiet flask psycopg2-binary joblib numpy pandas scikit-learn
fi

exec python3 "$SCRIPT_DIR/data_entry_ui.py" \
    --db-host "${MOVIE_DB_HOST:-localhost}" \
    --db-port "${MOVIE_DB_PORT:-5432}" \
    --db-name "${MOVIE_DB_NAME:-aura}" \
    --db-user "${MOVIE_DB_USER:-$USER}" \
    --http-port 3030
