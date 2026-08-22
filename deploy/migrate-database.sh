#!/bin/sh

set -eu

script_root="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
migration_root="$script_root/migrations"
jdbc_url="${SPRING_DATASOURCE_URL:-jdbc:postgresql://127.0.0.1:5432/minikun}"
database_url="${jdbc_url#jdbc:}"
export PGUSER="${SPRING_DATASOURCE_USERNAME:-minikun}"
if [ -n "${SPRING_DATASOURCE_PASSWORD:-}" ]; then
  export PGPASSWORD="$SPRING_DATASOURCE_PASSWORD"
fi

command -v psql >/dev/null 2>&1 || {
  echo "psql is required to run Mini-kun database migrations" >&2
  exit 1
}

psql "$database_url" -v ON_ERROR_STOP=1 -q -c '
CREATE TABLE IF NOT EXISTS minikun_schema_migration (
    version VARCHAR(255) PRIMARY KEY,
    applied_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
)'

for migration in "$migration_root"/V*.sql; do
  [ -f "$migration" ] || continue
  version="$(basename "$migration" .sql)"
  case "$version" in
    *[!A-Za-z0-9_.-]*)
      echo "Invalid migration filename: $version" >&2
      exit 1
      ;;
  esac
  applied="$(psql "$database_url" -v ON_ERROR_STOP=1 -Atqc \
    "SELECT EXISTS (SELECT 1 FROM minikun_schema_migration WHERE version = '$version')")"
  if [ "$applied" = "t" ]; then
    echo "Already applied: $version"
    continue
  fi
  psql "$database_url" -v ON_ERROR_STOP=1 -1 -q \
    -f "$migration" \
    -c "INSERT INTO minikun_schema_migration(version) VALUES ('$version')"
  echo "Applied: $version"
done
