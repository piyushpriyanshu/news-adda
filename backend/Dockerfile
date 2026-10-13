# Self-hosted News Adda backend (FastAPI + built-in scheduler).
FROM python:3.12-slim
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY newsadda ./newsadda
ENV NEWSADDA_DB=/data/newsadda.db \
    NEWSADDA_POLL_TICK=60 \
    PYTHONUNBUFFERED=1
VOLUME /data
EXPOSE 8000
CMD ["python", "-m", "newsadda", "serve", "--host", "0.0.0.0", "--port", "8000"]
