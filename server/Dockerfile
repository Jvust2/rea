FROM python:3.13-slim-trixie
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg nodejs ca-certificates \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY android-app/app/src/main/python/ /app/android-app/app/src/main/python/
COPY server/ /app/server/
RUN pip install --no-cache-dir 'gallery-dl==1.32.17' 'requests[socks]>=2.32.2,<3' 'yt-dlp-ejs==0.8.0' \
    'certifi' 'mutagen' 'websockets>=13.0' \
    && test -d /app/android-app/app/src/main/python/yt_dlp \
    && useradd -u 10001 -m flowsave && mkdir /data && chown flowsave /data
USER 10001:10001
CMD ["python", "/app/server/app.py"]
