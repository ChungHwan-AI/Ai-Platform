# rag-backend/rag_service.py
from typing import List
from langchain_core.documents import Document
from config_embed import get_embedding_fn
from config_chroma import get_vectordb  # LangChain Chroma 래퍼를 통해 컬렉션 핸들을 얻기 위해 임포트함
from retriever_factory import resolve_strategy  # 검색 전략/파라미터를 한 번만 계산해 점수 계산과 Retriever 설정을 통일하기 위해 임포트함
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
    # 검색 전략별 점수 계산 로직과 Retriever 구성을 동일하게 적용하기 위해 설정을 한 번만 계산한다
    strategy, search_kwargs = resolve_strategy(top_k=k, metadata_filter=metadata_filter)

    if strategy == "mmr":
        # max_marginal_relevance_search_with_score 는 (Document, score) 튜플을 반환하므로 점수를 메타데이터에 주입해준다
        results = vectordb.max_marginal_relevance_search_with_score(
            q,
            k=search_kwargs.get("k", k),
            fetch_k=search_kwargs.get("fetch_k"),
            lambda_mult=search_kwargs.get("lambda_mult"),
            filter=search_kwargs.get("filter"),
        )
    else:
        # similarity / similarity_score_threshold 전략 모두 relevance score 를 함께 반환하는 API를 사용한다
        similarity_kwargs = {
            "k": search_kwargs.get("k", k),
            "filter": search_kwargs.get("filter"),
        }
        if strategy == "similarity_score_threshold":
            similarity_kwargs["score_threshold"] = search_kwargs.get("score_threshold")

        results = vectordb.similarity_search_with_relevance_scores(q, **similarity_kwargs)

    # 검색 점수를 메타데이터에 포함시켜 호출측에서 confidence 기준을 적용할 수 있게 한다
    docs_with_scores: List[Document] = []
    for doc, score in results:
        meta = dict(doc.metadata or {})
        meta["score"] = float(score) if score is not None else None  # 검색 점수를 숫자 형태로 보존한다
        docs_with_scores.append(Document(page_content=doc.page_content, metadata=meta))

    return docs_with_scores
