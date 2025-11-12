# utils/chunking.py
"""문서 청킹과 관련된 공통 유틸리티를 모아 둔 모듈"""

from __future__ import annotations

from typing import Dict, List, Sequence

from langchain_core.documents import Document  # LangChain에서 사용하는 Document 타입을 재활용하기 위한 임포트

try:
    # LangChain 0.1 이후 버전에서는 텍스트 분할기가 별도 패키지로 이동했기 때문에 우선적으로 해당 모듈을 시도한다
    from langchain_text_splitters import (
        RecursiveCharacterTextSplitter,
    )  # 보다 다양한 분리 기준을 제공하는 재귀적 문자 분할기를 사용하기 위한 임포트
except ImportError:  # pragma: no cover - 환경에 따라 다른 모듈 경로를 사용할 수 있으므로 테스트에서 제외한다
    # 구버전 LangChain 호환을 위해 기존 경로에서 동일한 클래스를 가져온다
    from langchain.text_splitter import RecursiveCharacterTextSplitter  # type: ignore


# 제품 전반에서 동일한 기본 청크 크기와 겹침 폭을 사용하도록 상수를 정의한다
DEFAULT_CHUNK_SIZE = 800
DEFAULT_CHUNK_OVERLAP = 160


def chunk_text(
    text: str,
    *,
    chunk_size: int = DEFAULT_CHUNK_SIZE,
    overlap: int = DEFAULT_CHUNK_OVERLAP,
) -> List[str]:
    """주어진 원문 문자열을 의미 단위에 가깝게 분할해 리스트로 반환한다."""

    # 줄바꿈, 공백, 글자 단위 순으로 분할을 시도해 맥락을 최대한 보존하면서도 일정한 길이를 유지한다
    splitter = RecursiveCharacterTextSplitter(
        chunk_size=chunk_size,
        chunk_overlap=overlap,
        separators=["\n\n", "\n", " ", ""],
    )
    # 분할된 텍스트 블록을 그대로 반환해 호출자가 추가 가공을 수행할 수 있도록 한다
    return splitter.split_text(text)


def chunk_documents(
    raw_texts: Sequence[str],
    metadatas: Sequence[Dict[str, object]] | None = None,
    *,
    chunk_size: int = DEFAULT_CHUNK_SIZE,
    overlap: int = DEFAULT_CHUNK_OVERLAP,
) -> List[Document]:
    """여러 개의 원문과 메타데이터 쌍을 받아 LangChain Document 청크 목록으로 변환한다."""

    # 메타데이터가 명시되지 않으면 동일한 길이의 빈 딕셔너리를 만들어 필드 접근 시 KeyError를 방지한다
    if metadatas is None:
        metadatas = [{} for _ in raw_texts]

    documents: List[Document] = []
    for text, base_meta in zip(raw_texts, metadatas):
        # 각 텍스트를 분할한 결과를 순회하면서 chunk_index를 부여해 출처 추적을 돕는다
        for chunk_index, chunk in enumerate(
            chunk_text(text, chunk_size=chunk_size, overlap=overlap)
        ):
            metadata = dict(base_meta)
            metadata["chunk_index"] = chunk_index
            documents.append(
                Document(
                    page_content=chunk,
                    metadata=metadata,
                )
            )
    # 모든 텍스트 청킹이 끝나면 LangChain이 기대하는 Document 리스트를 반환한다
    return documents


def chunk_single_document(
    text: str,
    base_metadata: Dict[str, object] | None = None,
    *,
    chunk_size: int = DEFAULT_CHUNK_SIZE,
    overlap: int = DEFAULT_CHUNK_OVERLAP,
) -> List[Document]:
    """단일 문서와 메타데이터를 받아 재사용 가능한 Document 청크로 변환한다."""

    # 단일 문서에 대한 편의 함수로, 내부적으로 위에서 정의한 반복자 기반 함수를 재사용한다
    metadata_list: List[Dict[str, object]] = [base_metadata or {}]
    return chunk_documents(
        [text],
        metadata_list,
        chunk_size=chunk_size,
        overlap=overlap,
    )