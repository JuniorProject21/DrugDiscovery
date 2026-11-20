# InhibitAI backend. Build from the repository root:  docker build -t inhibitai-api backend
# Put the model bundle in backend/models/ first (or mount it at /app/models when running).
FROM python:3.12-slim

# RDKit's drawing module needs these system libraries, which slim images leave out
# (without libexpat the server can't even import it).
RUN apt-get update \
 && apt-get install -y --no-install-recommends libexpat1 libxrender1 libxext6 \
 && rm -rf /var/lib/apt/lists/*

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1 \
    PIP_ROOT_USER_ACTION=ignore \
    INHIBITAI_ENVIRONMENT=production \
    PORT=8000

WORKDIR /app
COPY requirements.txt .
RUN pip install -r requirements.txt

COPY app ./app
COPY models ./models

RUN useradd --create-home --uid 10001 inhibitai
USER inhibitai

EXPOSE 8000
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
  CMD python -c "import os, urllib.request; urllib.request.urlopen('http://127.0.0.1:%s/api/v1/health' % os.environ.get('PORT', '8000'), timeout=4)"

# --proxy-headers: behind the hosting proxy, the client address comes from X-Forwarded-For,
# which the sign-in rate limits rely on. Hosts that assign a port pass it in $PORT.
CMD ["sh", "-c", "exec uvicorn app.main:create_app --factory --host 0.0.0.0 --port \"$PORT\" --proxy-headers --forwarded-allow-ips='*'"]
