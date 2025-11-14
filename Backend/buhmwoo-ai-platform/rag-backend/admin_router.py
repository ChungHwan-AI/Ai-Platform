# admin_router.py
from fastapi import APIRouter, Query, Form
from fastapi.responses import HTMLResponse, JSONResponse, RedirectResponse
from urllib.parse import quote_plus
from typing import List, Dict, Any, Optional
import html
from collections import Counter

from config_embed import get_embedding_backend_info_dict  # 임베딩 상태 조회 API 구성을 위해 임포트함
from config_chroma import get_chroma_settings  # 동적으로 결정된 Chroma 저장 위치를 조회하기 위해 임포트함

import chromadb
from chromadb import ClientAPI


router = APIRouter(prefix="/admin/rag", tags=["admin-rag"])

# get_chroma_settings() 헬퍼를 통해 최신 경로·컬렉션 정보를 필요 시점에 조회한다.

def get_collection() -> ClientAPI:
    chroma_dir, collection_name = get_chroma_settings()  # 현재 저장 경로와 컬렉션 이름을 동적으로 조회함
    client = chromadb.PersistentClient(path=chroma_dir)
    try:
        # 기존 컬렉션을 그대로 불러와 LangChain이 저장한 데이터와 차원 정보를 유지한다
        col = client.get_collection(collection_name)
    except Exception:
        # 컬렉션이 아직 없다면 임시로 생성해 조회 페이지가 최소한 동작하도록 한다
        col = client.create_collection(collection_name)
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
    chroma_dir, collection_name = get_chroma_settings()  # 응답에 최신 경로·컬렉션을 반영하기 위해 재조회함
    total_chunks = col.count()
    meta_all = col.get(include=["metadatas"]).get("metadatas") or []
    unique_docs_total = len(set(m.get("docId") for m in meta_all if m))
    return {
        "collection": collection_name,
        "persist_path": chroma_dir,
        "total_chunks": total_chunks,
        "unique_docs_total": unique_docs_total,
    }

@router.get("/embedding", response_class=JSONResponse)
def embedding_status(force_refresh: bool = Query(False, description="임베딩 상태를 강제로 재확인할지 여부")):
    """현재 RAG 백엔드가 활용 중인 임베딩 백엔드 상태를 JSON으로 반환"""

    info = get_embedding_backend_info_dict(force_refresh=force_refresh)  # 임베딩 상태를 사전 형태로 조회하여 직렬화 준비
    return {
        "configuredBackend": info["configured_backend"],  # 환경 변수에 의해 설정된 백엔드 이름을 노출
        "resolvedBackend": info["resolved_backend"],  # 실제 로딩된 백엔드 이름(미초기화 시 None)을 노출
        "model": info["model"],  # 사용 중인 모델 이름을 반환
        "fallback": info["fallback"],  # 폴백 여부를 전달하여 장애 여부를 파악 가능하게 함
        "error": info["error"],  # 폴백 발생 시 에러 메시지를 함께 전달
    }

@router.get("/by-doc")
def by_doc():
    col = get_collection()
    chroma_dir, collection_name = get_chroma_settings()  # 동적으로 선택된 컬렉션 정보를 응답에 포함
    meta_all = col.get(include=["metadatas"]).get("metadatas") or []
    c = Counter(m.get("docId") for m in meta_all if m)
    rows = [{"docId": k, "chunks": v} for k, v in c.items() if k]
    rows.sort(key=lambda x: x["chunks"], reverse=True)
    return {
        "collection": collection_name,
        "persist_path": chroma_dir,
        "rows": rows,
        "unique_docs_total": len(rows),
        "total_chunks": col.count(),
    }


@router.get("/view")
def view(limit: int = Query(50), offset: int = Query(0, ge=0), with_documents: bool = Query(True)):
    """JSON 뷰 (API용)"""
    col = get_collection()
    chroma_dir, collection_name = get_chroma_settings()  # 응답 메타데이터에 최신 컬렉션 정보를 반영
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
        "collection": collection_name,
        "persist_path": chroma_dir,
        "total_chunks": total_chunks,
        "unique_docs_total": unique_docs_total,
        "limit": limit,
        "offset": offset_eff,
        "returned": len(items),
        "items": items,
    }


# ====== HTML 테이블 페이지 뷰 ======
@router.post("/delete-by-docid")
def delete_by_docid(
    doc_id: str = Form(..., description="삭제할 문서의 docId"),
    limit: int = Form(50),
    offset: int = Form(0),
    with_documents: str = Form("true"),
):
    """주어진 docId에 해당하는 모든 청크를 삭제"""

    # docId 입력값을 전처리해 안전하게 사용한다
    doc_id = (doc_id or "").strip()
    if not doc_id:
        # docId가 비어 있으면 아무 작업도 하지 않고 안내 메시지와 함께 돌아간다
        return RedirectResponse(
            f"/admin/rag/view-page?limit={limit}&offset={offset}&with_documents={with_documents}&message="
            f"{quote_plus('docId를 입력해주세요.')}",
            status_code=303,
        )

    # 현재 컬렉션에서 해당 docId의 레코드 개수를 조회한다
    col = get_collection()
    existing = col.get(where={"docId": doc_id}, include=["metadatas"], limit=100000)
    to_delete = len(existing.get("ids") or [])

    if to_delete:
        # 레코드가 존재하면 삭제를 실행한다
        col.delete(where={"docId": doc_id})
        message = f"docId '{doc_id}'에 대한 {to_delete}개의 청크를 삭제했습니다."
    else:
        # 삭제 대상이 없을 경우에도 안내 메시지를 제공한다
        message = f"docId '{doc_id}'에 해당하는 청크를 찾지 못했습니다."

    # 원래 페이지 파라미터로 리다이렉트하여 작업 결과를 보여준다
    return RedirectResponse(
        f"/admin/rag/view-page?limit={limit}&offset={offset}&with_documents={with_documents}&message={quote_plus(message)}",
        status_code=303,
    )


@router.post("/wipe-collection")
def wipe_collection(
    limit: int = Form(50),
    offset: int = Form(0),
    with_documents: str = Form("true"),
):
    """현재 선택된 컬렉션을 통째로 삭제"""

    # 최신 컬렉션 정보를 읽어 전체 삭제를 수행한다
    chroma_dir, collection_name = get_chroma_settings()
    client = chromadb.PersistentClient(path=chroma_dir)

    try:
        # 컬렉션 삭제 후에는 get_collection()이 비어 있는 컬렉션을 재생성한다
        client.delete_collection(collection_name)
        message = f"컬렉션 '{collection_name}'의 모든 청크를 삭제했습니다."
    except Exception as exc:
        # 삭제 실패 시 예외 메시지를 함께 전달한다
        message = f"컬렉션 삭제 중 오류가 발생했습니다: {exc}"

    return RedirectResponse(
        f"/admin/rag/view-page?limit={limit}&offset=0&with_documents={with_documents}&message={quote_plus(message)}",
        status_code=303,
    )

@router.get("/view-page", response_class=HTMLResponse)
def view_page(
    limit: int = Query(50, ge=1, le=5000, description="페이지 크기"),
    offset: int = Query(0, ge=0, description="시작 오프셋"),
    with_documents: bool = Query(True, description="본문 미리보기 포함"),
    message: Optional[str] = Query(None, description="작업 결과 메시지")
):
    """
    브라우저에서 표로 확인하는 HTML 페이지
    - 페이지네이션: limit/offset
    - 상단에 총 청크 수, 고유 문서 수 표시
    - 기본 컬럼: id, docId, page, source, chunkPreview
    """
    col = get_collection()
    chroma_dir, collection_name = get_chroma_settings()  # HTML 템플릿 상단에 최신 경로/컬렉션 정보를 보여주기 위해 조회함
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
<title>Chroma View - {html.escape(collection_name)}</title>
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
  .controls a, .controls button, .controls input[type=submit] {{
    background: var(--card); border:1px solid var(--border); color: var(--fg);
    padding: 6px 10px; border-radius: 8px; text-decoration:none; cursor:pointer;
  }}
  .controls form {{ display:flex; gap:6px; align-items:center; }}
  .controls input[type=text] {{
    background: var(--bg); border:1px solid var(--border); border-radius:8px;
    padding:6px 8px; color: var(--fg); min-width:160px;
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
  <h1>Chroma Collection: <span class="pill">{html.escape(collection_name)}</span></h1>

  {f'<div class="stats" style="border-color:#f87171; color:#fca5a5;">{html.escape(message)}</div>' if message else ''}

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
    <!-- docId 단위 삭제 폼 -->
    <form method="post" action="/admin/rag/delete-by-docid" onsubmit="return confirm('입력한 docId의 청크를 모두 삭제할까요?');">
      <input type="hidden" name="limit" value="{limit}">
      <input type="hidden" name="offset" value="{offset}">
      <input type="hidden" name="with_documents" value="{'true' if with_documents else 'false'}">
      <input type="text" name="doc_id" placeholder="docId 입력" required>
      <input type="submit" value="선택 docId 삭제">
    </form>
    <!-- 전체 삭제 폼 -->
    <form method="post" action="/admin/rag/wipe-collection" onsubmit="return confirm('현재 컬렉션의 모든 청크를 삭제합니다. 진행할까요?');">
      <input type="hidden" name="limit" value="{limit}">
      <input type="hidden" name="with_documents" value="{'true' if with_documents else 'false'}">
      <input type="submit" value="전체 삭제">
    </form>    
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
