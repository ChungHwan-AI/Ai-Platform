"""임베딩 백엔드 선택 로직과 상태 조회 유틸리티."""

import logging  # 로깅을 사용하여 어떤 임베딩 백엔드가 선택되었는지 기록하기 위한 모듈
import os  # 환경 변수를 읽어 현재 설정된 임베딩 백엔드를 파악하기 위한 모듈
from dataclasses import asdict, dataclass  # 임베딩 상태를 구조화해 저장하기 위한 데이터클래스 도구 임포트
from typing import Optional, Tuple  # 선택적 타입과 튜플 리턴을 위해 typing 모듈 임포트

from dotenv import load_dotenv  # .env 파일의 환경 변수를 선반영하기 위해 dotenv 로더를 임포트함

# .env 파일에 API 키 등이 저장된 경우를 대비해 임포트 즉시 환경 변수를 로드함
load_dotenv()

# 모듈 레벨에서 로거를 생성하여 일관된 로그 출력을 제공
logger = logging.getLogger(__name__)


@dataclass
class EmbeddingBackendInfo:
    """임베딩 백엔드 구성 및 실제 사용 정보를 담는 데이터 구조"""

    configured_backend: str  # 환경 변수로 설정된 원래 백엔드 이름
    resolved_backend: Optional[str]  # 실제 로딩에 성공한 백엔드 이름 (아직 로드되지 않았다면 None)
    model: Optional[str]  # 실제 사용 중인 모델 이름 (아직 로드되지 않았다면 None)
    fallback: bool = False  # 폴백이 발생했는지 여부를 표시하는 플래그
    error: Optional[str] = None  # 폴백 원인이 된 오류 메시지를 저장하기 위한 필드


# 최근에 선택된 임베딩 상태를 캐시하여 API에서 재사용
_LAST_INFO: Optional[EmbeddingBackendInfo] = None  # 마지막으로 결정된 임베딩 백엔드 정보를 저장
_LAST_EMBEDDING = None  # 마지막으로 생성된 임베딩 객체를 캐시하여 불필요한 재초기화를 방지


def _resolve_embedding_backend() -> Tuple[EmbeddingBackendInfo, object]:
    """환경 변수에 따라 임베딩 객체와 상태 정보를 생성"""

    configured = os.getenv("EMBEDDING_BACKEND", "gemini").lower()  # 기본값을 Gemini로 바꿔 즉시 해당 백엔드를 사용하도록 구성
    if configured in {"openai", "azure_openai", "azure-openai"}:
        raise RuntimeError(
            "OpenAI 임베딩은 더 이상 지원하지 않습니다. "
            "EMBEDDING_BACKEND=gemini 또는 hf로 설정하세요."
        )    
    if configured == "gemini":
        from langchain_google_genai import GoogleGenerativeAIEmbeddings  # Gemini 임베딩 클래스를 지연 임포트

        model = os.getenv("GEMINI_EMBED_MODEL", "text-embedding-004")  # 사용할 Gemini 임베딩 모델명을 확인
        api_key = os.getenv("GEMINI_API_KEY") or os.getenv("GOOGLE_API_KEY")  # Gemini 호출에 필수인 API 키를 읽어옴
        if not api_key:
            raise RuntimeError("GEMINI_API_KEY 환경 변수가 설정되어 있지 않습니다.")  # API 키 없이 호출하면 명확한 오류를 발생시킴
        info = EmbeddingBackendInfo(
            configured_backend=configured,
            resolved_backend="gemini",
            model=model,
            fallback=False,
        )  # Gemini 백엔드가 곧바로 선택되었음을 기록
        logger.info(
            "EMBEDDING_BACKEND=gemini 선택 → GoogleGenerativeAIEmbeddings(%s) 사용",
            model,
        )  # Gemini 임베딩 사용 여부를 로그로 남김
        return info, GoogleGenerativeAIEmbeddings(model=model, google_api_key=api_key)  # 상태 정보와 함께 임베딩 객체를 반환

    try:
        from langchain_huggingface import HuggingFaceEmbeddings  # 허깅페이스 임베딩 클래스를 지연 임포트

        model = os.getenv("HF_EMBED_MODEL", "sentence-transformers/all-MiniLM-L6-v2")  # 사용할 허깅페이스 모델명을 확인
        info = EmbeddingBackendInfo(
            configured_backend=configured,
            resolved_backend="hf",
            model=model,
            fallback=False,
        )  # 허깅페이스 모델이 정상적으로 선택되었음을 기록
        logger.info(
            "EMBEDDING_BACKEND=hf 선택 → HuggingFaceEmbeddings(%s) 사용",
            model,
        )  # 허깅페이스 임베딩 사용 여부를 로그로 남김
        return info, HuggingFaceEmbeddings(model_name=model)  # 상태 정보와 함께 허깅페이스 임베딩 객체를 반환
    except Exception as exc:  # 허깅페이스 초기화가 실패한 경우 예외를 잡아 폴백을 수행
        from langchain_google_genai import GoogleGenerativeAIEmbeddings  # 폴백 대상인 Gemini 임베딩 클래스를 임포트

        model = os.getenv("GEMINI_EMBED_MODEL", "text-embedding-004")  # 폴백 시 사용할 Gemini 임베딩 모델명을 확인
        api_key = os.getenv("GEMINI_API_KEY") or os.getenv("GOOGLE_API_KEY")  # 폴백 시에도 Gemini 호출을 위해 API 키를 확인
        if not api_key:
            raise RuntimeError("HuggingFace 임베딩 실패 후 Gemini로 폴백하려 했으나 GEMINI_API_KEY가 없습니다.")  # 폴백이 불가능할 때 즉시 알림
        info = EmbeddingBackendInfo(
            configured_backend=configured,
            resolved_backend="gemini",
            model=model,
            fallback=True,
            error=str(exc),
        )  # 폴백이 발생했음을 상태 정보에 기록
        logger.warning(
            "HuggingFaceEmbeddings 초기화 실패(%s) → GoogleGenerativeAIEmbeddings(%s)로 폴백",
            exc,
            model,
        )  # 폴백 상황을 경고 로그로 기록
        return info, GoogleGenerativeAIEmbeddings(model=model, google_api_key=api_key)  # 상태 정보와 함께 폴백 임베딩 객체를 반환


def get_embedding_fn():
    """LangChain에서 사용할 임베딩 객체를 반환"""

    global _LAST_INFO, _LAST_EMBEDDING  # 캐시된 상태와 임베딩 객체를 사용하기 위해 전역 변수 선언
    if _LAST_EMBEDDING is not None:  # 이미 생성된 임베딩이 있다면 재사용
        return _LAST_EMBEDDING

    info, embedding = _resolve_embedding_backend()  # 실제 임베딩 객체와 상태 정보를 생성
    _LAST_INFO = info  # 상태 정보를 캐시에 저장하여 이후 조회에서 활용
    _LAST_EMBEDDING = embedding  # 생성된 임베딩 객체를 캐시에 저장하여 재사용 비용을 줄임
    return embedding  # LangChain 파이프라인에서 사용할 임베딩 객체 반환


def get_embedding_backend_info(force_refresh: bool = False) -> EmbeddingBackendInfo:
    """현재 설정 및 실제 사용 중인 임베딩 상태를 조회"""

    global _LAST_INFO, _LAST_EMBEDDING  # 캐시된 상태와 임베딩을 참조하기 위해 전역 변수 선언
    if force_refresh:  # 강제로 최신 상태를 확인하고 싶을 때 실행
        info, embedding = _resolve_embedding_backend()  # 현재 환경을 기준으로 임베딩을 새로 생성
        _LAST_INFO = info  # 갱신된 상태 정를 캐시에 저장
        _LAST_EMBEDDING = embedding  # 새 임베딩 객체를 캐시에 저장하여 즉시 사용 가능하게 함
        return info

    if _LAST_INFO is not None:  # 이전에 임베딩이 한 번이라도 로드되었다면 캐시된 결과를 반환
        return _LAST_INFO

    configured = os.getenv("EMBEDDING_BACKEND", "gemini").lower()  # 기본값이 Gemini임을 반영해 초기 설정을 일관되게 표시
    return EmbeddingBackendInfo(
        configured_backend=configured,
        resolved_backend=None,
        model=None,
        fallback=False,
        error=None,
    )  # 아직 임베딩이 초기화되지 않았음을 나타내는 기본 상태 반환


def get_embedding_backend_info_dict(force_refresh: bool = False) -> dict:
    """FastAPI 응답 등에서 사용하기 쉽도록 상태 정보를 dict 형태로 변환"""

    info = get_embedding_backend_info(force_refresh=force_refresh)  # 상태 정보를 조회하거나 새로 갱신
    return asdict(info)  # 데이터클래스를 사전 형태로 변환해 직렬화가 쉽도록 반환


def switch_to_gemini_embedding(model: Optional[str] = None) -> object:
    """코드에서 즉시 Gemini 임베딩으로 전환하고 객체를 돌려주는 헬퍼"""

    global _LAST_INFO, _LAST_EMBEDDING  # 캐시를 갱신하기 위해 전역 상태를 사용한다고 선언
    if model:
        os.environ["GEMINI_EMBED_MODEL"] = model  # 호출자가 모델명을 지정했다면 환경 변수로 기록해 일관성 있게 유지
    os.environ["EMBEDDING_BACKEND"] = "gemini"  # 환경 설정을 강제로 Gemini 로 고정해 이후 요청에서도 동일하게 동작
    info, embedding = _resolve_embedding_backend()  # Gemini 임베딩 객체와 갱신된 상태 정보를 재계산
    _LAST_INFO = info  # 최신 상태 정보를 캐시에 저장해 관리 도구 등에서 바로 확인 가능하게 함
    _LAST_EMBEDDING = embedding  # 방금 생성한 임베딩 객체를 캐시에 보관해 중복 생성을 방지
    return embedding  # LangChain 파이프라인에 바로 연결할 수 있도록 임베딩 객체를 반환
