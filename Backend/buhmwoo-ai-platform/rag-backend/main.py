# main.py
from fastapi import FastAPI, UploadFile, File, HTTPException
from pathlib import Path
from typing import List
from langchain_core.documents import Document

from config_chroma import get_vectordb, CHROMA_DIR, CHROMA_COLLECTION
from config_embed import get_embedding_fn
from utils.encoding import to_utf8_text
from admin_router import router as admin_router

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
                notes_texts = [p.text for p in slide.notes_slide.notes_text_frame.paragraphs if p.text and p.text.strip()]
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
app = FastAPI()
app.include_router(admin_router)

@app.post("/upload")
async def upload(file: UploadFile = File(...)):
    try:
        # 1) 업로드 파일 저장 (절대경로 고정)
        uploads_dir = (Path(__file__).parent / "uploads").resolve()
        uploads_dir.mkdir(parents=True, exist_ok=True)
        dst = uploads_dir / file.filename

        content: bytes = await file.read()
        # (선택) 20MB 제한
        max_bytes = 20 * 1024 * 1024
        if len(content) > max_bytes:
            raise HTTPException(status_code=413, detail=f"파일이 너무 큽니다(최대 20MB). size={len(content)}")

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
        vectordb = get_vectordb(emb)

        docs = [
            Document(
                page_content=c,
                metadata={
                    "source": file.filename,
                    "doctype": ext[1:],
                    "chunk_index": i,
                },
            )
            for i, c in enumerate(chunks)
        ]

        # 디버그: add/persist 전후 카운트
        try:
            count_before = vectordb._collection.count()
        except Exception:
            count_before = None

        vectordb.add_documents(docs)
        vectordb.persist()

        try:
            count_after = vectordb._collection.count()
        except Exception:
            count_after = None

        print(
            f"[INGEST] to {CHROMA_COLLECTION} @ {CHROMA_DIR}, "
            f"file={file.filename}, chunks={len(docs)} → persist() done "
            f"(count {count_before} → {count_after})"
        )

        # 5) 바로 관리뷰 링크 반환
        return {
            "ok": True,
            "file": file.filename,
            "chunks": len(docs),
            "collection": CHROMA_COLLECTION,
            "dir": str(CHROMA_DIR),
            "count_before": count_before,
            "count_after": count_after,
            "view": "/admin/rag/view?limit=50&offset=0",
        }

    except HTTPException:
        raise
    except Exception as e:
        # 숨은 예외(임베딩/HF 다운로드/의존성 등) 표면화
        print(f"[ERROR] ingest failed: {e}")
        raise HTTPException(status_code=500, detail=f"ingest failed: {e}")
