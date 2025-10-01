# rag-backend/utils/encoding.py
"""
인코딩 자동 감지 → UTF-8 통일 → 한글 정규화 도우미

- to_utf8_text(raw): bytes → str(UTF-8, NFC 정규화)
- read_text_file(path): 파일에서 안전하게 텍스트 읽기
- normalize_text(s): 이미 str인 텍스트를 NFC 정규화

필수 의존성:
  pip install -U charset-normalizer>=3.3
"""

from __future__ import annotations
from charset_normalizer import from_bytes
import unicodedata
from typing import Optional


def to_utf8_text(raw: bytes, *, errors: str = "replace") -> str:
    """
    바이너리(raw)를 인코딩 자동 감지로 안전하게 유니코드 문자열로 변환.
    - 주로 Windows cp949/utf-8 혼선 방지
    - 실패시 errors='replace'로 손실 최소화
    - 한글 자모 분해/조합 문제 방지를 위해 NFC 정규화 적용
    """
    if not isinstance(raw, (bytes, bytearray)):
        raise TypeError(f"to_utf8_text expects bytes, got {type(raw)}")

    res = from_bytes(raw).best()
    if res is not None:
        # charset-normalizer가 내부적으로 디코딩한 유니코드 문자열 반환
        text = str(res)
    else:
        # 최후의 보루: utf-8 / cp949 순으로 시도
        try:
            text = raw.decode("utf-8", errors=errors)
        except Exception:
            text = raw.decode("cp949", errors=errors)

    # 한글/조합문자 정규화
    return unicodedata.normalize("NFC", text)


def normalize_text(s: Optional[str]) -> str:
    """
    이미 str인 텍스트를 NFC 정규화만 적용해서 반환.
    None이면 빈 문자열 반환.
    """
    if s is None:
        return ""
    if not isinstance(s, str):
        # 혹시 bytes가 들어오면 안전하게 처리
        return to_utf8_text(s)  # type: ignore[arg-type]
    return unicodedata.normalize("NFC", s)


def read_text_file(path: str) -> str:
    """
    텍스트 파일(TXT/CSV/LOG/MD 등)을 바이너리로 읽은 뒤
    인코딩 자동 감지 → UTF-8로 통일해 반환.
    """
    with open(path, "rb") as f:
        raw = f.read()
    return to_utf8_text(raw)


# 사용 예시:
# from utils.encoding import to_utf8_text, read_text_file, normalize_text
# text = to_utf8_text(content_bytes)
# text = read_text_file("보고서.txt")
# text = normalize_text(text)
