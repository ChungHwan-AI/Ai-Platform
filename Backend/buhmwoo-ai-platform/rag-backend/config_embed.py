# rag-backend/config_embed.py
import os

def get_embedding_fn():
    backend = os.getenv("EMBEDDING_BACKEND", "hf").lower()
    if backend == "openai":
        from langchain_openai import OpenAIEmbeddings
        model = os.getenv("OPENAI_EMBED_MODEL", "text-embedding-3-small")
        return OpenAIEmbeddings(model=model)
    else:
        try:
            from langchain_huggingface import HuggingFaceEmbeddings
            model = os.getenv("HF_EMBED_MODEL", "sentence-transformers/all-MiniLM-L6-v2")
            return HuggingFaceEmbeddings(model_name=model)
        except Exception as e:
            # 내부망 등으로 실패 시 OpenAI로 폴백
            from langchain_openai import OpenAIEmbeddings
            model = os.getenv("OPENAI_EMBED_MODEL", "text-embedding-3-small")
            print(f"[WARN] HF embeddings init failed: {e} → fallback to OpenAI")
            return OpenAIEmbeddings(model=model)
