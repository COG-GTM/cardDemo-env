ARG BASE_IMAGE=ubuntu:22.04
FROM ${BASE_IMAGE}

ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y --no-install-recommends \
    gnucobol3 libpq-dev postgresql-client make python3 python3-psycopg2 \
    git build-essential \
    autoconf automake libtool bison flex ca-certificates pkg-config \
    && rm -rf /var/lib/apt/lists/*

RUN git clone --depth 1 --branch v1.4 \
      https://github.com/opensourcecobol/Open-COBOL-ESQL.git /tmp/ocesql \
    && cd /tmp/ocesql \
    && autoreconf -fiv \
    && ./configure --prefix=/usr/local \
    && make -j"$(nproc)" \
    && make install \
    && rm -rf /tmp/ocesql

ENV COB_LIBRARY_PATH=/estate/loadlib
ENV LD_LIBRARY_PATH=/usr/local/lib:/usr/lib/x86_64-linux-gnu
WORKDIR /estate
CMD ["sleep", "infinity"]
