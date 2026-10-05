FROM python:3.13-alpine
WORKDIR /app
COPY server /app/server
COPY web /app/web
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 SIMHUB_WEB_ROOT=/app/web SIMHUB_DB=/data/simhub.db
VOLUME ["/data"]
EXPOSE 8787
USER nobody
CMD ["python3", "/app/server/simhub_server.py"]
