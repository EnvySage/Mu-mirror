"""Sync generated preview metadata and the v16 fresh-database baseline."""
import argparse
import json
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument("--write", action="store_true")
parser.add_argument("--frontend", type=Path)
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
frontend = args.frontend or root.parent / "Mu-mirror-F"
catalog = json.loads((root / "src/main/resources/achievements/catalog.json").read_text(encoding="utf-8"))
preview = [{key: value for key, value in item.items() if key not in {"rule", "ruleVersion", "enabled"}}
           for item in catalog if item.get("enabled", True)]
marker = "-- BEGIN GENERATED ACHIEVEMENTS V16"
schema_path = root / "src/main/resources/db/schema.sql"
schema = schema_path.read_text(encoding="utf-8")
migration = (root / "src/main/resources/db/migration-v16-achievements.sql").read_text(encoding="utf-8")
end_marker = "-- END GENERATED ACHIEVEMENTS V16"
suffix = "\n"
if marker in schema:
    schema, _, previous = schema.partition(marker)
    _, closing, suffix = previous.partition(end_marker)
    if not closing:
        raise SystemExit("Missing generated V16 closing marker; refusing to overwrite schema.sql")
schema = schema.rstrip()
outputs = {
    frontend / "src/preview/achievementCatalog.json": json.dumps(preview, ensure_ascii=False, indent=2) + "\n",
    schema_path: schema + "\n\n" + marker + "\n" + migration.rstrip() + "\n" + end_marker + suffix,
}
stale = []
for path, content in outputs.items():
    current = path.read_text(encoding="utf-8") if path.exists() else None
    if current == content:
        continue
    if args.write:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")
        print("Updated", path)
    else:
        stale.append(str(path))
if stale:
    raise SystemExit("Generated achievement data is stale. Run with --write:\n" + "\n".join(stale))
