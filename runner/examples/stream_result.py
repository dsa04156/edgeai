"""Materialize the reference sum's sealed state as a final artifact."""
import json
import os
from pathlib import Path

state = Path(os.environ['EDGEAI_STATE_FILE']).read_bytes()
result = {'sum': int(state or b'0')}
Path(os.environ['EDGEAI_OUTPUT_DIR'],'result').write_text(json.dumps(result,separators=(',',':')))
