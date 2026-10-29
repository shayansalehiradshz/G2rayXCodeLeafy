FROM golang:1.27.3-bookworm AS xray-build
COPY build-xray.sh /build/build-xray.sh
COPY patches/ /build/patches/
COPY fixtures/ /build/fixtures/
RUN GOTOOLCHAIN=local bash /build/build-xray.sh /out

FROM debian:bookworm-slim

ARG XRAY_VERSION=v26.10.21

LABEL maintainer="G2rayXCodeLeafy" \
      description="Educational G2rayXCodeLeafy Codespaces environment"

RUN apt-get update && apt-get install -y --no-install-recommends \
        bash curl wget unzip ca-certificates sudo openssl uuid-runtime tmux tzdata \
        qrencode jq bc shellcheck net-tools libcap2-bin iproute2 dnsutils \
        python3 python-is-python3 gnupg \
    && mkdir -p /etc/apt/keyrings \
    && curl -fsSL https://deb.nodesource.com/gpgkey/nodesource-repo.gpg.key \
        | gpg --dearmor -o /etc/apt/keyrings/nodesource.gpg \
    && echo "deb [signed-by=/etc/apt/keyrings/nodesource.gpg] https://deb.nodesource.com/node_22.x nodistro main" \
        > /etc/apt/sources.list.d/nodesource.list \
    && apt-get update && apt-get install -y --no-install-recommends nodejs \
    && apt-get clean && rm -rf /var/lib/apt/lists/*

RUN wget -qO xray.zip "https://github.com/XTLS/Xray-core/releases/download/${XRAY_VERSION}/Xray-linux-64.zip" \
    && wget -qO xray.zip.dgst "https://github.com/XTLS/Xray-core/releases/download/${XRAY_VERSION}/Xray-linux-64.zip.dgst" \
    && awk -F'= ' '/^SHA2-256=/{print $2 "  xray.zip"}' xray.zip.dgst | sha256sum -c - \
    && unzip -q xray.zip -d /tmp/xray-dir \
    && mkdir -p /usr/local/lib/g2ray \
    && mv /tmp/xray-dir/xray /usr/local/lib/g2ray/xray-official \
    && mv /tmp/xray-dir/*.dat /usr/local/bin/ \
    && chmod +x /usr/local/lib/g2ray/xray-official \
    && rm -rf xray.zip xray.zip.dgst /tmp/xray-dir

COPY --from=xray-build /out/xray /usr/local/bin/xray
COPY --from=xray-build /out/xray-build.properties /usr/local/share/g2ray/xray-build.properties
RUN test "$XRAY_VERSION" = v26.10.21 && chmod +x /usr/local/bin/xray

EXPOSE 443
