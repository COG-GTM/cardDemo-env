ARG BASE_IMAGE=ubuntu:24.04
FROM ${BASE_IMAGE}

# TK5 ships prebuilt SDL Hercules 4.x binaries linked against glibc 2.38,
# so the base must be Ubuntu 24.04 or newer.
ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates curl unzip python3 s3270 c3270 file \
    && rm -rf /var/lib/apt/lists/*

# Distribution archives. The URLs can be overridden for a local mirror; the
# SHA256 values are pinned to the files fetched on 2026-09-16.
ARG TK5_URL=https://www.prince-webdesign.nl/images/downloads/mvs-tk5.zip
ARG TK5_SHA256=710d002843631322810a276dd42c793fda458548dc64d86e2914a62db7425f84
ARG KICKS_URL=http://www.kicksfortso.com/kicks-tso-v1r5m0.zip
ARG KICKS_SHA256=e0df01bee1988c1c73dbb0a49e082e8ae9fda5822a02f8b9c798583565c177f1

WORKDIR /opt
RUN curl -fsSL -o mvs-tk5.zip "${TK5_URL}" \
    && echo "${TK5_SHA256}  mvs-tk5.zip" | sha256sum -c - \
    && unzip -q mvs-tk5.zip \
    && rm mvs-tk5.zip \
    && mv mvs-tk5 mvs \
    && chmod +x /opt/mvs/hercules/linux/64/bin/* \
    && rm -rf /opt/mvs/hercules/windows /opt/mvs/hercules/darwin \
              /opt/mvs/hercules/linux/32 /opt/mvs/hercules/linux/arm* \
              /opt/mvs/hercules/linux/aarch64 \
              /opt/mvs/hercules/external_packages_binaries \
              /opt/mvs/hercules/external_packages_source \
              /opt/mvs/hercules/hercules_source

RUN curl -fsSL -o kicks.zip "${KICKS_URL}" \
    && echo "${KICKS_SHA256}  kicks.zip" | sha256sum -c - \
    && unzip -q -j kicks.zip kicks-tso-v1r5m0/kicks-tso-v1r5m0.xmi \
         kicks-tso-v1r5m0/kicks-license.txt -d kicks \
    && rm kicks.zip \
    && mv kicks/kicks-tso-v1r5m0.xmi /opt/mvs/jcl/kicks.xmi \
    && mv kicks/kicks-license.txt /opt/mvs/KICKS-LICENSE.txt

# Unattended (daemon) mode: no interactive Hercules console, the panel is
# replaced by the HTTP console on 8038 and the 3215-C console log.
RUN echo DAEMON > /opt/mvs/unattended/mode

ENV PATH=/opt/mvs/hercules/linux/64/bin:$PATH \
    LD_LIBRARY_PATH=/opt/mvs/hercules/linux/64/lib:/opt/mvs/hercules/linux/64/lib/hercules \
    HERCULES_RC=scripts/ipl.rc \
    MAINSIZE=64 \
    PYTHONUNBUFFERED=1

COPY entrypoint.sh mvs3270.py kicks_install.py kicks_build.py /opt/kicks/
COPY minimal /opt/kicks/minimal
COPY carddemo /opt/kicks/carddemo
RUN chmod +x /opt/kicks/entrypoint.sh

WORKDIR /opt/mvs
EXPOSE 3270 8038 3505
ENTRYPOINT ["/opt/kicks/entrypoint.sh"]
