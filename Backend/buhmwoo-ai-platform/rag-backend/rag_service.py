# rag-backend/rag_service.py
from typing import List
from langchain_core.documents import Document
from config_embed import get_embedding_fn
from config_chroma import get_vectordb  # LangChain Chroma 래퍼를 통해 컬렉션 핸들을 얻기 위해 임포트함
from retriever_factory import build_retriever  # 검색 전략을 환경 변수 기반으로 선택하기 위한 헬퍼를 임포트함
from utils.chunking import (
    chunk_documents,
    DEFAULT_CHUNK_OVERLAP,
    DEFAULT_CHUNK_SIZE,
)  # 청킹 파라미터와 공통 로직을 재사용하기 위해 유틸 모듈을 임포트함

# 업로드 파이프라인과 동일한 규칙으로 텍스트를 분할하기 위해 유틸 함수 래퍼를 제공한다
def chunk_docs(
    raw_texts: List[str],
    metadatas: List[dict] | None = None,
    *,
    chunk_size: int = DEFAULT_CHUNK_SIZE,
    overlap: int = DEFAULT_CHUNK_OVERLAP,
) -> List[Document]:
    return chunk_documents(
        raw_texts,
        metadatas,
        chunk_size=chunk_size,
        overlap=overlap,
    )

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
    # 검색 전략별로 필요한 search_kwargs 구성을 build_retriever에서 담당하도록 위임한다
    retriever = build_retriever(
        vectordb=vectordb,
        top_k=k,
        metadata_filter=metadata_filter,
    )
    return retriever.get_relevant_documents(q)
