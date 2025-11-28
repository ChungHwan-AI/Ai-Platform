FROM python:3.11-slim
WORKDIR /app

RUN apt-get update && apt-get install -y build-essential && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY . .
# FastAPI 컨테이너가 실제로 노출하는 포트를 8001로 명시해 프런트/백엔드 설정과 일관성을 맞춥니다.
EXPOSE 8001
# Uvicorn 실행 시에도 8001 포트로 구동하도록 지정해 서비스 접근 경로를 통일합니다.
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8001"]
