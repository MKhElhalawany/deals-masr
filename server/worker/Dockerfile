# Playwright image ships Chromium + all system deps (multi-arch: works on Oracle ARM and x86)
FROM mcr.microsoft.com/playwright/python:v1.48.0-jammy
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY *.py ./
ENV PYTHONUNBUFFERED=1
USER pwuser
CMD ["python", "worker.py"]
