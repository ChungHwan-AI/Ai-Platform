# main.py
# 표준 로깅 사용을 위한 import
import logging

import os  # LLM 공급자와 키 등 환경 변수 접근을 위해 os 모듈을 임포트함

from fastapi import FastAPI, UploadFile, File, HTTPException, Form  # 업로드 엔드포인트에서 폼 필드와 파일을 다루기 위해 FastAPI 관련 클래스를 임포트함

from fastapi import Body  # 쿼리 입력을 파싱하기 위한 Body 헬퍼

from dotenv import load_dotenv  # .env 파일을 읽어오기 위한 함수 임포트
from pathlib import Path  # 업로드 파일 저장 경로를 다루기 위해 Path 클래스를 임포트함
from typing import List, Optional, Dict, Any  # 타입 힌트를 명확히 하기 위해 필요한 제네릭 타입들을 임포트함
from pydantic import BaseModel, Field, ConfigDict, AliasChoices  # 요청/응답 스키마 정의를 위해 Pydantic 유틸리티를 임포트함
from langchain_core.documents import Document  # 청크를 LangChain Document 형태로 저장하기 위해 Document 클래스를 임포트함

from langchain_core.prompts import ChatPromptTemplate  # LLM 프롬프트 생성을 위한 도구
from langchain_openai import ChatOpenAI  # OpenAI 호출을 위한 LangChain 래퍼
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
from config_embed import get_embedding_fn  # 임베딩 함수를 가져오기 위해 임포트함
from utils.encoding import to_utf8_text  # 텍스트 인코딩을 UTF-8로 정규화하기 위해 임포트함
from admin_router import router as admin_router  # 관리자용 라우터를 메인 앱에 포함시키기 위해 임포트함

from pydantic import BaseModel
from rag_service import query_text


# 모듈 전역 로거
logger = logging.getLogger(__name__)

# Uvicorn 및 루트 로거가 INFO 이상으로 출력되도록 보정
if not logging.getLogger().handlers:
    # 기본 핸들러가 없을 때만 기본 설정을 적용
    logging.basicConfig(level=logging.INFO)
# Uvicorn 런타임 로그 레벨을 명시적으로 INFO로 맞춤
logging.getLogger("uvicorn").setLevel(logging.INFO)

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

    # 청크의 실제 본문 텍스트를 그대로 노출
    content: str = Field(..., description="검색된 청크 본문")
    # 추후 출처, 페이지 등의 메타데이터를 전달하기 위한 필드
    metadata: Dict[str, Any] = Field(
        default_factory=dict,
        description="청크에 연결된 메타데이터",
    )


class QueryResponse(BaseModel):
    """/query 응답 스키마 정의"""

    # LLM이 최종적으로 생성한 답변 텍스트를 담는 필드
    answer: str = Field(..., description="LLM이 생성한 최종 응답")
    # 참고용으로 검색된 청크 목록을 그대로 반환
    matches: List[Match] = Field(
        default_factory=list,
        description="벡터 검색으로 선택된 청크 목록",
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
    
def _build_prompt(question: str, docs: List[Document]) -> str:
    """질문과 검색된 청크들을 하나의 프롬프트 텍스트로 변환"""

    # 각 청크에 번호를 붙여 출처와 본문을 함께 구성
    context_blocks: List[str] = []
    for idx, doc in enumerate(docs, start=1):
        meta = doc.metadata or {}
        # 사람이 출처를 파악할 수 있도록 가능한 메타 필드를 함께 표기
        source = meta.get("source") or meta.get("docId") or "unknown"
        page = meta.get("page")
        header = f"[청크 {idx}] 출처: {source}"
        if page is not None:
            header += f" | 페이지: {page}"
        block = f"{header}\n{doc.page_content.strip()}"
        context_blocks.append(block)

    if not context_blocks:
        # 검색된 청크가 없을 때는 안내 메시지를 포함하여 LLM이 상황을 인지하도록 유도
        context_blocks.append("(참고할 문서를 찾지 못했습니다.)")

    context_text = "\n\n".join(context_blocks)

    # 시스템 지침에 가까운 안내문으로 답변 톤과 형식을 제한
    prompt = (
        "당신은 사내 문서를 바탕으로 답을 제공하는 한국어 도우미입니다."
        "\n주어진 문서 내용을 벗어난 추측은 피하고, 근거가 없으면 솔직하게 모른다고 답변하세요."
        "\n\n[질문]\n"
        f"{question.strip()}"
        "\n\n[참고 문서]\n"
        f"{context_text}"
        "\n\n위 자료를 참고해 간결하고 핵심적인 답변을 작성하세요."
    )
    return prompt


def _generate_answer(question: str, docs: List[Document]) -> str:
    """환경 변수 설정에 따라 LLM을 호출하여 답변 텍스트를 생성"""

    provider = os.getenv("LLM_PROVIDER", "openai").lower()  # 기본 LLM 공급자를 openai로 설정함
    prompt = _build_prompt(question, docs)  # 검색된 청크와 질문으로 최종 프롬프트를 생성함

    if provider == "openai":
        # OpenAI 키가 없으면 즉시 오류를 발생시켜 클라이언트가 설정을 점검하도록 유도
        api_key = os.getenv("OPENAI_API_KEY")
        if not api_key:
            raise HTTPException(status_code=500, detail="OPENAI_API_KEY가 설정되어 있지 않습니다.")

        from langchain_openai import ChatOpenAI  # 지연 임포트로 선택적 의존성 관리를 수행함

        model = os.getenv("OPENAI_CHAT_MODEL", "gpt-4o-mini")
        base_url = os.getenv("OPENAI_BASE_URL")
        try:
            # 잘못된 입력으로 인한 예외를 방지하기 위해 수치 변환 시 예외를 처리함
            temperature = float(os.getenv("OPENAI_CHAT_TEMPERATURE", "0.2"))
        except ValueError:
            temperature = 0.2

        # 실제 LLM 호출 객체를 생성 (base_url 은 선택적으로 주입)
        llm_kwargs: Dict[str, Any] = {"model": model, "temperature": temperature, "api_key": api_key}
        if base_url:
            llm_kwargs["base_url"] = base_url

        llm = ChatOpenAI(**llm_kwargs)
        response = llm.invoke(prompt)
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

def _extract_text_docx(path: Path) -> str:
    from docx import Document as Docx

    d = Docx(str(path))
    return "\n".join([p.text for p in d.paragraphs])

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

def chunk_text(text: str, chunk_size: int = 800, overlap: int = 160) -> List[str]:
    chunks: List[str] = []
    n = len(text)
    s = 0
    while s < n:
        e = min(n, s + chunk_size)
        chunks.append(text[s:e])
        if e == n:
            break
        s = e - overlap if e - overlap > s else e
    return chunks

# -----------------------------
# FastAPI
# -----------------------------
# 서버 시작 시 .env를 메모리로 불러오려는 목적
load_dotenv()

app = FastAPI()
app.include_router(admin_router)

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

        # 3) 청킹
        chunks = chunk_text(text, chunk_size=800, overlap=160)

        # 4) 벡터DB 업서트 + persist()
        emb = get_embedding_fn()
        vectordb = get_vectordb(emb)  # 업서트 전에 사용할 벡터 DB 핸들을 준비함
        # 재업로드 시 기존 청크를 정리하기 위한 메타데이터 필터를 구성함
        deletion_filter = {"docId": doc_id} if doc_id else {"source": file.filename}
        deleted_chunks = 0  # 삭제된 청크 수를 추적해 중복 제거 여부를 확인하기 위한 카운터
        try:
            # 지정된 필터에 해당하는 기존 데이터를 삭제해 재업로드 시 중복을 방지함
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

        docs: List[Document] = []
        for i, c in enumerate(chunks):
            metadata = {
                "source": file.filename,
                "doctype": ext[1:],
                "chunk_index": i,
            }
            if doc_id:
                metadata["docId"] = doc_id
            docs.append(
                Document(
                    page_content=c,
                    metadata=metadata,
                )        
            )                    

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

    except HTTPException:
        raise
    except Exception as e:
        # 숨은 예외(임베딩/HF 다운로드/의존성 등) 표면화
        print(f"[ERROR] ingest failed: {e}")
        raise HTTPException(status_code=500, detail=f"ingest failed: {e}")

# 문서 관련 질의 응답 
@app.post(
    "/query",
    response_model=QueryResponse,
    summary="벡터 검색 + LLM 기반 답변",
    tags=["rag"],
)
async def query(payload: QueryRequest) -> QueryResponse:
    """문서 검색 후 LLM을 호출하여 최종 답변을 반환"""

    try:
        # 업로드 시 사용한 임베딩과 동일한 함수를 불러와 일관성을 유지
        embedding_fn = get_embedding_fn()
        vectordb = get_vectordb(embedding_fn)

        # docId 가 주어지면 해당 문서 범위에서만 검색하도록 필터를 적용
        search_kwargs: Dict[str, Any] = {}
        if payload.doc_id:
            search_kwargs["filter"] = {"docId": payload.doc_id}

        # LangChain VectorStore 의 similarity_search 를 활용해 상위 청크를 조회
        docs = vectordb.similarity_search(
            payload.question,
            k=payload.top_k,
            **search_kwargs,
        )

        # 검색된 청크 정보를 응답에 포함시키기 위해 스키마로 변환
        matches = [
            Match(content=doc.page_content, metadata=doc.metadata or {})
            for doc in docs
        ]

        # LLM 에 전달할 컨텍스트로 활용하여 최종 답변을 생성
        answer_text = _generate_answer(payload.question, docs)

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

        # 동일한 where 조건으로 청크를 삭제한다.
        collection.delete(where=where)
        vectordb.persist()  # 변경된 상태를 디스크에 즉시 반영해 일관성을 유지한다.

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