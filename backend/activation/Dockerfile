# Servidor de ativação local: entrega à lâmpada a chave que o Valkyris está
# usando na janela de pareamento. A imagem final não leva compilador.
FROM python:3.12-slim AS builder

RUN apt-get update \
    && apt-get install -y --no-install-recommends gcc python3-dev libssl-dev \
    && rm -rf /var/lib/apt/lists/*

RUN pip wheel --no-cache-dir --wheel-dir /wheels \
    cryptography==45.0.7 \
    sslpsk==1.0.0

FROM python:3.12-slim

COPY --from=builder /wheels /wheels
RUN pip install --no-cache-dir --no-index --find-links /wheels cryptography sslpsk \
    && rm -rf /wheels

WORKDIR /opt/activation
COPY server.py /opt/activation/server.py

# As portas 80 e 443 exigem privilégio dentro do container; o serviço roda com
# no-new-privileges e o banco do Valkyris é montado somente para leitura.
EXPOSE 80 443 8886
CMD ["python3", "/opt/activation/server.py", "--database", "/data/valkyris.db"]
