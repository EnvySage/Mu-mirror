"""Exercise the public export boundary and repeatable migration-block generation."""
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest


class AchievementSyncTest(unittest.TestCase):
    def test_indexed_catalog_exports_only_public_fields_and_preserves_schema_suffix(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "backend"
            scripts = root / "scripts"
            scripts.mkdir(parents=True)
            shutil.copy(Path(__file__).resolve().parents[1] / "sync-achievements.py", scripts)
            resources = root / "src/main/resources"
            catalog = resources / "achievements"
            database = resources / "db"
            catalog.mkdir(parents=True)
            database.mkdir()
            (catalog / "catalog.json").write_text(json.dumps({"files": ["catalog-r.json"]}), encoding="utf-8")
            (catalog / "catalog-r.json").write_text(json.dumps([
                {"code": "R-04", "plating": True, "rule": {"secret": 10},
                 "platingRule": {"hiddenTarget": 100}, "internalFutureField": "private"},
                {"code": "R-05", "enabled": False}
            ]), encoding="utf-8")
            (database / "migration-v16-achievements.sql").write_text("SELECT 16;\n", encoding="utf-8")
            (database / "migration-v17-achievement-progression.sql").write_text("SELECT 17;\n", encoding="utf-8")
            schema = database / "schema.sql"
            schema.write_text("SELECT 1;\n-- BEGIN GENERATED ACHIEVEMENTS V16\nold\n"
                              "-- END GENERATED ACHIEVEMENTS V16\n-- keep following schema\n", encoding="utf-8")
            frontend = Path(directory) / "frontend"
            command = [sys.executable, str(scripts / "sync-achievements.py"), "--frontend", str(frontend)]
            subprocess.run(command + ["--write"], check=True, capture_output=True)
            generated = frontend / "src/preview/achievementCatalog.json"
            self.assertEqual([{"code": "R-04", "plating": True}], json.loads(generated.read_text(encoding="utf-8")))
            result = schema.read_text(encoding="utf-8")
            self.assertIn("-- keep following schema", result)
            self.assertIn("SELECT 16;", result)
            self.assertIn("SELECT 17;", result)
            subprocess.run(command, check=True, capture_output=True)
            subprocess.run(command + ["--write"], check=True, capture_output=True)
            self.assertEqual(result, schema.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
