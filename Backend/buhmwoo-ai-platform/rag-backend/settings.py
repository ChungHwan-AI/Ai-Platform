import os
# inspect_chroma.py
#import chromadb
from dotenv import load_dotenv

load_dotenv()  # .env 로드

GEMINI_API_KEY = os.getenv("GEMINI_API_KEY", "") or os.getenv("GOOGLE_API_KEY", "")
CHROMA_DIR = os.getenv("CHROMA_DIR", "./chroma_db")

if not GEMINI_API_KEY:
    raise RuntimeError("GEMINI_API_KEY is not set")


CHROMA_DIR = "./chroma_db"  # settings.py 와 동일하게 맞추기
#client = chromadb.PersistentClient(path=CHROMA_DIR)

# 어떤 컬렉션이 있는지
#print("collections:", [c.name for c in client.list_collections()])

# LangChain 기본 컬렉션명은 보통 "langchain"
#col = client.get_collection("langchain")

#print("count:", col.count())

# 샘플 n개 훑어보기 (id, document, metadata)
#print("peek:", col.peek(5))  # 작은 규모면 이걸로 충분

# 좀 더 제어해서 가져오기 (임베딩은 제외 권장)
#res = col.get(limit=3, include=["documents", "metadatas", "ids"])
#print(res)

# 특정 문서(UUID)로 필터해서 보기
doc_id = "00000000-0000-0000-0000-000000000000"  # 실제 UUID 넣기
#res = col.get(where={"docId": doc_id}, limit=5, include=["documents", "metadatas", "ids"])
#print(res)
