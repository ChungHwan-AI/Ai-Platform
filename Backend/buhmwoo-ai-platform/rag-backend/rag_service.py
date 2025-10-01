# rag-backend/rag_service.py
from typing import List
from langchain_core.documents import Document
from config_embed import get_embedding_fn
from config_chroma import get_vectordb

# TODO: 필요하면 여기서 청킹 로직 교체(현재는 docs 그대로 저장)
def chunk_docs(raw_texts: List[str], metadatas: List[dict] | None = None) -> List[Document]:
    if metadatas is None:
        metadatas = [{} for _ in raw_texts]
    return [Document(page_content=t, metadata=m) for t, m in zip(raw_texts, metadatas)]

def ingest_texts(texts: List[str], metadatas: List[dict] | None = None) -> int:
    embedding_fn = get_embedding_fn()
    vectordb = get_vectordb(embedding_fn)
    docs = chunk_docs(texts, metadatas)
    vectordb.add_documents(docs)
    vectordb.persist()
    # count 확인 (LangChain은 count 직접 제공X → chromadb 백엔드를 경유)
    try:
        from chromadb import PersistentClient
        import os
        from config_chroma import CHROMA_DIR, CHROMA_COLLECTION
        client = PersistentClient(path=CHROMA_DIR)
        col = client.get_or_create_collection(CHROMA_COLLECTION)
        return col.count()
    except Exception:
        return -1

def query_text(q: str, k: int = 5):
    embedding_fn = get_embedding_fn()
    vectordb = get_vectordb(embedding_fn)
    retriever = vectordb.as_retriever(search_kwargs={"k": k})
    return retriever.get_relevant_documents(q)
