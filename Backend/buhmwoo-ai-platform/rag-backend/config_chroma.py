# rag-backend/config_chroma.py
import logging  # 벡터 스토어 초기화 과정을 로깅하기 위해 로깅 모듈을 임포트함
import os
import re  # 컬렉션 이름에 사용할 접미사를 안전하게 정규화하기 위한 모듈
from pathlib import Path
from langchain_community.vectorstores import Chroma # LangChain이 제공하는 Chroma 래퍼를 사용하기 위해 임포트함
from chromadb import PersistentClient  # 컬렉션 삭제 등 직접 제어를 위해 Chroma 기본 클라이언트를 임포트함

logger = logging.getLogger(__name__)  # 모듈 전용 로거를 생성해 상황별 정보를 출력

_COLLECTION_OVERRIDE = os.getenv("CHROMA_COLLECTION")  # 사용자가 직접 지정한 컬렉션 이름을 우선 저장
_DEFAULT_COLLECTION_BASE = "oneask_docs"  # 컬렉션 이름 기본 접두사를 상수로 관리

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
CHROMA_COLLECTION = _COLLECTION_OVERRIDE or _DEFAULT_COLLECTION_BASE  # 초기 컬렉션 이름을 환경 변수 또는 기본값으로 설정


def _sanitize_suffix(raw: str) -> str:
    """컬렉션 이름에 붙일 접미사를 안전하게 정규화"""

    normalized = re.sub(r"[^0-9a-zA-Z]+", "_", raw)  # 영숫자 외 문자를 밑줄로 치환
    normalized = normalized.strip("_")  # 앞뒤 불필요한 밑줄 제거
    return normalized or "default"  # 전부 제거된 경우를 대비한 폴백 값 반환


def _select_collection_name() -> str:
    """임베딩 백엔드 정보를 반영한 컬렉션 이름을 선택"""

    if _COLLECTION_OVERRIDE:
        return _COLLECTION_OVERRIDE  # 사용자가 지정했다면 그대로 사용

    try:
        from config_embed import get_embedding_backend_info  # 순환 참조를 피하기 위해 지연 임포트

        info = get_embedding_backend_info()  # 현재 임베딩 설정 정보를 조회
        suffix_source = info.resolved_backend or info.configured_backend  # 실제 사용 또는 설정된 백엔드명을 접미사 후보로 사용
    except Exception:
        suffix_source = None  # 조회 실패 시 기본값 사용

    if suffix_source:
        suffix = _sanitize_suffix(suffix_source)
        return f"{_DEFAULT_COLLECTION_BASE}_{suffix}"  # 백엔드별로 구분되는 컬렉션 이름 생성

    return _DEFAULT_COLLECTION_BASE  # 특별한 정보가 없다면 기본값 유지

def _ensure_collection_name() -> str:
    """임베딩 설정 변화에 따라 컬렉션 이름을 갱신"""

    global CHROMA_COLLECTION  # 전역 상태를 변경하기 위해 global 선언

    selected_collection = _select_collection_name()  # 현재 임베딩 상황에 맞는 컬렉션 이름을 계산
    if selected_collection != CHROMA_COLLECTION:
        logger.info(
            "Chroma 컬렉션 이름을 %s에서 %s로 업데이트합니다.",
            CHROMA_COLLECTION,
            selected_collection,
        )  # 임베딩 백엔드 변경으로 컬렉션이 바뀌었음을 로그로 안내
        CHROMA_COLLECTION = selected_collection  # 새로운 컬렉션 이름을 전역 상태로 반영

    return CHROMA_COLLECTION  # 항상 최신 컬렉션 이름을 반환


def get_current_collection_name() -> str:
    """다른 모듈에서 최신 컬렉션 이름을 참고할 때 사용"""

    return _ensure_collection_name()  # 내부적으로 최신 상태를 확인한 뒤 값을 돌려줌


def reset_collection() -> None:
    """기존 컬렉션을 삭제해 차원 불일치 시 자동 복구하도록 지원"""

    collection_name = get_current_collection_name()  # 삭제 대상 컬렉션 이름을 계산
    client = PersistentClient(path=CHROMA_DIR)  # 영속 디렉터리 기반 Chroma 클라이언트를 준비
    try:
        client.delete_collection(collection_name)  # 기존 컬렉션을 제거해 새 임베딩으로 다시 채울 수 있게 함
        logger.warning(
            "Chroma 컬렉션 %s 을(를) 삭제했습니다. 새 임베딩으로 다시 채우십시오.",
            collection_name,
        )
    except Exception as exc:
        logger.warning(
            "Chroma 컬렉션 %s 삭제 중 예외 발생: %s",
            collection_name,
            exc,
        )  # 컬렉션이 없거나 삭제 실패 시에도 기록만 남기고 흐름을 계속함


def get_chroma_settings() -> tuple[str, str]:
    """현재 영속 경로와 컬렉션 이름을 한 번에 반환"""

    return CHROMA_DIR, get_current_collection_name()


def get_vectordb(embedding_fn):    
    collection_name = _ensure_collection_name()  # 최신 임베딩 구성에 맞는 컬렉션 이름을 확보

    logger.info("[Chroma] dir=%s collection=%s", CHROMA_DIR, collection_name)  # 선택된 경로와 컬렉션을 기록
    return Chroma(        
        collection_name=collection_name,
        persist_directory=CHROMA_DIR,
        embedding_function=embedding_fn,
    )