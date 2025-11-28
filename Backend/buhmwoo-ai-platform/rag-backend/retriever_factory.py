"""retriever_factory.py

환경 변수에 따라 LangChain Retriever 전략을 선택적으로 구성하는 모듈.
"""

from __future__ import annotations

import logging  # 잘못된 환경 변수 입력을 기록하기 위해 로깅 모듈을 사용함
import os
from typing import Any, Dict

from langchain_community.vectorstores import Chroma  # VectorStore 구현체 타입 힌트 제공
from langchain_core.retrievers import BaseRetriever  # 반환 타입을 명확히 하기 위한 베이스 클래스


logger = logging.getLogger(__name__)  # 모듈 전용 로거를 준비해 설정 상태를 기록함


def _parse_int(env_key: str, default: int) -> int:
    """환경 변수에서 정수를 읽어오되 실패 시 기본값을 제공"""

    raw_value = os.getenv(env_key)
    if raw_value is None:
        return default

    try:
        return int(raw_value)
    except ValueError:
        # 잘못된 입력은 무시하고 기본값을 사용하되 로그로 원인을 남긴다
        logger.warning("%s 환경 변수의 값 '%s'을(를) 정수로 변환할 수 없어 기본값 %s을(를) 사용합니다.", env_key, raw_value, default)
        return default


def _parse_float(env_key: str, default: float) -> float:
    """환경 변수에서 실수를 읽어오되 실패 시 기본값을 제공"""

    raw_value = os.getenv(env_key)
    if raw_value is None:
        return default

    try:
        return float(raw_value)
    except ValueError:
        # 변환 실패 시에도 애플리케이션이 동작하도록 기본값으로 대체한다
        logger.warning("%s 환경 변수의 값 '%s'을(를) 실수로 변환할 수 없어 기본값 %s을(를) 사용합니다.", env_key, raw_value, default)
        return default


def _normalize_strategy_name(raw_strategy: str | None) -> str:
    """환경 변수에서 넘어온 검색 전략 이름을 정규화"""

    if not raw_strategy:
        return "similarity"

    normalized = raw_strategy.strip().lower()
    if normalized not in {"similarity", "mmr", "similarity_score_threshold"}:
        logger.warning("지원하지 않는 RETRIEVER_STRATEGY='%s'가 입력되어 기본 전략을 사용합니다.", raw_strategy)
        return "similarity"
    return normalized


def _build_base_kwargs(top_k: int, metadata_filter: Dict[str, Any] | None) -> Dict[str, Any]:
    """Retriever 공통 search_kwargs 구성을 생성"""

    base_kwargs: Dict[str, Any] = {"k": top_k}
    if metadata_filter:
        # 메타데이터 필터가 존재하면 검색 범위를 제한할 수 있도록 전달한다
        base_kwargs["filter"] = metadata_filter
    return base_kwargs


def build_retriever(
    *,
    vectordb: Chroma,
    top_k: int,
    metadata_filter: Dict[str, Any] | None,
) -> BaseRetriever:
    """Chroma VectorStore에서 환경 변수 기반 Retriever 인스턴스를 생성"""

    # 점수 계산과 Retriever 생성에 동일한 설정을 재사용하기 위해 전략/검색 파라미터를 한 번에 계산한다
    strategy, search_kwargs = resolve_strategy(top_k=top_k, metadata_filter=metadata_filter)

    if strategy == "mmr":
        # MMR 전략의 fetch_k, lambda_mult 값은 resolve_strategy 에서 이미 정규화되었으므로 그대로 사용한다
        logger.info("Retriever 전략(MMR) 적용: fetch_k=%s lambda_mult=%s", search_kwargs["fetch_k"], search_kwargs["lambda_mult"])
        return vectordb.as_retriever(search_type="mmr", search_kwargs=search_kwargs)

    if strategy == "similarity_score_threshold":
        # score_threshold 역시 resolve_strategy 에서 기본값을 포함해 계산해두었으므로 그대로 전달한다
        logger.info("Retriever 전략(Score Threshold) 적용: threshold=%s", search_kwargs["score_threshold"])
        return vectordb.as_retriever(
            search_type="similarity_score_threshold",
            search_kwargs=search_kwargs,
        )

    # 지정되지 않았거나 지원하지 않는 경우 기본 similarity 검색을 사용한다
    logger.info("Retriever 전략(Similarity) 적용: k=%s", search_kwargs["k"])
    return vectordb.as_retriever(search_type="similarity", search_kwargs=search_kwargs)


def resolve_strategy(*, top_k: int, metadata_filter: Dict[str, Any] | None) -> tuple[str, Dict[str, Any]]:
    """환경 변수 기반 검색 전략과 search_kwargs 를 한 번에 계산"""

    # 환경 변수 RETRIEVER_STRATEGY로 검색 방식을 결정한다 (기본값: similarity)
    strategy = _normalize_strategy_name(os.getenv("RETRIEVER_STRATEGY"))
    base_kwargs = _build_base_kwargs(top_k=top_k, metadata_filter=metadata_filter)

    if strategy == "mmr":
        # mmr 방식은 fetch_k와 lambda_mult 파라미터가 필요하므로 환경 변수로 튜닝할 수 있게 한다
        fetch_k_default = max(top_k * 2, top_k)
        fetch_k = _parse_int("RETRIEVER_FETCH_K", fetch_k_default)
        lambda_mult = _parse_float("RETRIEVER_MMR_LAMBDA", 0.5)
        search_kwargs = {**base_kwargs, "fetch_k": max(fetch_k, top_k), "lambda_mult": lambda_mult}
        return strategy, search_kwargs

    if strategy == "similarity_score_threshold":
        # 유사도 점수 임계치를 활용하는 전략으로, 점수는 0~1 범위로 가정한다
        score_threshold = _parse_float("RETRIEVER_SCORE_THRESHOLD", 0.5)
        search_kwargs = {**base_kwargs, "score_threshold": score_threshold}
        return strategy, search_kwargs

    # 지정되지 않았거나 지원하지 않는 경우 기본 similarity 검색을 사용한다
    return strategy, base_kwargs