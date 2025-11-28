# main.py
# 표준 로깅 사용을 위한 import
import logging

import os  # LLM 공급자와 키 등 환경 변수 접근을 위해 os 모듈을 임포트함

from dataclasses import dataclass  # 프롬프트 컨텍스트 블록 정보를 구조화하기 위해 dataclass를 사용함

from fastapi import FastAPI, UploadFile, File, HTTPException, Form, Request  # 업로드 엔드포인트에서 폼 필드와 파일을 다루고 템플릿 렌더링에 필요한 Request 객체를 사용하기 위해 임포트함

from dotenv import load_dotenv  # .env 파일을 읽어오기 위한 함수 임포트
from pathlib import Path  # 업로드 파일 저장 경로를 다루기 위해 Path 클래스를 임포트함
from typing import List, Optional, Dict, Any  # 타입 힌트를 명확히 하기 위해 필요한 제네릭 타입들을 임포트함

from langchain_core.documents import Document  # 청크를 LangChain Document 형태로 저장하기 위해 Document 클래스를 임포트함

from langchain_core.prompts import ChatPromptTemplate  # LLM 프롬프트 생성을 위한 도구

from fastapi.responses import HTMLResponse  # 간단한 웹 UI를 제공하기 위해 HTML 응답 클래스를 임포트함
from fastapi.templating import Jinja2Templates  # Jinja 템플릿을 이용한 화면 렌더링을 위해 임포트함

from pydantic import (
    BaseModel,
    Field,
    ConfigDict,
    AliasChoices,
    model_validator,
)  # 요청/응답 스키마 정의 및 유효성 검증 훅 사용을 위해 Pydantic 유틸리티를 임포트함

from config_chroma import (
    get_vectordb,  # 벡터 스토어 핸들을 얻기 위한 함수
    get_chroma_settings,  # 현재 사용 중인 Chroma 경로와 컬렉션 이름을 조회하기 위한 헬퍼
    reset_collection,  # 차원 불일치 발생 시 컬렉션을 초기화하기 위한 헬퍼
)  # Chroma 벡터 DB 설정 정보를 불러오기 위해 관련 유틸을 임포트함
from config_embed import (
    get_embedding_fn,  # 임베딩 함수를 가져오기 위해 임포트함
    get_embedding_backend_info_dict,  # 현재 임베딩 백엔드 상태를 조회하기 위해 임포트함
)  # 임베딩 설정 조회 및 생성을 위해 관련 유틸을 임포트함
from utils.encoding import to_utf8_text  # 텍스트 인코딩을 UTF-8로 정규화하기 위해 임포트함
from utils.chunking import (
    chunk_single_document,
    DEFAULT_CHUNK_OVERLAP,
    DEFAULT_CHUNK_SIZE,
)  # 업로드 시 일관된 청킹 규칙을 적용하기 위해 공통 유틸을 임포트함
from admin_router import router as admin_router  # 관리자용 라우터를 메인 앱에 포함시키기 위해 임포트함

from rag_service import query_text  # 검색 서비스 모듈의 일관된 질의 헬퍼를 재사용하기 위해 임포트함


# 모듈 전역 로거
logger = logging.getLogger(__name__)

# Uvicorn 및 루트 로거가 INFO 이상으로 출력되도록 보정
if not logging.getLogger().handlers:
    # 기본 핸들러가 없을 때만 기본 설정을 적용
    logging.basicConfig(level=logging.INFO)
# Uvicorn 런타임 로그 레벨을 명시적으로 INFO로 맞춤
logging.getLogger("uvicorn").setLevel(logging.INFO)

@dataclass
class PromptContextChunk:
    """프롬프트 및 응답 모두에서 재사용할 검색 청크 정보를 표현하는 구조체"""

    reference: str  # LLM 응답 인용에 사용될 라벨 (예: [청크 1])
    header: str  # 프롬프트에 노출될 출처 요약 헤더
    body: str  # 청크 본문 텍스트
    metadata: Dict[str, Any]  # 원본 문서 메타데이터 사본
    source: str  # UX에서 표시할 주요 출처(파일명 또는 문서 ID)
    page: Optional[int]  # 페이지/슬라이드 등 위치 정보
    chunk_index: int  # 1부터 시작하는 청크 순번
    preview: str  # 프론트에서 사용할 간단한 스니펫 텍스트


# 시스템 메시지에 사용할 기본 지침 문자열 정의
PROMPT_SYSTEM_TEXT = (
    "당신은 사내 문서를 바탕으로 답을 제공하는 한국어 도우미입니다."
    "\n- 제공된 문서 근거를 벗어난 추측은 피하고, 근거가 없으면 솔직하게 모른다고 답변하세요."
    "\n- 쉬운 한국어를 사용하고, 전문 용어가 등장하면 짧은 풀이를 덧붙이세요."
    "\n- 모든 창의적 통찰과 예시는 반드시 문서 근거에서 출발했음을 명시하세요."
    "\n- 답변에는 반드시 다음 섹션을 포함하고 마크다운 헤더로 구분하세요: '## 핵심 요약', '## 사실에 기반한 창의적 통찰', '## 쉬운 비유나 예시'."
    "\n- '사실에 기반한 창의적 통찰'과 '쉬운 비유나 예시'에 등장하는 문장은 각 문장 끝에 관련 청크 인용을 [청크 N] 형태로 붙이세요."
    "\n- 근거를 찾을 수 없는 경우에는 '근거 부족'이라고 적고 추측을 덧붙이지 마세요."
)


# 사람이 이해하기 쉬운 예시/질문/참고 문서를 포함한 휴먼 메시지 템플릿을 정의
PROMPT_HUMAN_TEMPLATE = (
    "응답 형식 예시:\n"
    "## 핵심 요약\n- [청크 1] 핵심 사실 요약\n\n"
    "## 사실에 기반한 창의적 통찰\n- [청크 2] 통찰 내용\n\n"
    "## 쉬운 비유나 예시\n- [청크 3] 비유 또는 예시\n\n"
    "[질문]\n{question}\n\n"
    "[참고 문서]\n{context}\n\n"
    "위 자료를 참고해 독자가 쉽게 이해할 수 있는 답변을 작성하세요."
)


# LangChain ChatPromptTemplate으로 시스템/휴먼 메시지를 사전에 컴파일해둔다
PROMPT_TEMPLATE = ChatPromptTemplate.from_messages(
    [
        ("system", PROMPT_SYSTEM_TEXT),
        ("human", PROMPT_HUMAN_TEMPLATE),
    ]
)

# 일반 지식/업무 대화를 위해 RAG 컨텍스트가 없을 때 사용할 별도 시스템 메시지를 정의
PROMPT_SYSTEM_GENERAL = (
    "당신은 한국어로 답변하는 친절한 업무 비서입니다."
    "\n- 회사 문서가 없더라도 일반 상식과 논리를 활용해 문제를 해결하세요."
    "\n- 사실 근거가 부족하면 추측임을 명확히 밝히고, 안전하고 책임감 있게 답하세요."
    "\n- 사용자가 요청한 작업(요약, 번역, 일정 제안 등)을 그대로 수행하세요."
)

# RAG 컨텍스트 없이도 대화를 이어가기 위해 간단한 휴먼 템플릿을 별도로 둔다
PROMPT_HUMAN_GENERAL = (
    "사용자 요청: {question}\n"
    "참고 문서: {context}\n"
    "친절하게 한국어로 응답하세요."
)

# 일반 대화 프롬프트 템플릿을 사전에 컴파일해 재사용 비용을 줄인다
PROMPT_TEMPLATE_GENERAL = ChatPromptTemplate.from_messages(
    [
        ("system", PROMPT_SYSTEM_GENERAL),
        ("human", PROMPT_HUMAN_GENERAL),
    ]
)

class QueryRequest(BaseModel):
    """/query 요청 스키마 정의"""

    # 사용자의 자연어 질문 텍스트를 입력받는 필드
    question: str = Field(..., description="사용자가 던진 질문 문장")
    # 필요시 특정 문서(UUID 등)로 검색 범위를 제한하기 위한 필드
    doc_id: Optional[str] = Field(
        default=None,
        description="벡터 검색 대상을 제한할 문서 ID (선택)",
        alias="docId",
        validation_alias=AliasChoices("docId", "doc_id"),
    )
    # 벡터 검색에서 가져올 상위 청크 개수를 제어하는 필드
    top_k: int = Field(
        default=4,
        ge=1,
        le=20,
        description="벡터 검색으로 가져올 상위 청크 개수",
    )

    model_config = ConfigDict(
        populate_by_name=True,
        # Swagger 문서에 샘플 요청을 노출하기 위한 설정
        json_schema_extra={
            "examples": [
                {
                    "question": "이번 분기 생산 목표는 어떻게 되나요?",
                    "docId": "123e4567-e89b-12d3-a456-426614174000",
                    "top_k": 3,
                }
            ]
        }
    )


class Match(BaseModel):
    """벡터 검색 결과 청크 정보를 담는 스키마"""

    # LLM 응답과 프론트엔드 인용에 사용할 고유 라벨 (예: [청크 1])
    reference: str = Field(..., description="LLM 응답 인용에 사용할 청크 라벨")
    # 청크 순번(1부터 시작). camelCase 입력도 허용하기 위해 validation_alias 를 지정한다.
    chunk_index: int = Field(
        ...,  # 검색 결과에 반드시 존재해야 하는 필드이므로 필수로 지정한다
        ge=1,
        alias="chunkIndex",
        validation_alias=AliasChoices("chunkIndex", "chunk_index"),
        description="검색 결과 순번 (1부터 시작)",
    )    
    # 청크의 실제 본문 텍스트를 그대로 노출
    content: str = Field(..., description="검색된 청크 본문")
    # 프론트엔드에서 목록 형태로 노출할 때 사용할 간략한 스니펫
    preview: str = Field(..., description="줄바꿈을 제거하고 길이를 제한한 미리보기 텍스트")
    # UX 에서 출처를 명확히 보여주기 위한 별도 필드
    source: str = Field(..., description="원본 문서 이름 또는 ID")
    # 페이지/슬라이드 등의 위치를 전달하기 위한 선택적 필드
    page: Optional[int] = Field(
        default=None,
        description="문서 내 위치 정보 (해당하지 않으면 null)",
    )
    # 추후 출처, 페이지 등의 메타데이터를 전달하기 위한 필드
    metadata: Dict[str, Any] = Field(
        default_factory=dict,
        description="청크에 연결된 원본 메타데이터",
    )

model_config = ConfigDict(populate_by_name=True)  # chunk_index 키로도 값을 설정할 수 있도록 허용한다.

class QueryResponse(BaseModel):
    """/query 응답 스키마 정의"""

    # LLM이 최종적으로 생성한 답변 텍스트를 담는 필드
    answer: str = Field(..., description="LLM이 생성한 최종 응답")
    # 참고용으로 검색된 청크 목록을 그대로 반환
    matches: List[Match] = Field(
        default_factory=list,
        description="벡터 검색으로 선택된 청크 목록",
    )

class RetrieveResponse(BaseModel):
    """/query/retrieve 응답 스키마 정의"""

    matches: List[Match] = Field(
        default_factory=list,
        description="벡터 검색으로 선택된 청크 목록",
    )
    context: str = Field(..., description="GPT 프롬프트에 사용할 컨텍스트 전체 텍스트")


class GenerateRequest(BaseModel):
    """/query/generate 요청 스키마 정의"""

    question: str = Field(..., description="사용자가 던진 질문 문장")
    context: str = Field(..., description="벡터 검색 결과를 하나의 텍스트로 결합한 컨텍스트")


class GenerateResponse(BaseModel):
    """/query/generate 응답 스키마 정의"""

    answer: str = Field(..., description="LLM이 생성한 최종 응답")

class RagHealthResponse(BaseModel):
    """RAG 백엔드의 기본 구성 상태를 한눈에 보여주는 헬스체크 응답"""

    ready: bool = Field(..., description="핵심 의존성이 모두 준비되었는지 여부")
    llm_provider: str = Field(..., alias="llmProvider", description="선택된 LLM 공급자 이름")
    llm_ready: bool = Field(..., alias="llmReady", description="LLM 호출 준비 여부")
    llm_error: Optional[str] = Field(
        default=None, alias="llmError", description="LLM 설정 문제 발생 시 사유"
    )
    embedding: Dict[str, Any] = Field(
        default_factory=dict, description="임베딩 백엔드 상태 요약"
    )
    chroma: Dict[str, Any] = Field(
        default_factory=dict, description="Chroma 영속 디렉터리 및 컬렉션 상태"
    )

# 문서 삭제 요청을 위한 스키마 정의
class DocDeleteRequest(BaseModel):
    """벡터 DB에서 삭제할 문서 조건을 표현하는 모델"""

    # 업로드 당시 부여한 문서 UUID. 값이 존재하면 source 보다 우선 적용한다.
    doc_id: Optional[str] = Field(
        default=None,
        alias="docId",
        description="삭제할 문서의 UUID",
        validation_alias=AliasChoices("docId", "doc_id"),
    )
    # 문서 업로드 시 기록한 원본 파일명. docId 가 없을 때 보조 키로 활용한다.
    source: Optional[str] = Field(
        default=None,
        description="삭제할 문서를 찾기 위한 파일명",
    )

    model_config = ConfigDict(populate_by_name=True)  # camelCase 요청도 허용하도록 설정한다.

    @model_validator(mode="after")
    def validate_identifier(cls, values: "DocDeleteRequest") -> "DocDeleteRequest":
        """docId 와 source 가 모두 비어 있으면 요청 자체를 거부한다."""

        if not (values.doc_id or values.source):
            raise ValueError("docId 또는 source 중 하나는 반드시 전달되어야 합니다.")
        return values
    
def _prepare_prompt_context(docs: List[Document]) -> tuple[List[PromptContextChunk], str, bool]:
    """검색된 청크를 프롬프트와 API 응답 모두에서 활용 가능한 형태로 가공"""

    context_chunks: List[PromptContextChunk] = []  # 프롬프트/응답에서 재사용할 가공 결과를 담는 리스트
    context_blocks: List[str] = []  # ChatPromptTemplate에 전달할 순수 텍스트 블록 모음
    has_docs = False  # 컨텍스트가 비었는지 여부를 별도 플래그로 표시해 후속 로직에서 일반 대화로 전환할지 결정함

    for idx, doc in enumerate(docs, start=1):
        # 한 건이라도 검색되면 일반 대화 대신 RAG 컨텍스트 기반 답변을 사용하도록 플래그를 세팅함
        has_docs = True        
        # LangChain Document 메타데이터를 복사해 변형 과정에서 원본이 손상되지 않도록 한다
        meta = dict(doc.metadata or {})
        source_raw = meta.get("source") or meta.get("docId") or "unknown"
        source = str(source_raw)
        page_value = meta.get("page")
        if isinstance(page_value, (int, float)):
            page = int(page_value)
        elif isinstance(page_value, str) and page_value.isdigit():
            page = int(page_value)
        else:
            page = None

        reference = f"[청크 {idx}]"
        header = f"{reference} 출처: {source}"
        if page is not None:
            header += f" | 페이지: {page}"
        # 공백을 정리한 본문과 스니펫을 각각 준비한다
        body = doc.page_content.strip()
        snippet = " ".join(body.split())
        if len(snippet) > 160:
            snippet = snippet[:157] + "..."

        context_blocks.append(f"{header}\n{body}")
        context_chunks.append(
            PromptContextChunk(
                reference=reference,
                header=header,
                body=body,
                metadata=meta,
                source=source,
                page=page,
                chunk_index=idx,
                preview=snippet,
            )
        )

    if not context_blocks:
        # 검색된 청크가 없을 때는 안내 문구를 넣어 LLM이 상황을 명확히 이해하도록 돕는다        
        context_blocks.append("(참고할 문서를 찾지 못했습니다.)")

    context_text = "\n\n".join(context_blocks)
    return context_chunks, context_text, has_docs

def _run_retrieval_with_recovery(
    *,
    question: str,
    top_k: int,
    metadata_filter: Dict[str, Any] | None,
):
    """retriever 실행 시 임베딩 차원 불일치 등을 감지해 자동 복구."""

    try:
        return query_text(question, k=top_k, metadata_filter=metadata_filter)
    except ValueError as err:
        message = str(err)
        if "dimensionality" in message:
            logger.warning(
                "임베딩 차원 불일치 감지 → Chroma 컬렉션 초기화 후 재검색을 시도합니다."
            )
            try:
                reset_collection()
                return query_text(question, k=top_k, metadata_filter=metadata_filter)
            except Exception as retry_exc:
                logger.exception("컬렉션 초기화 후 재검색에 실패했습니다.")
                raise HTTPException(
                    status_code=500,
                    detail=(
                        "벡터 컬렉션 임베딩 차원이 맞지 않아 재시도에 실패했습니다. "
                        "`python wipe_chroma.py` 실행 후 문서를 다시 업로드하세요."
                    ),
                ) from retry_exc
        raise

def _generate_answer(question: str, context_text: str, *, has_context: bool) -> str:
    """환경 변수 설정에 따라 LLM을 호출하여 답변 텍스트를 생성"""

    provider = os.getenv("LLM_PROVIDER", "openai").lower()  # 기본 LLM 공급자를 openai로 설정함
    # LangChain 프롬프트 템플릿으로 시스템/휴먼 메시지를 미리 구성해 일관된 UX를 유지한다
    # 컨텍스트가 없으면 일반 대화 템플릿을 사용해 일상 질문에도 답변하도록 분기함
    prompt_template = PROMPT_TEMPLATE if has_context else PROMPT_TEMPLATE_GENERAL
    messages = prompt_template.format_messages(
        question=question.strip(),
        context=context_text,
    )    

    if provider == "openai":
        # OpenAI 키가 없으면 즉시 오류를 발생시켜 클라이언트가 설정을 점검하도록 유도
        api_key = os.getenv("OPENAI_API_KEY")
        if not api_key:
            raise HTTPException(status_code=500, detail="OPENAI_API_KEY가 설정되어 있지 않습니다.")

        from langchain_openai import ChatOpenAI  # 지연 임포트로 선택적 의존성 관리를 수행함

        model = os.getenv("OPENAI_CHAT_MODEL", "gpt-5")
        base_url = os.getenv("OPENAI_BASE_URL")
        try:
            # 잘못된 입력으로 인한 예외를 방지하기 위해 수치 변환 시 예외를 처리함
            # 기본 온도를 0.35로 맞춰 창의적 통찰 섹션의 다양성을 확보하면서도 문서 근거 중심 답변을 유지하려는 균형을 문서화함
            # (필요 시 OPENAI_CHAT_TEMPERATURE 환경 변수로 창의성과 사실성의 균형을 세밀하게 조정할 수 있음)
            temperature = float(os.getenv("OPENAI_CHAT_TEMPERATURE", "0.35"))
        except ValueError:
            # 환경 변수 파싱 실패 시에도 동일한 기본값을 사용하도록 예외 처리를 수행함
            temperature = 0.35

        # 실제 LLM 호출 객체를 생성 (base_url 은 선택적으로 주입)
        llm_kwargs: Dict[str, Any] = {"model": model, "temperature": temperature, "api_key": api_key}
        if base_url:
            llm_kwargs["base_url"] = base_url

        llm = ChatOpenAI(**llm_kwargs)
        response = llm.invoke(messages)
        return (response.content or "").strip() or "답변을 생성하지 못했습니다."

    # 현재는 OpenAI 만 지원하므로 다른 공급자가 들어오면 명시적으로 오류 반환
    raise HTTPException(status_code=500, detail=f"지원하지 않는 LLM_PROVIDER: {provider}")


# -----------------------------
# 파일별 텍스트 추출 유틸
# -----------------------------
def _extract_text_txt_like_bytes(content: bytes) -> str:
    # 바이너리 → UTF-8 정규화 (한국어 깨짐 방지)
    return to_utf8_text(content)

def _extract_text_pdf(path: Path) -> str:
    import fitz  # PyMuPDF

    out = []
    with fitz.open(str(path)) as pdf:
        for page in pdf:
            out.append(page.get_text("text"))
    return "\n".join(out)

def _iter_block_items(parent):
    """docx 문서/셀에서 단락과 표를 순서대로 순회하기 위한 유틸 함수"""

    from docx.oxml.table import CT_Tbl  # 표 요소 식별을 위해 임포트
    from docx.oxml.text.paragraph import CT_P  # 단락 요소 식별을 위해 임포트
    from docx.table import _Cell, Table  # 셀/표 객체 타입 비교를 위해 임포트
    from docx.text.paragraph import Paragraph  # 단락 객체 생성을 위해 임포트

    # Document 인스턴스는 body, Cell 은 _tc 를 통해 하위 요소를 조회함
    parent_elm = parent._tc if isinstance(parent, _Cell) else parent.element.body
    for child in parent_elm.iterchildren():
        if isinstance(child, CT_P):
            yield Paragraph(child, parent)  # 단락은 Paragraph 객체로 감싸 반환함
        elif isinstance(child, CT_Tbl):
            yield Table(child, parent)  # 표 요소는 Table 객체로 변환해 반환함


def _collect_table_text(table, out_lines: List[str]) -> None:
    """docx 표를 순회하며 셀 텍스트를 라인으로 변환해 누적"""

    from docx.text.paragraph import Paragraph  # 셀 내부 단락 텍스트 접근용 임포트
    from docx.table import Table  # 중첩 표 판별을 위해 임포트

    for row in table.rows:
        row_texts: List[str] = []  # 한 행의 셀 텍스트를 저장해 가독성 있게 결합함
        for cell in row.cells:
            cell_fragments: List[str] = []  # 셀 내부 단락/중첩 표 텍스트를 모음
            for item in _iter_block_items(cell):
                if isinstance(item, Paragraph):
                    text = item.text.strip()
                    if text:
                        cell_fragments.append(text)  # 셀 내 단락 텍스트를 모음
                elif isinstance(item, Table):
                    _collect_table_text(item, out_lines)  # 중첩 표는 재귀로 처리함
            cell_text = " ".join(cell_fragments).strip()
            if cell_text:
                row_texts.append(cell_text)  # 공백 제거 후 남은 텍스트만 행에 추가함
        if row_texts:
            out_lines.append(" | ".join(row_texts))  # 행 전체를 파이프 구분자로 한 줄에 기록함

def _extract_text_docx(path: Path) -> str:
    from docx import Document as Docx  # DOCX 파일 열람을 위해 임포트
    from docx.table import Table  # 블록이 표인지 판별하기 위한 임포트
    from docx.text.paragraph import Paragraph  # 블록이 단락인지 판별하기 위한 임포트

    doc = Docx(str(path))  # 업로드된 DOCX 파일을 메모리로 로드함
    lines: List[str] = []  # 추출된 텍스트 조각을 순서대로 누적할 리스트를 준비함    

    for block in _iter_block_items(doc):
        if isinstance(block, Paragraph):
            text = block.text.strip()
            if text:
                lines.append(text)  # 표 밖의 단락 텍스트를 그대로 누적함
        elif isinstance(block, Table):
            _collect_table_text(block, lines)  # 표는 행 단위 텍스트로 변환해 누적함

    return "\n".join(lines)  # 문서 순서를 유지한 채 줄바꿈으로 합쳐 반환함

def _shape_texts(shape) -> List[str]:
    """python-pptx shape 에서 텍스트/테이블 텍스트 추출"""
    texts: List[str] = []

    # 텍스트 프레임
    if hasattr(shape, "has_text_frame") and shape.has_text_frame:
        try:
            for para in shape.text_frame.paragraphs:
                # run 기반 결합 (굵게/색상 등 스타일 분할 보정)
                texts.append("".join(run.text for run in para.runs) or (para.text or ""))
        except Exception:
            pass

    # 테이블
    if hasattr(shape, "has_table") and shape.has_table:
        try:
            tbl = shape.table
            for r in tbl.rows:
                row_vals = []
                for c in r.cells:
                    s = (c.text or "").replace("\r", " ").replace("\n", " ").strip()
                    if s:
                        row_vals.append(s)
                if row_vals:
                    texts.append(" | ".join(row_vals))
        except Exception:
            pass

    # 그룹(중첩)
    if hasattr(shape, "shapes"):
        try:
            for s in shape.shapes:
                texts.extend(_shape_texts(s))
        except Exception:
            pass

    return [t for t in texts if t and t.strip()]

def _extract_text_pptx(path: Path) -> str:
    """슬라이드 본문 + 노트 + 테이블 텍스트까지 추출"""
    from pptx import Presentation
    prs = Presentation(str(path))
    out = []
    for idx, slide in enumerate(prs.slides, start=1):
        slide_buf = [f"[Slide {idx}]"]
        # 본문
        for shape in slide.shapes:
            slide_buf.extend(_shape_texts(shape))
        # 노트
        try:
            if slide.has_notes_slide and slide.notes_slide and slide.notes_slide.notes_text_frame:
                notes_texts = [
                    p.text
                    for p in slide.notes_slide.notes_text_frame.paragraphs
                    if p.text and p.text.strip()
                ]
                if notes_texts:
                    slide_buf.append("[Notes]")
                    slide_buf.append("\n".join(notes_texts))
        except Exception:
            pass
        out.append("\n".join(slide_buf))
    return "\n\n".join(out)

def _extract_text_xlsx(path: Path, max_rows_per_sheet: int = 2000) -> str:
    """경량 XLSX 텍스트 추출(표/시트 주요 텍스트). 대용량 방지 위해 상한 적용."""
    from openpyxl import load_workbook
    wb = load_workbook(filename=str(path), read_only=True, data_only=True)
    out: List[str] = []
    for ws in wb.worksheets:
        out.append(f"[Sheet] {ws.title}")
        rows = 0
        for row in ws.iter_rows(values_only=True):
            if rows >= max_rows_per_sheet:
                out.append("... (truncated)")
                break
            vals = []
            for v in row:
                if v is None:
                    continue
                s = str(v).strip()
                if s:
                    vals.append(s)
            if vals:
                out.append(" | ".join(vals))
            rows += 1
    return "\n".join(out)

SUPPORTED_EXTS = {".txt", ".csv", ".log", ".md", ".pdf", ".docx", ".pptx", ".xlsx"}

def extract_text_by_ext(dst: Path, raw_content: bytes) -> str:
    ext = dst.suffix.lower()
    if ext in {".txt", ".csv", ".log", ".md"}:
        return _extract_text_txt_like_bytes(raw_content)
    if ext == ".pdf":
        return _extract_text_pdf(dst)
    if ext == ".docx":
        return _extract_text_docx(dst)
    if ext == ".pptx":
        return _extract_text_pptx(dst)
    if ext == ".xlsx":
        return _extract_text_xlsx(dst)
    raise HTTPException(status_code=400, detail=f"지원하지 않는 확장자: {ext}")

# -----------------------------
# FastAPI
# -----------------------------
# 서버 시작 시 .env를 메모리로 불러오려는 목적
load_dotenv()

# 템플릿과 정적 리소스 경로 계산을 위해 모듈 기준 디렉터리를 미리 구함
BASE_DIR = Path(__file__).parent  # 현재 파일 기준 디렉터리를 재사용하기 위해 상수로 정의함

app = FastAPI()
app.include_router(admin_router)

# Jinja 템플릿 로더를 초기화하여 HTML 기반의 간단한 챗 인터페이스를 제공함
templates = Jinja2Templates(directory=str(BASE_DIR / "templates"))

def _check_llm_status() -> tuple[bool, Optional[str]]:
    """LLM 공급자 준비 상태를 확인해 문제를 조기에 노출"""

    provider = os.getenv("LLM_PROVIDER", "openai").lower()
    # 현재는 OpenAI만 지원하므로 공급자가 다른 경우 바로 에러 메시지를 반환함
    if provider != "openai":
        return False, f"지원하지 않는 LLM_PROVIDER: {provider}"

    api_key = os.getenv("OPENAI_API_KEY")
    if not api_key:
        return False, "OPENAI_API_KEY가 설정되어 있지 않습니다."

    return True, None


@app.get(
    "/health/rag",
    response_model=RagHealthResponse,
    summary="RAG 필수 설정 헬스체크",
    tags=["health"],
)
async def rag_health() -> RagHealthResponse:
    """LLM·임베딩·벡터스토어 설정을 한 번에 점검하는 헬스 엔드포인트"""

    llm_ready, llm_error = _check_llm_status()

    # 임베딩 백엔드 상태는 기존 관리자 API와 동일한 헬퍼를 사용해 조회함
    try:
        embedding_info = get_embedding_backend_info_dict(force_refresh=True)
    except Exception as exc:
        embedding_info = {"error": str(exc), "resolved_backend": None}

    # Chroma의 영속 경로와 현재 컬렉션 이름을 간단히 반환해 운영자가 위치를 바로 파악하도록 함
    chroma_dir, collection_name = get_chroma_settings()
    chroma_summary = {
        "persistPath": chroma_dir,
        "collection": collection_name,
    }
    try:
        # 실제 연결 여부를 확인하기 위해 카운트 조회를 시도함
        vectordb = get_vectordb(get_embedding_fn())
        chroma_summary["count"] = vectordb._collection.count()
    except Exception as exc:
        chroma_summary["error"] = str(exc)

    ready = llm_ready and not embedding_info.get("error") and "error" not in chroma_summary

    return RagHealthResponse(
        ready=ready,
        llm_provider=os.getenv("LLM_PROVIDER", "openai"),
        llm_ready=llm_ready,
        llm_error=llm_error,
        embedding=embedding_info,
        chroma=chroma_summary,
    )

@app.get("/", response_class=HTMLResponse, summary="웹 챗 인터페이스", tags=["ui"])
async def chat_ui(request: Request) -> HTMLResponse:
    """Swagger 대신 실제 채팅 화면에서 RAG 답변을 체험할 수 있는 엔드포인트"""

    # 템플릿 렌더링에 필요한 request 컨텍스트만 전달하면 정적 HTML/JS가 로드됨
    return templates.TemplateResponse("chat.html", {"request": request})

@app.post("/upload")
async def upload(
    file: UploadFile = File(...),  # 업로드된 파일을 그대로 유지함
    doc_id: Optional[str] = Form(default=None, alias="docId"),  # 업로드 요청에서 문서 ID를 선택적으로 받아 alias를 지정함
):
    try:
        # 1) 업로드 파일 저장 (절대경로 고정)
        uploads_dir = (Path(__file__).parent / "uploads").resolve()
        uploads_dir.mkdir(parents=True, exist_ok=True)
        dst = uploads_dir / file.filename

        content: bytes = await file.read()
        # (선택) 50MB 제한
        max_bytes = 50 * 1024 * 1024
        if len(content) > max_bytes:
            raise HTTPException(status_code=413, detail=f"파일이 너무 큽니다(최대 50MB). size={len(content)}")

        dst.write_bytes(content)

        # 2) 텍스트 추출
        ext = dst.suffix.lower()
        if ext not in SUPPORTED_EXTS:
            raise HTTPException(400, f"지원하지 않는 확장자: {ext}")
        text = extract_text_by_ext(dst, content)
        if not text.strip():
            raise HTTPException(400, "본문이 비어 있습니다.")

        # 3) 청킹 - 업로드 전용 청킹 규칙을 명시적으로 변수에 담아 가독성을 높인다
        chunk_size = DEFAULT_CHUNK_SIZE
        chunk_overlap = DEFAULT_CHUNK_OVERLAP
        docs = chunk_single_document(
            text,
            {
                "source": file.filename,
                "doctype": ext[1:],
                **({"docId": doc_id} if doc_id else {}),
            },
            chunk_size=chunk_size,
            overlap=chunk_overlap,
        )

        # 4) 벡터DB 업서트 + persist()
        emb = get_embedding_fn()
        vectordb = get_vectordb(emb)  # 업서트 전에 사용할 벡터 DB 핸들을 준비함
        # 재업로드 시 기존 청크를 정리하기 위한 메타데이터 필터를 구성함
        deletion_filter = {"docId": doc_id} if doc_id else {"source": file.filename}
        deleted_chunks = 0  # 삭제된 청크 수를 추적해 중복 제거 여부를 확인하기 위한 카운터
        try:
            existing = vectordb._collection.get(
                where=deletion_filter,
                include=[],
            )  # 삭제 전에 일치하는 청크가 실제로 존재하는지 확인해 불필요한 delete 호출을 피함
            existing_ids = existing.get("ids") if isinstance(existing, dict) else None
            matched_ids = existing_ids or []  # None 대비 기본값으로 빈 리스트를 사용함

            if matched_ids:
                # 실제로 삭제할 대상이 있을 때에만 delete 를 수행해 헤더 파일 누락 오류를 방지함
                delete_result = vectordb._collection.delete(where=deletion_filter)
                if isinstance(delete_result, dict):
                    deleted_chunks = len(delete_result.get("ids") or [])
                elif isinstance(delete_result, (list, tuple, set)):
                    deleted_chunks = len(delete_result)
                elif isinstance(delete_result, int):
                    deleted_chunks = delete_result
            logger.info(
                "업로드 전 기존 청크 삭제 수행 filter=%s, deleted=%s",
                deletion_filter,
                deleted_chunks,
            )
        except Exception as delete_exc:
            # 삭제 과정에서 오류가 발생하면 업로드를 중단하고 클라이언트에게 오류를 알림
            logger.exception(
                "기존 청크 삭제 중 오류 발생 filter=%s", deletion_filter
            )
            raise HTTPException(
                status_code=500,
                detail="기존 문서를 삭제하지 못해 업로드를 중단합니다.",
            ) from delete_exc
                
        chroma_dir, collection_name = get_chroma_settings()  # 현재 사용 중인 Chroma 경로와 컬렉션 이름을 함께 확인함                  

        # 디버그: add/persist 전후 카운트
        try:
            count_before = vectordb._collection.count()
        except Exception:
            count_before = None

        try:
            vectordb.add_documents(docs)  # 준비한 문서 청크들을 벡터 DB에 추가
            vectordb.persist()  # 디스크에 변경 사항을 영속화
        except ValueError as err:
            message = str(err)
            if "does not match collection dimensionality" in message:
                hint = (
                    "임베딩 차원이 기존 Chroma 컬렉션 설정과 맞지 않습니다. "
                    "새로운 임베딩 모델을 사용할 때는 `python wipe_chroma.py`로 "
                    "기존 컬렉션을 초기화하거나 `CHROMA_COLLECTION` 환경 변수를 "
                    "변경해 다른 컬렉션을 사용해야 합니다."
                )  # 차원 불일치 시 사용자가 수행해야 할 조치를 안내
                logger.error("Chroma dimension mismatch detected: %s", message)
                logger.warning(
                    "기존 컬렉션 %s(%s)을 삭제하고 새 임베딩으로 재시도합니다.",
                    collection_name,
                    chroma_dir,
                )  # 운영자가 상황을 파악할 수 있도록 자동 복구 절차를 로그로 남김
                try:
                    reset_collection()  # 차원 불일치를 야기한 기존 컬렉션을 삭제하여 깨끗한 상태로 만든다
                    vectordb = get_vectordb(emb)  # 삭제 이후 동일한 임베딩으로 새 컬렉션을 다시 연다
                    chroma_dir, collection_name = get_chroma_settings()  # 삭제 결과 반영된 최신 정보를 다시 조회한다
                    count_before = 0  # 새로 만들어진 컬렉션이므로 기존 카운트는 0으로 재설정한다
                    vectordb.add_documents(docs)  # 다시 문서를 업서트하여 업로드를 계속 진행한다
                    vectordb.persist()  # 재시도한 데이터를 디스크에 영속화한다
                    logger.info(
                        "Chroma 컬렉션 %s 재생성 후 업로드를 완료했습니다.",
                        collection_name,
                    )  # 자동 복구가 성공했음을 알리는 로그를 남김
                except Exception as retry_exc:
                    logger.exception("Chroma 컬렉션 자동 초기화 재시도 실패")
                    raise HTTPException(
                        status_code=500,
                        detail=f"ingest failed: {message}. {hint}",
                    ) from retry_exc  # 자동 복구에도 실패한 경우 명시적으로 안내
            else:
                raise  # 다른 ValueError는 기존 처리 흐름으로 전달

        try:
            count_after = vectordb._collection.count()
        except Exception:
            count_after = None

        print(
            f"[INGEST] to {collection_name} @ {chroma_dir}, "
            f"file={file.filename}, chunks={len(docs)} → persist() done "
            f"(count {count_before} → {count_after})"
        )

        # 5) 바로 관리뷰 링크 반환
        return {
            "ok": True,
            "file": file.filename,
            "chunks": len(docs),
            "collection": collection_name,
            "dir": chroma_dir,
            "count_before": count_before,
            "count_after": count_after,
            "view": "/admin/rag/view?limit=50&offset=0",
            "docId": doc_id,  # 응답에 실제 저장된 docId 값을 포함해 호출자가 확인하도록 함
        }

    except HTTPException as http_exc:
        logger.warning(
            "업로드 요청이 실패했습니다 status=%s detail=%s",
            http_exc.status_code,
            http_exc.detail,
        )  # FastAPI 로그에 실패 사유를 남겨 운영 시점에 원인을 바로 추적할 수 있도록 함
        raise
    except Exception as e:
        logger.exception(
            "업로드 처리 중 예상치 못한 오류가 발생했습니다"
        )  # 미처 처리하지 못한 예외는 전체 스택을 기록해 디버깅 단서를 확보함
        raise HTTPException(status_code=500, detail=f"ingest failed: {e}")

# 문서 관련 질의 응답 
@app.post(
    "/query/retrieve",
    response_model=RetrieveResponse,
    summary="벡터 검색 결과만 반환",
    tags=["rag"],
)
async def query_retrieve(payload: QueryRequest) -> RetrieveResponse:
    """문서 검색 단계만 수행하여 컨텍스트와 매치 목록을 반환"""

    try:
        metadata_filter = {"docId": payload.doc_id} if payload.doc_id else None

        docs = _run_retrieval_with_recovery(
            question=payload.question,
            top_k=payload.top_k,
            metadata_filter=metadata_filter,
        )

        context_chunks, context_text, _has_docs = _prepare_prompt_context(docs)  # 검색 유무는 응답 구성에 필요 없으므로 언더스코어 변수로 받는다

        matches = [
            Match(
                reference=chunk.reference,
                chunk_index=chunk.chunk_index,
                content=chunk.body,
                preview=chunk.preview,
                source=chunk.source,
                page=chunk.page,
                metadata=chunk.metadata,
            )
            for chunk in context_chunks
        ]

        return RetrieveResponse(matches=matches, context=context_text)

    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"retrieve failed: {e}")


@app.post(
    "/query/generate",
    response_model=GenerateResponse,
    summary="검색 컨텍스트로 LLM 답변 생성",
    tags=["rag"],
)
async def query_generate(payload: GenerateRequest) -> GenerateResponse:
    """검색된 컨텍스트를 기반으로 LLM 답변만 생성"""

    try:
        has_context = bool(payload.context.strip())  # 컨텍스트가 비어 있으면 일반 대화 모드로 전환하기 위한 플래그
        answer_text = _generate_answer(payload.question, payload.context, has_context=has_context)
        return GenerateResponse(answer=answer_text)
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"generate failed: {e}")
@app.post(
    "/query",
    response_model=QueryResponse,
    summary="벡터 검색 + LLM 기반 답변",
    tags=["rag"],
)
async def query(payload: QueryRequest) -> QueryResponse:
    """문서 검색 후 LLM을 호출하여 최종 답변을 반환"""

    try:
        # docId 필터가 전달된 경우 retriever 팩토리가 이해할 수 있도록 metadata_filter 로 변환한다
        metadata_filter = {"docId": payload.doc_id} if payload.doc_id else None
         
        # rag_service.query_text 를 통해 검색 전략/파라미터 구성을 일관되게 재사용한다
        docs = _run_retrieval_with_recovery(
            question=payload.question,
            top_k=payload.top_k,
            metadata_filter=metadata_filter,
        )            

        # 프롬프트와 응답에서 동일한 라벨과 메타데이터를 쓰기 위해 컨텍스트를 한 번만 가공한다
        context_chunks, context_text, has_docs = _prepare_prompt_context(docs)

        # 검색된 청크 정보를 응답에 포함시키되 UX 에 필요한 부가 필드를 함께 전달한다                
        matches = [        
            Match(
                reference=chunk.reference,
                chunk_index=chunk.chunk_index,
                content=chunk.body,
                preview=chunk.preview,
                source=chunk.source,
                page=chunk.page,
                metadata=chunk.metadata,
            )
            for chunk in context_chunks
        ]

        # LLM 에 전달할 컨텍스트 텍스트를 재사용하여 최종 답변을 생성한다
        # 검색 결과가 없으면 일반 대화 프롬프트로 전환해 일상 질문에도 답하도록 함
        answer_text = _generate_answer(payload.question, context_text, has_context=has_docs)

        return QueryResponse(answer=answer_text, matches=matches)

    except HTTPException:
        # 이미 정의된 에러는 그대로 전파
        raise
    except Exception as e:
        # 예기치 못한 오류는 서버 오류로 감싸서 전달
        raise HTTPException(status_code=500, detail=f"query failed: {e}")

@app.post("/documents/delete")
async def delete_documents(payload: DocDeleteRequest):
    """문서 UUID 또는 파일명을 기준으로 벡터 DB와 업로드 파일을 정리"""

    try:
        # 업로드 시점과 동일한 임베딩 설정을 불러와 동일한 컬렉션에 접근한다.
        embedding_fn = get_embedding_fn()
        vectordb = get_vectordb(embedding_fn)
        collection = vectordb._collection  # delete(where=...) 호출을 위해 내부 컬렉션 객체를 그대로 사용한다.

        # docId 가 존재하면 docId 기준으로, 없으면 source 기준으로 where 절을 구성한다.
        if payload.doc_id:
            where: Dict[str, Any] = {"docId": payload.doc_id}
        else:
            where = {"source": payload.source}

        # 삭제 대상 청크의 메타데이터를 먼저 모아서 삭제 건수와 관련 파일명을 파악한다.
        matched = collection.get(where=where, include=["metadatas"])
        matched_ids = matched.get("ids") or []
        metadatas = matched.get("metadatas") or []
        sources = {
            (meta or {}).get("source")
            for meta in metadatas
            if meta and (meta.get("source") is not None)
        }

        # 동일한 where 조건으로 청크가 존재할 때만 삭제를 수행해 빈 인덱스에서 발생하는 오류를 막는다.
        if matched_ids:
            collection.delete(where=where)
            vectordb.persist()  # 변경된 상태를 디스크에 즉시 반영해 일관성을 유지한다.
        else:
            logger.info(
                "삭제 요청과 일치하는 청크가 없어 delete 작업을 건너뜁니다. filter=%s",
                where,
            )  # 삭제할 항목이 없는 경우에도 상황을 기록해 문제 추적을 돕는다.

        # 업로드 폴더에 남아있는 원본 파일도 함께 제거한다.
        uploads_dir = (Path(__file__).parent / "uploads").resolve()
        file_results: List[Dict[str, Any]] = []
        for file_name in sorted(s for s in sources if s):
            target_path = uploads_dir / file_name
            try:
                target_path.unlink(missing_ok=True)
                file_results.append(
                    {
                        "file": file_name,
                        "removed": not target_path.exists(),
                    }
                )
            except Exception as exc:
                file_results.append(
                    {
                        "file": file_name,
                        "removed": False,
                        "error": str(exc),
                    }
                )

        return {
            "deletedChunks": len(matched_ids),
            "deletedFiles": file_results,
        }

    except HTTPException:
        raise
    except Exception as exc:
        raise HTTPException(status_code=500, detail=f"delete failed: {exc}")