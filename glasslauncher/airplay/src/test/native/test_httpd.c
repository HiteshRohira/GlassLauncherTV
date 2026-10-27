/* Host stress tests for UxPlay's single-threaded httpd with patches
 * 0011-0014: a stalled client must not block others, idle connections are
 * reaped (HTTPD_IDLE_TIMEOUT=2 here), oversized input is dropped and stop
 * finishes in bounded time. Loopback only. */
#include <arpa/inet.h>
#include <errno.h>
#include <signal.h>
#include <netinet/in.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <time.h>
#include <unistd.h>

#ifndef MSG_NOSIGNAL
#define MSG_NOSIGNAL 0
#endif

#include "httpd.h"
#include "logger.h"

static int failures;
#define CHECK(cond, ...) do { if (!(cond)) { failures++; fprintf(stderr, "FAIL %s:%d: ", __FILE__, __LINE__); \
    fprintf(stderr, __VA_ARGS__); fputc('\n', stderr); } } while (0)

static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;
static int live_connections, requests;
static httpd_t *server_under_test;
static char last_protocol[16];

static void *conn_init(void *opaque, unsigned char *l, int ll, unsigned char *r, int rl, unsigned int z) {
    pthread_mutex_lock(&lock); live_connections++; pthread_mutex_unlock(&lock);
    return calloc(1,1);
}
static void conn_request(void *ptr, http_request_t *request, http_response_t **response) {
    pthread_mutex_lock(&lock); requests++;
    const char *protocol = http_request_get_protocol(request);
    snprintf(last_protocol, sizeof(last_protocol), "%s", protocol ? protocol : "(null)");
    pthread_mutex_unlock(&lock);
    *response = http_response_create();
    http_response_init(*response, "HTTP/1.1", 200, "OK");
    const char *url=http_request_get_url(request);
    if (!strcmp(url,"/upgrade-large")) {
        httpd_set_connection_type(server_under_test,ptr,CONNECTION_TYPE_PTTH);
        int fd=httpd_get_connection_socket(server_under_test,ptr),small=4096;
        setsockopt(fd,SOL_SOCKET,SO_SNDBUF,&small,sizeof(small));
        size_t size=2*1024*1024;char *body=calloc(1,size);
        http_response_finish(*response,body,size);free(body);
    } else if (!strcmp(url,"/enqueue")) {
        http_response_t *event=http_response_create();
        http_response_reverse_request_init(event,"POST","/event","HTTP/1.1");
        http_response_finish(event,"event",5);
        if(httpd_queue_by_type(server_under_test,CONNECTION_TYPE_PTTH,event)<0) {
            http_response_destroy(event);
            http_response_init(*response,"HTTP/1.1",500,"Queue failed");
        }
        http_response_finish(*response,"ok",2);
    } else if (!strcmp(url, "/large")) {
        size_t size = 8 * 1024 * 1024;
        char *body = calloc(1,size);
        http_response_finish(*response, body, size);
        free(body);
    } else http_response_finish(*response, "ok", 2);
}
static void conn_destroy(void *ptr) {
    pthread_mutex_lock(&lock); live_connections--; pthread_mutex_unlock(&lock);
    free(ptr);
}
static int live(void) { pthread_mutex_lock(&lock); int n = live_connections; pthread_mutex_unlock(&lock); return n; }

static double now_s(void) { struct timespec t; clock_gettime(CLOCK_MONOTONIC, &t); return t.tv_sec + t.tv_nsec / 1e9; }

static int dial(unsigned short port) {
    int fd = socket(AF_INET, SOCK_STREAM, 0);
    struct sockaddr_in a = { .sin_family = AF_INET, .sin_port = htons(port) };
    inet_pton(AF_INET, "127.0.0.1", &a.sin_addr);
    if (connect(fd, (struct sockaddr *) &a, sizeof(a)) != 0) { close(fd); return -1; }
    struct timeval tv = { .tv_sec = 5 };
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
    return fd;
}

/* Sends a complete request and returns 1 if a 200 response arrives. */
static int request_ok(unsigned short port) {
    int fd = dial(port);
    if (fd < 0) return 0;
    const char *req = "GET /info HTTP/1.1\r\nContent-Length: 0\r\n\r\n";
    send(fd, req, strlen(req), 0);
    char buf[256] = { 0 };
    ssize_t n = recv(fd, buf, sizeof(buf) - 1, 0);
    close(fd);
    return n > 0 && strstr(buf, " 200 ") != NULL;
}

/* Reads until the server closes the socket; returns 1 on close, 0 on timeout. */
static int closed_by_server(int fd) {
    char buf[4096];
    for (;;) {
        ssize_t n = recv(fd, buf, sizeof(buf), 0);
        if (n == 0) return 1;
        if (n < 0) return errno == ECONNRESET;
    }
}

static void log_cb(void *cls, int level, const char *msg) { if (level <= LOGGER_ERR) fprintf(stderr, "[uxplay] %.*s\n", (int) strcspn(msg, "\n"), msg); }

int main(void) {
    signal(SIGPIPE, SIG_IGN);
    logger_t *logger = logger_init();
    logger_set_level(logger, LOGGER_DEBUG);
    logger_set_callback(logger, log_cb, NULL);
    httpd_callbacks_t cbs = { .opaque = NULL, .conn_init = conn_init, .conn_request = conn_request, .conn_destroy = conn_destroy };
    httpd_t *httpd = httpd_init(logger, &cbs, 0);
    server_under_test=httpd;
    unsigned short port = 0;
    CHECK(httpd_start(httpd, &port) == 1 && port, "httpd_start (port %u)", port);

    /* 0. The request protocol is parsed from the request line. */
    CHECK(request_ok(port), "plain request failed");
    CHECK(!strcmp(last_protocol, "HTTP/1.1"), "protocol: [%s]", last_protocol);
    int rtsp = dial(port);
    const char *opt = "OPTIONS * RTSP/1.0\r\nCSeq: 1\r\n\r\n";
    send(rtsp, opt, strlen(opt), 0);
    char rbuf[256];
    recv(rtsp, rbuf, sizeof(rbuf), 0);
    close(rtsp);
    CHECK(!strcmp(last_protocol, "RTSP/1.0"), "RTSP protocol: [%s]", last_protocol);

    /* 1. A client that sends fewer than 8 bytes and stalls must not block others. */
    int stalled = dial(port);
    send(stalled, "GE", 2, 0);
    usleep(100000);
    double t = now_s();
    CHECK(request_ok(port), "request behind a stalled client was not answered");
    CHECK(now_s() - t < 1.5, "request behind a stalled client took %.2fs", now_s() - t);

    /* 2. Idle connections are reaped; with all 12 slots idle, new clients get in again. */
    int idle[12];
    int opened = 0;
    for (int i = 0; i < 11; i++) if ((idle[i] = dial(port)) >= 0) opened++;   /* + the stalled one = 12 */
    usleep(300000);
    CHECK(live() == 12, "expected 12 live connections, got %d", live());
    sleep(4);
    CHECK(live() == 0, "idle connections not reaped: %d live", live());
    CHECK(request_ok(port), "server did not accept after reaping");
    for (int i = 0; i < opened; i++) close(idle[i]);
    close(stalled);

    /* 3. Oversized body is rejected: connection dropped, not buffered without bound. */
    int big = dial(port);
    const char *hdr = "POST /x HTTP/1.1\r\nContent-Length: 104857600\r\n\r\n";
    send(big, hdr, strlen(hdr), 0);
    static char chunk[65536];
    memset(chunk, 'a', sizeof(chunk));
    size_t sent = 0;
    while (sent < (size_t) 20 * 1024 * 1024) {
        ssize_t n = send(big, chunk, sizeof(chunk), MSG_NOSIGNAL);
        if (n <= 0) break;
        sent += (size_t) n;
    }
    CHECK(sent < (size_t) 20 * 1024 * 1024 || closed_by_server(big), "100 MB body was not rejected");
    close(big);

    /* 4. Header flood and a giant URL are rejected. */
    int flood = dial(port);
    send(flood, "GET / HTTP/1.1\r\n", 16, 0);
    for (int i = 0; i < 400; i++) { char h[64]; int l = snprintf(h, sizeof(h), "X-H%d: v\r\n", i); send(flood, h, l, MSG_NOSIGNAL); }
    send(flood, "\r\n", 2, MSG_NOSIGNAL);
    CHECK(closed_by_server(flood), "400-header request was not rejected");
    close(flood);
    int url = dial(port);
    send(url, "GET /", 5, 0);
    memset(chunk, 'u', 16384);
    send(url, chunk, 16384, MSG_NOSIGNAL);
    CHECK(closed_by_server(url), "16 KB URL was not rejected");
    close(url);
    CHECK(request_ok(port), "server unhealthy after malformed input");

    /* 5. Many short connections in a row (stress). */
    int ok = 0;
    for (int i = 0; i < 300; i++) ok += request_ok(port);
    CHECK(ok == 300, "only %d/300 sequential requests answered", ok);

    /* A full reverse chunk must never need a byte beyond the receive buffer. */
    int reverse = dial(port);
    send(reverse, "HTTP/1.1", 8, 0); usleep(30000);
    char full[1024]; memset(full, 'x', sizeof(full));
    send(reverse, full, sizeof(full), 0); usleep(30000); close(reverse);
    CHECK(request_ok(port), "reverse logging damaged server");

    /* A nonreading response peer must not monopolize the HTTP worker. */
    int nonreader = dial(port), window=1024;
    setsockopt(nonreader,SOL_SOCKET,SO_RCVBUF,&window,sizeof(window));
    const char *large="GET /large HTTP/1.1\r\n\r\n";
    send(nonreader,large,strlen(large),0); usleep(100000);
    CHECK(request_ok(port), "nonreading peer blocked another client");

    /* Reverse-channel events must follow all bytes of a pending response. */
    int ordered=dial(port);
    const char *upgrade="GET /upgrade-large HTTP/1.1\r\n\r\n";
    send(ordered,upgrade,strlen(upgrade),0);usleep(100000);
    int enqueue=dial(port);
    const char *eq="GET /enqueue HTTP/1.1\r\n\r\n";
    send(enqueue,eq,strlen(eq),0);
    char replybuf[256];int got=recv(enqueue,replybuf,sizeof(replybuf)-1,0);
    if(got>0)replybuf[got]=0;
    CHECK(got>0&&strstr(replybuf," 200 "),"reverse request not queued");close(enqueue);
    char header[1024]={0};size_t h=0;
    while(h+1<sizeof(header)){
        if(recv(ordered,header+h,1,0)!=1)break;
        h++;header[h]=0;if(h>=4&&!memcmp(header+h-4,"\r\n\r\n",4))break;
    }
    CHECK(strstr(header," 200 "),"initial reverse response missing");
    size_t remaining=2*1024*1024;int corrupt=0;
    while(remaining){
        size_t want=remaining<sizeof(chunk)?remaining:sizeof(chunk);
        ssize_t count=recv(ordered,chunk,want,0);if(count<=0)break;
        for(ssize_t j=0;j<count;j++)if(chunk[j])corrupt=1;
        remaining-=count;
    }
    CHECK(!remaining&&!corrupt,"reverse event interleaved with response body");
    got=recv(ordered,replybuf,sizeof(replybuf)-1,0);
    if(got>0)replybuf[got]=0;
    CHECK(got>0&&strstr(replybuf,"POST /event HTTP/1.1"),"queued event did not follow response");
    close(ordered);

    /* 6. Stop finishes promptly even with a stalled client attached. */
    int last = dial(port);
    send(last, "G", 1, 0);
    usleep(100000);
    t = now_s();
    httpd_stop(httpd);
    CHECK(now_s() - t < 3.0, "httpd_stop took %.2fs", now_s() - t);
    close(last);
    close(nonreader);
    httpd_destroy(httpd);
    logger_destroy(logger);

    if (failures) { fprintf(stderr, "test_httpd: %d failure(s)\n", failures); return 1; }
    printf("test_httpd: ok\n");
    return 0;
}
