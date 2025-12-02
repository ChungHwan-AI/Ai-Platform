# test_websearch.py
import json
from fastapi import FastAPI, Form
from fastapi.responses import HTMLResponse, JSONResponse
from openai import OpenAI
import uvicorn

# -------------------------------------------------------
# 🔥 여기만 네 환경에 맞게 고쳐줘
OPENAI_API_KEY = ""
MODEL_NAME = "gpt-4.1-mini"   # 사용 중인 Responses 모델 (gpt-4.1 / gpt-4.1-mini / gpt-5.1 등)
# -------------------------------------------------------

client = OpenAI(api_key=OPENAI_API_KEY)
app = FastAPI()

HTML_PAGE = """
<!doctype html>
<html lang="ko">
<head>
  <meta charset="utf-8" />
  <title>WebSearch 테스트 페이지</title>
  <style>
    body { font-family: system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif; max-width: 900px; margin: 40px auto; }
    textarea { width: 100%; height: 80px; }
    .box { padding: 12px; border: 1px solid #aaa; margin-top: 16px; white-space: pre-wrap; border-radius: 8px; }
    button { padding: 8px 20px; margin-top: 10px; cursor: pointer; }
    .tag { padding: 3px 8px; border-radius: 999px; font-size: 12px; display: inline-block; }
    .ok { background: #d1fae5; color: #065f46; }
    .no { background: #fee2e2; color: #991b1b; }
  </style>
</head>
<body>
  <h1>🔍 Web Search 동작 테스트</h1>
  <p>이 페이지는 OpenAI Responses API를 <code>tools=[{"type": "web_search"}]</code>로 호출해서,<br>
     실제로 web_search가 사용되는지 확인하기 위한 테스트입니다.</p>

  <form id="test-form">
    <textarea name="question">지금 원/달러 환율이 얼마야?</textarea><br/>
    <button type="submit">테스트</button>
  </form>

  <h3 id="status"></h3>
  <div id="answer" class="box" style="display:none;"></div>
  <div id="raw" class="box" style="display:none; background:#111; color:#eee;"></div>

<script>
const form = document.getElementById("test-form");
const statusEl = document.getElementById("status");
const answerEl = document.getElementById("answer");
const rawEl = document.getElementById("raw");

form.addEventListener("submit", async (e) => {
  e.preventDefault();
  statusEl.innerHTML = "요청 중...";
  answerEl.style.display = "none";
  rawEl.style.display = "none";

  const formData = new FormData(form);
  const res = await fetch("/api/test", { method: "POST", body: formData });

  if (!res.ok) {
    statusEl.innerHTML = "에러: " + res.status + " " + res.statusText;
    return;
  }

  const data = await res.json();

  const tag = data.used_web_search
      ? '<span class="tag ok">web_search 사용됨</span>'
      : '<span class="tag no">web_search 사용 안됨</span>';

  statusEl.innerHTML = "결과: " + tag;

  answerEl.innerHTML = data.answer || "(answer 비어 있음)";
  answerEl.style.display = "block";

  rawEl.innerHTML = JSON.stringify(data.raw, null, 2);
  rawEl.style.display = "block";
});
</script>
</body>
</html>
"""


@app.get("/", response_class=HTMLResponse)
async def index():
    return HTML_PAGE


@app.post("/api/test")
async def test(question: str = Form(...)):
    """
    질문을 받아서 Responses API를 tools=[{"type": "web_search"}]로 호출하고,
    응답 전체 JSON 안에 'web_search' 문자열이 있는지만 보고 사용 여부를 판단한다.
    """
    resp = client.responses.create(
        model=MODEL_NAME,
        input=question,
        tools=[{"type": "web_search"}],  # web_search_preview로 바꿔볼 수도 있음
    )

    # SDK 버전에 상관없이 가장 안전하게 텍스트를 뽑는 방법:
    answer = getattr(resp, "output_text", "")
    if not answer:
        # 그래도 없으면 그냥 문자열로 캐스팅
        answer = str(resp)

    # RAW 데이터 전체
    raw = resp.model_dump()
    raw_text = json.dumps(raw, ensure_ascii=False)

    # web_search 호출 여부 (대략적으로만 체크)
    used = "web_search" in raw_text.lower()

    return JSONResponse({
        "answer": answer,
        "used_web_search": used,
        "raw": raw,
    })


if __name__ == "__main__":
    uvicorn.run(app, host="127.0.0.1", port=8000)
