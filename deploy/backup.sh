#!/usr/bin/env bash
# 备份：MySQL 逻辑全量（一致性快照，含触发器）+ 附件卷。默认保留 14 份。macOS / Linux 通用。
# 建议 crontab：15 3 * * * /path/to/Company-System/deploy/backup.sh >> /var/log/mold-backup.log 2>&1
# 恢复演练见 deploy/README.md（备份不演练等于没有备份）。
set -euo pipefail
cd "$(dirname "$0")"
KEEP="${MOLD_BACKUP_KEEP:-14}"
OUT="backups/$(date -u +%Y%m%dT%H%M%SZ)"
umask 077
mkdir -p "$OUT"
COMPOSE=(docker compose -f docker-compose.prod.yml)

"${COMPOSE[@]}" exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump --protocol=socket -uroot \
    --single-transaction --routines --triggers --events --set-gtid-purged=OFF --databases mold_dev' \
  | gzip -9 > "$OUT/mold_dev.sql.gz"

"${COMPOSE[@]}" exec -T backend tar -C /app/data -czf - files > "$OUT/files.tar.gz"

if command -v sha256sum >/dev/null 2>&1; then
  ( cd "$OUT" && sha256sum ./*.gz > SHA256SUMS )
else
  ( cd "$OUT" && shasum -a 256 ./*.gz > SHA256SUMS )
fi
echo "备份完成：$OUT"

# 只保留最近 KEEP 份
ls -1d backups/*/ 2>/dev/null | sort -r | tail -n +"$((KEEP + 1))" | while read -r old; do
  rm -rf "$old"
done
