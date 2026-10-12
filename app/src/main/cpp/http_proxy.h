#ifndef HTTP_PROXY_H
#define HTTP_PROXY_H

#include <stddef.h> /* size_t */

/*
 * HTTP 프록시(CONNECT + 평문 absolute-form) 요청 파싱의 순수 함수
 * (Android / JNI / socket 의존 없음 → host 단위 테스트 가능).
 *
 * SOCKS5와 같은 포트를 공유한다: 첫 바이트가 0x05면 SOCKS5, ASCII 대문자면 HTTP.
 * 연결 수립과 relay는 simple-socks5.c가 SOCKS5 CONNECT와 같은 경로로 처리한다.
 */

#ifdef __cplusplus
extern "C" {
#endif

/* 요청 헤더 블록(요청 줄 ~ 빈 줄)의 최대 크기. 넘으면 431로 거절한다. */
#define HTTP_PROXY_MAX_HEADER 8192

typedef struct {
    int is_connect;          /* 1 = CONNECT(터널), 0 = 평문 HTTP(absolute-form) */
    char host[256];          /* 대상 호스트(IPv6는 대괄호 없이) */
    int port;                /* 대상 포트 */
    size_t header_len;       /* "\r\n\r\n"까지 포함한 헤더 블록 길이 */

    /* absolute-form 재작성용 오프셋(buf 기준) */
    size_t method_len;
    size_t path_off, path_len;       /* "/path?query" (없으면 path_len=0 → "/") */
    size_t version_off, version_len; /* "HTTP/1.1" */
    size_t headers_off;              /* 첫 헤더 줄 시작 */
    size_t authority_off, authority_len;
    int has_host;

    /* Proxy-Authorization: Basic <token> 의 token 위치(없으면 len=0) */
    size_t auth_off, auth_len;
} http_proxy_req_t;

/* 첫 바이트가 HTTP 메서드의 시작(A-Z)인지. SOCKS5(0x05)와 구분하는 데 쓴다. */
int http_proxy_looks_like_http(unsigned char first);

/* 헤더 블록 파싱.
 *  0 = 성공, 1 = 아직 "\r\n\r\n"이 없음(더 읽어야 함),
 * -1 = 형식 오류/미지원(400), -2 = 헤더가 HTTP_PROXY_MAX_HEADER 초과(431). */
int http_proxy_parse(const char *buf, size_t len, http_proxy_req_t *out);

/* Proxy-Authorization Basic token(base64 "user:pass")이 기대값과 같으면 1. */
int http_proxy_check_basic_auth(const char *token, size_t token_len,
                                const char *expected_user, const char *expected_pass);

/* absolute-form 요청을 origin-form으로 바꾼 헤더 블록을 out에 쓴다.
 * Proxy-*, Connection, Keep-Alive 헤더는 버리고 "Connection: close"를 붙인다
 * (한 클라이언트 연결 = 한 upstream 호스트 보장). Host가 없으면 authority로 채운다.
 * 반환: 쓴 바이트 수, out이 작으면 -1. */
int http_proxy_rewrite_request(const char *buf, const http_proxy_req_t *req,
                               char *out, size_t cap);

#ifdef __cplusplus
}
#endif

#endif /* HTTP_PROXY_H */
