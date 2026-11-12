"""Sync public achievement metadata and versioned fresh-database baselines."""
import argparse
import json
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument("--write", action="store_true")
parser.add_argument("--frontend", type=Path)
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
frontend = args.frontend or root.parent / "Mu-mirror-F"
catalog_dir = root / "src/main/resources/achievements"
catalog = json.loads((catalog_dir / "catalog.json").read_text(encoding="utf-8"))
if isinstance(catalog, dict):
    catalog = [item for filename in catalog["files"]
               for item in json.loads((catalog_dir / filename).read_text(encoding="utf-8"))]
public_fields = {"code", "name", "english", "condition", "motif", "group", "difficulty",
                 "starter", "hidden", "image", "atlas", "atlasIndex", "setName", "setDescription", "plating"}
preview = [{key: value for key, value in item.items() if key in public_fields}
           for item in catalog if item.get("enabled", True)]
schema_path = root / "src/main/resources/db/schema.sql"
schema = schema_path.read_text(encoding="utf-8")
for version, filename in [(16, "migration-v16-achievements.sql"),
                          (17, "migration-v17-achievement-progression.sql")]:
    migration_path = root / "src/main/resources/db" / filename
    if not migration_path.exists():
        raise SystemExit(f"Missing migration: {migration_path}")
    marker = f"-- BEGIN GENERATED ACHIEVEMENTS V{version}"
    end_marker = f"-- END GENERATED ACHIEVEMENTS V{version}"
    block = marker + "\n" + migration_path.read_text(encoding="utf-8").rstrip() + "\n" + end_marker
    if marker in schema:
        before, _, previous = schema.partition(marker)
        _, closing, after = previous.partition(end_marker)
        if not closing:
            raise SystemExit(f"Missing generated V{version} closing marker; refusing to overwrite schema.sql")
        schema = before + block + after
    else:
        schema = schema.rstrip() + "\n\n" + block + "\n"
outputs = {
    frontend / "src/preview/achievementCatalog.json": json.dumps(preview, ensure_ascii=False, indent=2) + "\n",
    schema_path: schema,
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
