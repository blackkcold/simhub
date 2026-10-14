FROM python:3.13-slim
RUN pip install --no-cache-dir cryptography==46.0.3
WORKDIR /app
COPY server/peer_connect.py /app/peer_connect.py
RUN useradd --uid 10001 --create-home peer && mkdir /data && chown peer /data
USER 10001
ENV P2P_BIND=0.0.0.0 P2P_PORT=8787 P2P_DB=/data/peer-connect.db
EXPOSE 8787
CMD ["python", "peer_connect.py"]
