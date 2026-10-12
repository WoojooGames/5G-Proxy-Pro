/*
 * http_proxy 의 host 단위 테스트(자체 단언, 프레임워크 의존 없음).
 * 실행: app/build.gradle 의 cProtocolTest task.
 */
#include "http_proxy.h"

#include <stdio.h>
#include <string.h>

static int g_failures = 0;

#define CHECK(cond) do {                                          \
    if (!(cond)) {                                                \
        fprintf(stderr, "FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond); \
        g_failures++;                                             \
    }                                                             \
} while (0)

static int parse(const char *s, http_proxy_req_t *r) {
    return http_proxy_parse(s, strlen(s), r);
}

static void test_looks_like_http(void) {
    CHECK(http_proxy_looks_like_http('C') == 1);
    CHECK(http_proxy_looks_like_http('G') == 1);
    CHECK(http_proxy_looks_like_http(0x05) == 0);
    CHECK(http_proxy_looks_like_http('c') == 0);
}

static void test_connect(void) {
    http_proxy_req_t r;
    const char *s = "CONNECT example.com:443 HTTP/1.1\r\nHost: example.com:443\r\n\r\n";
    CHECK(parse(s, &r) == 0);
    CHECK(r.is_connect == 1);
    CHECK(strcmp(r.host, "example.com") == 0);
    CHECK(r.port == 443);
    CHECK(r.header_len == strlen(s));
    CHECK(r.auth_len == 0);

    CHECK(parse("CONNECT [2001:db8::1]:8443 HTTP/1.1\r\n\r\n", &r) == 0);
    CHECK(strcmp(r.host, "2001:db8::1") == 0);
    CHECK(r.port == 8443);

    CHECK(parse("CONNECT example.com HTTP/1.1\r\n\r\n", &r) == -1);       /* 포트 필수 */
    CHECK(parse("CONNECT example.com:0 HTTP/1.1\r\n\r\n", &r) == -1);
    CHECK(parse("CONNECT example.com:65536 HTTP/1.1\r\n\r\n", &r) == -1);
    CHECK(parse("CONNECT example.com:44a HTTP/1.1\r\n\r\n", &r) == -1);
    CHECK(parse("CONNECT example.com:443 SPDY/3\r\n\r\n", &r) == -1);
}

static void test_incomplete_and_oversize(void) {
    http_proxy_req_t r;
    CHECK(parse("CONNECT example.com:443 HTTP/1.1\r\nHost: x\r\n", &r) == 1);
    static char big[HTTP_PROXY_MAX_HEADER + 10];
    memset(big, 'A', sizeof(big));
    CHECK(http_proxy_parse(big, sizeof(big), &r) == -2);
}

static void test_absolute_form_and_rewrite(void) {
    http_proxy_req_t r;
    const char *s =
        "GET http://example.com:8080/a/b?x=1 HTTP/1.1\r\n"
        "Host: example.com:8080\r\n"
        "Proxy-Connection: keep-alive\r\n"
        "Proxy-Authorization: Basic dTpw\r\n"
        "Connection: keep-alive\r\n"
        "Keep-Alive: 300\r\n"
        "Accept: */*\r\n"
        "\r\n";
    CHECK(parse(s, &r) == 0);
    CHECK(r.is_connect == 0);
    CHECK(strcmp(r.host, "example.com") == 0);
    CHECK(r.port == 8080);
    char out[1024];
    int n = http_proxy_rewrite_request(s, &r, out, sizeof(out));
    CHECK(n > 0);
    out[n > 0 ? n : 0] = '\0';
    CHECK(strcmp(out,
        "GET /a/b?x=1 HTTP/1.1\r\n"
        "Host: example.com:8080\r\n"
        "Accept: */*\r\n"
        "Connection: close\r\n\r\n") == 0);
    CHECK(http_proxy_rewrite_request(s, &r, out, 10) == -1); /* 버퍼 부족 */

    /* 경로 없음 + Host 헤더 없음 → "/" 와 Host 보충, 기본 포트 80 */
    const char *s2 = "GET http://example.com HTTP/1.0\r\n\r\n";
    CHECK(parse(s2, &r) == 0);
    CHECK(r.port == 80);
    n = http_proxy_rewrite_request(s2, &r, out, sizeof(out));
    out[n > 0 ? n : 0] = '\0';
    CHECK(strcmp(out, "GET / HTTP/1.0\r\nHost: example.com\r\nConnection: close\r\n\r\n") == 0);

    /* 쿼리만 있음 */
    const char *s3 = "GET http://example.com?q=1 HTTP/1.1\r\nHost: example.com\r\n\r\n";
    CHECK(parse(s3, &r) == 0);
    n = http_proxy_rewrite_request(s3, &r, out, sizeof(out));
    out[n > 0 ? n : 0] = '\0';
    CHECK(strncmp(out, "GET /?q=1 HTTP/1.1\r\n", 20) == 0);

    /* origin-form / https absolute-form / userinfo 는 거절 */
    CHECK(parse("GET / HTTP/1.1\r\nHost: x\r\n\r\n", &r) == -1);
    CHECK(parse("GET https://example.com/ HTTP/1.1\r\n\r\n", &r) == -1);
    CHECK(parse("GET http://u:p@example.com/ HTTP/1.1\r\n\r\n", &r) == -1);
}

static void test_basic_auth(void) {
    http_proxy_req_t r;
    /* "user:pa:ss" = dXNlcjpwYTpzcw== */
    const char *s = "CONNECT h:1 HTTP/1.1\r\nproxy-authorization:   basic dXNlcjpwYTpzcw==  \r\n\r\n";
    CHECK(parse(s, &r) == 0);
    CHECK(r.auth_len == strlen("dXNlcjpwYTpzcw=="));
    CHECK(http_proxy_check_basic_auth(s + r.auth_off, r.auth_len, "user", "pa:ss") == 1);
    CHECK(http_proxy_check_basic_auth(s + r.auth_off, r.auth_len, "user", "pa:sx") == 0);
    CHECK(http_proxy_check_basic_auth(s + r.auth_off, r.auth_len, "use", "rpa:ss") == 0);
    CHECK(http_proxy_check_basic_auth("", 0, "user", "pass") == 0);
    CHECK(http_proxy_check_basic_auth("!!!!", 4, "user", "pass") == 0);
}

int main(void) {
    test_looks_like_http();
    test_connect();
    test_incomplete_and_oversize();
    test_absolute_form_and_rewrite();
    test_basic_auth();
    if (g_failures) {
        fprintf(stderr, "http_proxy_test: %d failure(s)\n", g_failures);
        return 1;
    }
    printf("http_proxy_test: ALL PASS\n");
    return 0;
}
