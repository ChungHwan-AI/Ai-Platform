# rag-backend/config_chroma.py
import os
from pathlib import Path
from langchain_community.vectorstores import Chroma

def _resolve_chroma_dir():
    env = os.getenv("CHROMA_DIR", "chroma_db")  # 기본값 폴더명만
    p = Path(env).expanduser()
    if not p.is_absolute():
        # 파일 기준(project/rag-backend)로 고정
        base = Path(__file__).parent
        p = (base / env)
    p.mkdir(parents=True, exist_ok=True)
    return str(p.resolve())

CHROMA_DIR = _resolve_chroma_dir()
CHROMA_COLLECTION = os.getenv("CHROMA_COLLECTION", "oneask_docs")

def get_vectordb(embedding_fn):
    print(f"[Chroma] dir={CHROMA_DIR} collection={CHROMA_COLLECTION}")
    return Chroma(
        collection_name=CHROMA_COLLECTION,
        persist_directory=CHROMA_DIR,
        embedding_function=embedding_fn,
    )
