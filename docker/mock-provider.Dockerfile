# The capstone pack's mock OpenAI-compatible provider as an image, so the demo stack can run from
# images alone. One image serves as both providers; the name and port are chosen at run time:
#
#   docker run -p 9001:9001 prism-mock-provider:0.0.1 --port 9001 --name alpha
#
# Build from the repository root:
#   docker build -f docker/mock-provider.Dockerfile -t prism-mock-provider:0.0.1 .

FROM python:3.12-slim
WORKDIR /app
COPY scripts/mock_provider.py /app/mock_provider.py
ENV PYTHONUNBUFFERED=1
USER nobody
ENTRYPOINT ["python", "/app/mock_provider.py"]
CMD ["--port", "9001", "--name", "alpha"]
