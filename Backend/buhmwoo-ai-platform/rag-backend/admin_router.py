# admin_router.py
from fastapi import APIRouter, Query
from fastapi.responses import HTMLResponse, JSONResponse
from typing import List, Dict, Any, Optional
import os, html
from collections import Counter

import chromadb
from chromadb import ClientAPI
from chromadb.utils import embedding_functions

router = APIRouter(prefix="/admin/rag", tags=["admin-rag"])

# === 환경설정 ===
CHROMA_DIR = os.path.abspath(os.getenv("CHROMA_DIR", "./chroma_db"))
CHROMA_COLLECTION = os.getenv("CHROMA_COLLECTION", "oneask_docs")

# (선택) 기본 임베더 (불필요하면 제거 가능)
_DEFAULT_EMBED = embedding_functions.DefaultEmbeddingFunction()


def get_collection() -> ClientAPI:
    client = chromadb.PersistentClient(path=CHROMA_DIR)
    try:
        col = client.get_collection(CHROMA_COLLECTION, embedding_function=_DEFAULT_EMBED)
    except Exception:
        col = client.create_collection(CHROMA_COLLECTION, embedding_function=_DEFAULT_EMBED)
    return col


def _page_get_all(col, include: Optional[List[str]] = None, page_size: int = 1000):
    """limit=-1일 때 모든 레코드를 페이지 단위로 수집"""
    offset = 0
    all_ids, all_docs, all_metas = [], [], []
    include = include or ["metadatas", "documents"]
    while True:
        got = col.get(limit=page_size, offset=offset, include=include)
        ids = got.get("ids") or []
        if not ids:
            break
        all_ids.extend(ids)
        if "documents" in include:
            all_docs.extend(got.get("documents") or [])
        if "metadatas" in include:
            all_metas.extend(got.get("metadatas") or [])
        offset += len(ids)
    return {"ids": all_ids, "documents": all_docs if "documents" in include else None, "metadatas": all_metas if "metadatas" in include else None}


@router.get("/stats")
def stats():
    col = get_collection()
    total_chunks = col.count()
    meta_all = col.get(include=["metadatas"]).get("metadatas") or []
    unique_docs_total = len(set(m.get("docId") for m in meta_all if m))
    return {
        "collection": CHROMA_COLLECTION,
        "persist_path": CHROMA_DIR,
        "total_chunks": total_chunks,
        "unique_docs_total": unique_docs_total,
    }


@router.get("/by-doc")
def by_doc():
    col = get_collection()
    meta_all = col.get(include=["metadatas"]).get("metadatas") or []
    c = Counter(m.get("docId") for m in meta_all if m)
    rows = [{"docId": k, "chunks": v} for k, v in c.items() if k]
    rows.sort(key=lambda x: x["chunks"], reverse=True)
    return {
        "collection": CHROMA_COLLECTION,
        "persist_path": CHROMA_DIR,
        "rows": rows,
        "unique_docs_total": len(rows),
        "total_chunks": col.count(),
    }


@router.get("/view")
def view(limit: int = Query(50), offset: int = Query(0, ge=0), with_documents: bool = Query(True)):
    """JSON 뷰 (API용)"""
    col = get_collection()
    include = ["metadatas"] + (["documents"] if with_documents else [])
    if limit == -1:
        got = _page_get_all(col, include=include, page_size=1000)
        ids, metas, docs = got["ids"], got["metadatas"] or [], got["documents"] or []
        offset_eff = 0
    else:
        got = col.get(limit=limit, offset=offset, include=include)
        ids, metas, docs = got.get("ids") or [], got.get("metadatas") or [], got.get("documents") or []
        offset_eff = offset

    total_chunks = col.count()
    meta_all = col.get(include=["metadatas"]).get("metadatas") or []
    unique_docs_total = len(set(m.get("docId") for m in meta_all if m))

    items: List[Dict[str, Any]] = []
    for i, id_ in enumerate(ids):
        meta = metas[i] if i < len(metas) else {}
        doc = docs[i] if (with_documents and i < len(docs)) else None
        preview = (doc or "")[:200] if with_documents else None
        items.append({
            "id": id_,
            "docId": (meta or {}).get("docId"),
            "source": (meta or {}).get("source"),
            "page": (meta or {}).get("page"),
            "chunkPreview": preview,
            "metadata": meta,
        })

    return {
        "collection": CHROMA_COLLECTION,
        "persist_path": CHROMA_DIR,
        "total_chunks": total_chunks,
        "unique_docs_total": unique_docs_total,
        "limit": limit,
        "offset": offset_eff,
        "returned": len(items),
        "items": items,
    }


# ====== HTML 테이블 페이지 뷰 ======
@router.get("/view-page", response_class=HTMLResponse)
def view_page(
    limit: int = Query(50, ge=1, le=5000, description="페이지 크기"),
    offset: int = Query(0, ge=0, description="시작 오프셋"),
    with_documents: bool = Query(True, description="본문 미리보기 포함")
):
    """
    브라우저에서 표로 확인하는 HTML 페이지
    - 페이지네이션: limit/offset
    - 상단에 총 청크 수, 고유 문서 수 표시
    - 기본 컬럼: id, docId, page, source, chunkPreview
    """
    col = get_collection()
    include = ["metadatas"] + (["documents"] if with_documents else [])
    got = col.get(limit=limit, offset=offset, include=include)

    ids = got.get("ids") or []
    metas = got.get("metadatas") or []
    docs = got.get("documents") or []

    total_chunks = col.count()
    meta_all = col.get(include=["metadatas"]).get("metadatas") or []
    unique_docs_total = len(set(m.get("docId") for m in meta_all if m))

    # 페이지네이션 계산
    prev_offset = max(0, offset - limit)
    next_offset = offset + len(ids)
    has_prev = offset > 0
    has_next = len(ids) == limit and next_offset < total_chunks  # 다음 페이지 추정

    # HTML 렌더링
    rows_html = []
    for i, id_ in enumerate(ids):
        meta = metas[i] if i < len(metas) else {}
        doc = docs[i] if (with_documents and i < len(docs)) else None
        preview = (doc or "")[:400] if with_documents else ""
        rows_html.append(f"""
        <tr>
            <td class="mono">{html.escape(str(id_))}</td>
            <td class="mono">{html.escape(str(meta.get('docId', '')))}</td>
            <td>{html.escape(str(meta.get('page', '')))}</td>
            <td class="mono">{html.escape(str(meta.get('source', '')))}</td>
            <td class="preview">{html.escape(preview)}</td>
        </tr>
        """)

    html_page = f"""
<!doctype html>
<html lang="ko">
<head>
<meta charset="utf-8" />
<title>Chroma View - {html.escape(CHROMA_COLLECTION)}</title>
<meta name="viewport" content="width=device-width, initial-scale=1" />
<style>
  :root {{
    --bg: #0b0d12;
    --card: #11151d;
    --fg: #e5e7eb;
    --muted: #9ca3af;
    --accent: #3b82f6;
    --border: #1f2937;
  }}
  * {{ box-sizing: border-box; }}
  body {{
    margin: 0; padding: 24px;
    background: var(--bg); color: var(--fg);
    font-family: ui-sans-serif, system-ui, -apple-system, "Segoe UI", Roboto, "Noto Sans KR", Apple SD Gothic Neo, "Malgun Gothic", "Helvetica Neue", Arial, "Noto Color Emoji", "Segoe UI Emoji";
  }}
  h1 {{ margin: 0 0 12px; font-size: 20px; }}
  .stats {{
    display: grid; grid-template-columns: repeat(4, auto); gap: 16px; align-items: center;
    background: var(--card); padding: 12px 16px; border: 1px solid var(--border); border-radius: 10px; margin-bottom: 16px;
  }}
  .stats div span {{ color: var(--muted); font-size: 12px; display:block; }}
  .controls {{
    display:flex; gap:8px; flex-wrap:wrap; align-items:center; margin: 8px 0 16px;
  }}
  .controls a, .controls button {{
    background: var(--card); border:1px solid var(--border); color: var(--fg);
    padding: 6px 10px; border-radius: 8px; text-decoration:none; cursor:pointer;
  }}
  .controls a.disabled {{ pointer-events:none; opacity:.4; }}
  table {{
    width: 100%; border-collapse: collapse; background: var(--card); border:1px solid var(--border); border-radius: 10px; overflow: hidden;
  }}
  thead th {{
    text-align: left; padding: 10px; font-size: 12px; color: var(--muted); background: #0f1420; border-bottom:1px solid var(--border);
  }}
  tbody td {{
    padding: 10px; font-size: 13px; border-bottom:1px solid var(--border); vertical-align: top;
  }}
  .mono {{ font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, "Liberation Mono", monospace; font-size: 12px; color:#cbd5e1; }}
  .preview {{ white-space: pre-wrap; line-height: 1.35; color:#e5e7eb; }}
  .footer {{ color: var(--muted); font-size: 12px; margin-top: 10px; }}
  .pill {{ background:#0f172a; border:1px solid #1f2937; padding:2px 6px; border-radius:999px; font-size:12px; color:#93c5fd; }}
  .switch {{
    display:inline-flex; gap:6px; align-items:center; padding:6px 8px; border-radius: 8px; background:#0f1420; border:1px solid var(--border);
  }}
</style>
</head>
<body>
  <h1>Chroma Collection: <span class="pill">{html.escape(CHROMA_COLLECTION)}</span></h1>

  <div class="stats">
    <div><span>Total Chunks</span><strong>{total_chunks}</strong></div>
    <div><span>Unique Docs</span><strong>{unique_docs_total}</strong></div>
    <div><span>Limit</span><strong>{limit}</strong></div>
    <div><span>Offset</span><strong>{offset}</strong></div>
  </div>

  <div class="controls">
    <a href="/admin/rag/view-page?limit={limit}&offset={prev_offset}&with_documents={'true' if with_documents else 'false'}" class="{'' if has_prev else 'disabled'}">← Prev</a>
    <a href="/admin/rag/view-page?limit={limit}&offset={next_offset}&with_documents={'true' if with_documents else 'false'}" class="{'' if has_next else 'disabled'}">Next →</a>
    <span class="switch">
      <span>Preview</span>
      <a href="/admin/rag/view-page?limit={limit}&offset={offset}&with_documents={'false' if with_documents else 'true'}">
        {'ON' if with_documents else 'OFF'}
      </a>
    </span>
    <a href="/admin/rag/by-doc" target="_blank">By-Doc JSON</a>
    <a href="/admin/rag/stats" target="_blank">Stats JSON</a>
    <a href="/admin/rag/view?limit={limit}&offset={offset}&with_documents={'true' if with_documents else 'false'}" target="_blank">This Page JSON</a>
  </div>

  <table>
    <thead>
      <tr>
        <th style="width: 28%">id</th>
        <th style="width: 18%">docId</th>
        <th style="width: 6%">page</th>
        <th style="width: 18%">source</th>
        <th>chunkPreview</th>
      </tr>
    </thead>
    <tbody>
      {''.join(rows_html) if rows_html else '<tr><td colspan="5" style="text-align:center; color:#9ca3af; padding:24px;">No items</td></tr>'}
    </tbody>
  </table>

  <div class="footer">
    Returned: {len(ids)} / Total Chunks: {total_chunks} | Unique Docs: {unique_docs_total}
  </div>
</body>
</html>
    """
    return HTMLResponse(html_page)
