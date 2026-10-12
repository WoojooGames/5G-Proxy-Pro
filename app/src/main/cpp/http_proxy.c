#include "http_proxy.h"

#include <string.h>

int http_proxy_looks_like_http(unsigned char first) {
    return first >= 'A' && first <= 'Z';
}

static int lower(int c) { return (c >= 'A' && c <= 'Z') ? c + 32 : c; }

static int ieq_prefix(const char *s, size_t len, const char *prefix) {
    size_t n = strlen(prefix);
    if (len < n) return 0;
    for (size_t i = 0; i < n; i++) {
        if (lower((unsigned char)s[i]) != lower((unsigned char)prefix[i])) return 0;
    }
    return 1;
}

/* 헤더 줄이 "name:"으로 시작하는지(대소문자 무시) */
static int header_is(const char *line, size_t len, const char *name) {
    size_t n = strlen(name);
    return len > n && line[n] == ':' && ieq_prefix(line, len, name);
}

static const char *find_crlf(const char *p, const char *end) {
    for (; p + 1 < end; p++) {
        if (p[0] == '\r' && p[1] == '\n') return p;
    }
    return NULL;
}

/* "host:port" / "[v6]:port" / "host"(default_port) → out. 성공 0, 실패 -1. */
static int parse_authority(const char *a, size_t len, int default_port, http_proxy_req_t *out) {
    const char *host;
    size_t hlen;
    const char *port_s = NULL;
    size_t port_len = 0;
    if (len == 0) return -1;
    if (a[0] == '[') {
        const char *rb = memchr(a, ']', len);
        if (!rb) return -1;
        host = a + 1;
        hlen = (size_t)(rb - host);
        size_t rest = len - (size_t)(rb + 1 - a);
        if (rest > 0) {
            if (rb[1] != ':') return -1;
            port_s = rb + 2;
            port_len = rest - 1;
        }
    } else {
        const char *colon = NULL;
        for (size_t i = 0; i < len; i++) if (a[i] == ':') colon = a + i;
        host = a;
        if (colon) {
            hlen = (size_t)(colon - a);
            port_s = colon + 1;
            port_len = len - hlen - 1;
        } else {
            hlen = len;
        }
    }
    if (hlen == 0 || hlen >= sizeof(out->host)) return -1;
    for (size_t i = 0; i < hlen; i++) {
        unsigned char c = (unsigned char)host[i];
        if (c <= 0x20 || c == '/' || c == '@' || c >= 0x7F) return -1;
    }
    int port = default_port;
    if (port_s) {
        if (port_len == 0 || port_len > 5) return -1;
        port = 0;
        for (size_t i = 0; i < port_len; i++) {
            if (port_s[i] < '0' || port_s[i] > '9') return -1;
            port = port * 10 + (port_s[i] - '0');
        }
    }
    if (port <= 0 || port > 65535) return -1;
    memcpy(out->host, host, hlen);
    out->host[hlen] = '\0';
    out->port = port;
    return 0;
}

int http_proxy_parse(const char *buf, size_t len, http_proxy_req_t *out) {
    memset(out, 0, sizeof(*out));
    /* 헤더 끝 찾기 */
    size_t scan = len < HTTP_PROXY_MAX_HEADER ? len : HTTP_PROXY_MAX_HEADER;
    size_t hdr_end = 0;
    for (size_t i = 3; i < scan; i++) {
        if (buf[i - 3] == '\r' && buf[i - 2] == '\n' && buf[i - 1] == '\r' && buf[i] == '\n') {
            hdr_end = i + 1;
            break;
        }
    }
    if (hdr_end == 0) return len >= HTTP_PROXY_MAX_HEADER ? -2 : 1;
    out->header_len = hdr_end;
    const char *end = buf + hdr_end;

    /* 요청 줄: METHOD SP target SP HTTP/x.y CRLF */
    const char *eol = find_crlf(buf, end);
    if (!eol) return -1;
    const char *sp1 = memchr(buf, ' ', (size_t)(eol - buf));
    if (!sp1 || sp1 == buf) return -1;
    const char *target = sp1 + 1;
    const char *sp2 = memchr(target, ' ', (size_t)(eol - target));
    if (!sp2 || sp2 == target) return -1;
    const char *version = sp2 + 1;
    if (!ieq_prefix(version, (size_t)(eol - version), "HTTP/1.")) return -1;

    out->method_len = (size_t)(sp1 - buf);
    for (size_t i = 0; i < out->method_len; i++) {
        if (buf[i] < 'A' || buf[i] > 'Z') return -1;
    }
    out->version_off = (size_t)(version - buf);
    out->version_len = (size_t)(eol - version);
    size_t tlen = (size_t)(sp2 - target);

    if (out->method_len == 7 && memcmp(buf, "CONNECT", 7) == 0) {
        out->is_connect = 1;
        /* CONNECT는 포트 필수 */
        if (parse_authority(target, tlen, 0, out) != 0) return -1;
        out->authority_off = (size_t)(target - buf);
        out->authority_len = tlen;
    } else {
        /* 프록시 요청은 absolute-form(http://...)이어야 한다. https:// absolute-form은
         * 클라이언트가 TLS를 프록시에 맡기는 형태라 미지원 → 400. */
        if (!ieq_prefix(target, tlen, "http://")) return -1;
        const char *auth = target + 7;
        const char *tend = target + tlen;
        const char *p = auth;
        while (p < tend && *p != '/' && *p != '?') p++;
        if (memchr(auth, '@', (size_t)(p - auth))) return -1; /* userinfo 미지원 */
        if (parse_authority(auth, (size_t)(p - auth), 80, out) != 0) return -1;
        out->authority_off = (size_t)(auth - buf);
        out->authority_len = (size_t)(p - auth);
        out->path_off = (size_t)(p - buf);
        out->path_len = (size_t)(tend - p);
    }

    /* 헤더 줄 */
    out->headers_off = (size_t)(eol + 2 - buf);
    const char *line = eol + 2;
    while (line < end - 2) {
        const char *le = find_crlf(line, end);
        if (!le) return -1;
        size_t ll = (size_t)(le - line);
        if (header_is(line, ll, "Host")) out->has_host = 1;
        if (header_is(line, ll, "Proxy-Authorization")) {
            const char *v = line + strlen("Proxy-Authorization") + 1;
            while (v < le && (*v == ' ' || *v == '\t')) v++;
            if (ieq_prefix(v, (size_t)(le - v), "Basic ")) {
                v += 6;
                while (v < le && *v == ' ') v++;
                const char *ve = le;
                while (ve > v && (ve[-1] == ' ' || ve[-1] == '\t')) ve--;
                out->auth_off = (size_t)(v - buf);
                out->auth_len = (size_t)(ve - v);
            }
        }
        line = le + 2;
    }
    return 0;
}

static int b64val(int c) {
    if (c >= 'A' && c <= 'Z') return c - 'A';
    if (c >= 'a' && c <= 'z') return c - 'a' + 26;
    if (c >= '0' && c <= '9') return c - '0' + 52;
    if (c == '+') return 62;
    if (c == '/') return 63;
    return -1;
}

int http_proxy_check_basic_auth(const char *token, size_t token_len,
                                const char *expected_user, const char *expected_pass) {
    unsigned char dec[600];
    size_t dlen = 0;
    unsigned int acc = 0;
    int bits = 0;
    if (token_len == 0 || token_len > 800) return 0;
    for (size_t i = 0; i < token_len; i++) {
        if (token[i] == '=') break;
        int v = b64val((unsigned char)token[i]);
        if (v < 0) return 0;
        acc = (acc << 6) | (unsigned int)v;
        bits += 6;
        if (bits >= 8) {
            bits -= 8;
            if (dlen >= sizeof(dec)) return 0;
            dec[dlen++] = (unsigned char)((acc >> bits) & 0xFF);
        }
    }
    size_t ulen = strlen(expected_user);
    size_t plen = strlen(expected_pass);
    if (dlen != ulen + 1 + plen) return 0;
    /* 길이가 같을 때 전체를 끝까지 비교(조기 종료 없음) */
    unsigned char diff = 0;
    for (size_t i = 0; i < ulen; i++) diff |= dec[i] ^ (unsigned char)expected_user[i];
    diff |= dec[ulen] ^ ':';
    for (size_t i = 0; i < plen; i++) diff |= dec[ulen + 1 + i] ^ (unsigned char)expected_pass[i];
    return diff == 0;
}

static int append(char *out, size_t cap, size_t *pos, const char *s, size_t n) {
    if (*pos + n > cap) return -1;
    memcpy(out + *pos, s, n);
    *pos += n;
    return 0;
}

int http_proxy_rewrite_request(const char *buf, const http_proxy_req_t *req,
                               char *out, size_t cap) {
    size_t pos = 0;
    if (append(out, cap, &pos, buf, req->method_len) ||
        append(out, cap, &pos, " ", 1)) return -1;
    if (req->path_len == 0) {
        if (append(out, cap, &pos, "/", 1)) return -1;
    } else if (buf[req->path_off] == '?') {
        /* "http://host?q" → "/?q" */
        if (append(out, cap, &pos, "/", 1) ||
            append(out, cap, &pos, buf + req->path_off, req->path_len)) return -1;
    } else {
        if (append(out, cap, &pos, buf + req->path_off, req->path_len)) return -1;
    }
    if (append(out, cap, &pos, " ", 1) ||
        append(out, cap, &pos, buf + req->version_off, req->version_len) ||
        append(out, cap, &pos, "\r\n", 2)) return -1;

    if (!req->has_host) {
        if (append(out, cap, &pos, "Host: ", 6) ||
            append(out, cap, &pos, buf + req->authority_off, req->authority_len) ||
            append(out, cap, &pos, "\r\n", 2)) return -1;
    }

    const char *end = buf + req->header_len - 2; /* 마지막 빈 줄 제외 */
    const char *line = buf + req->headers_off;
    while (line < end) {
        const char *le = find_crlf(line, end + 2);
        if (!le) return -1;
        size_t ll = (size_t)(le - line);
        int drop = ieq_prefix(line, ll, "Proxy-") ||
                   header_is(line, ll, "Connection") ||
                   header_is(line, ll, "Keep-Alive");
        if (!drop && append(out, cap, &pos, line, ll + 2)) return -1;
        line = le + 2;
    }
    if (append(out, cap, &pos, "Connection: close\r\n\r\n", 21)) return -1;
    return (int)pos;
}
