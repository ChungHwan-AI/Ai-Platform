# list_collections_debug.py
import os, glob
from pathlib import Path
import chromadb

os.environ["CHROMA_DISABLE_TELEMETRY"] = "1"

CHROMA_DIR = os.getenv("CHROMA_DIR", "./chroma_db")
abs_dir = str(Path(CHROMA_DIR).expanduser().resolve())
print(f"[info] CHROMA_DIR(abs)={abs_dir}")

client = chromadb.PersistentClient(path=abs_dir)

cols = client.list_collections()
print("[info] collections:", [c.name for c in cols])

for c in cols:
    try:
        print(f" - {c.name}: count={c.count()}")
    except Exception as e:
        print(f" - {c.name}: count=? (error: {e})")
