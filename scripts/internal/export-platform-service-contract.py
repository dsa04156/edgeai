"""Export/review the original FastAPI boundary independently of the Spring API."""
import importlib.util
import json
import sys
from pathlib import Path
root = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("workflow_api", root / "platform-service/argocdAPI/workflow_api.py")
module = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = module
spec.loader.exec_module(module)
text = json.dumps(module.app.openapi(), ensure_ascii=False, indent=2) + "\n"
target = root / "contracts/openapi/platform-service-api.json"
if "--check" in sys.argv:
    if not target.exists() or target.read_text() != text:
        raise SystemExit("Platform-Service contract differs; regenerate and review it")
else:
    target.write_text(text)
