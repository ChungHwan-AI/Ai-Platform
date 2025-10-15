# rag-backend/rag_service.py
from typing import List
from langchain_core.documents import Document
from config_embed import get_embedding_fn
from config_chroma import get_vectordb  # LangChain Chroma 래퍼를 통해 컬렉션 핸들을 얻기 위해 임포트함

# TODO: 필요하면 여기서 청킹 로직 교체(현재는 docs 그대로 저장)
def chunk_docs(raw_texts: List[str], metadatas: List[dict] | None = None) -> List[Document]:
    if metadatas is None:
        metadatas = [{} for _ in raw_texts]
    return [Document(page_content=t, metadata=m) for t, m in zip(raw_texts, metadatas)]

def ingest_texts(texts: List[str], metadatas: List[dict] | None = None) -> int:
    embedding_fn = get_embedding_fn()
    vectordb = get_vectordb(embedding_fn)
    collection = vectordb._collection  # LangChain Chroma 래퍼에서 실제 컬렉션 핸들을 꺼내어 재사용
    docs = chunk_docs(texts, metadatas)
    vectordb.add_documents(docs)
    vectordb.persist()
    try:
        # 벡터 스토어가 가리키는 컬렉션에서 직접 카운트를 읽어 일관된 값을 반환한다
        return collection.count()
    except Exception:
        return -1

def query_text(q: str, k: int = 5, metadata_filter: dict | None = None):
    embedding_fn = get_embedding_fn()
    vectordb = get_vectordb(embedding_fn)
    search_kwargs = {"k": k}
    if metadata_filter:
        search_kwargs["filter"] = metadata_filter
    retriever = vectordb.as_retriever(search_kwargs=search_kwargs)    
    return retriever.get_relevant_documents(q)
