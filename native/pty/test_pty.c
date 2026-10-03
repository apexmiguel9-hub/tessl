/*
 * Test harness for tessl pty core. Builds and runs on the host (glibc or
 * bionic) with no JNI, so the pty layer is verifiable before the Android
 * toolchain exists.
 *
 *   cc -Wall -Wextra -O2 -o test_pty test_pty.c pty_core.c
 */
#include "pty_core.h"

#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

static int failures = 0;
static const char *SH = NULL;

static void ok(const char *name, int cond, const char *detail) {
    printf("  [%s] %-34s %s\n", cond ? "PASS" : "FAIL", name, detail ? detail : "");
    if (!cond) failures++;
}

/* Read until the pty goes quiet, accumulating output. */
static void drain(tessl_pty *p, char *buf, size_t cap, int quiet_ms) {
    size_t off = 0;
    buf[0] = 0;
    for (;;) {
        int rc = tessl_pty_poll_readable(p, quiet_ms);
        if (rc != 1) break;
        ssize_t n = tessl_pty_read(p, buf + off, cap - 1 - off);
        if (n <= 0) break;
        off += (size_t) n;
        buf[off] = 0;
        if (off >= cap - 1) break;
    }
}

static void run_cmd(const char *name, const char *cmd, char *out, size_t cap) {
    tessl_pty p;
    const char *argv[] = {SH, "-c", cmd, NULL};
    int code = -1;
    if (tessl_pty_spawn(&p, argv, NULL, NULL, NULL) != 0) {
        snprintf(out, cap, "spawn failed: %s", strerror(errno));
        ok(name, 0, out);
        return;
    }
    drain(&p, out, cap, 250);
    tessl_pty_wait(&p, &code);
    tessl_pty_close(&p);
}

int main(int argc, char **argv) {
    SH = getenv("TESSL_TEST_SHELL");
    if (!SH) SH = (argc > 1) ? argv[1] : "/data/data/com.termux/files/usr/bin/bash";
    if (access(SH, X_OK) != 0) {
        fprintf(stderr, "shell not executable: %s\n", SH);
        return 2;
    }
    printf("pty core tests\n  shell: %s\n\n", SH);

    char out[4096];

    /* 1. basic output */
    run_cmd("stdout capture", "printf 'tessl-ok-9182'", out, sizeof(out));
    ok("stdout capture", strstr(out, "tessl-ok-9182") != NULL, out);

    /* 2. stdin is a tty -- this is the whole point of the pty */
    run_cmd("stdin is a tty", "test -t 0 && test -t 1 && test -t 2 && printf TTYOK", out, sizeof(out));
    ok("stdin/stdout/stderr are ttys", strstr(out, "TTYOK") != NULL, out);

    /* 3. terminal name comes from the pty */
    run_cmd("tty name", "tty", out, sizeof(out));
    ok("tty reports a pts device", strstr(out, "/dev/pts/") != NULL, out);

    /* 4. window size set at spawn time, read back by the child via stty */
    {
        tessl_pty p;
        const char *argv2[] = {SH, "-c", "stty size", NULL};
        struct winsize ws;
        memset(&ws, 0, sizeof(ws));
        ws.ws_row = 37; ws.ws_col = 111;
        if (tessl_pty_spawn(&p, argv2, NULL, &ws, NULL) != 0) {
            ok("winsize at spawn", 0, strerror(errno));
        } else {
            int code;
            drain(&p, out, sizeof(out), 250);
            tessl_pty_wait(&p, &code);
            tessl_pty_close(&p);
            ok("winsize at spawn", strstr(out, "37 111") != NULL, out);
        }
    }

    /* 5. resize mid-session reaches the child.
     * Sequential, no WINCH trap: bash defers traps until the foreground
     * command returns, so trapping around a sleep would never fire inside
     * our poll window. Two stty calls with a gap proves the propagation. */
    {
        tessl_pty p;
        const char *argv3[] = {SH, "-c", "stty size; sleep 1; stty size", NULL};
        struct winsize ws;
        memset(&ws, 0, sizeof(ws));
        ws.ws_row = 24; ws.ws_col = 80;
        if (tessl_pty_spawn(&p, argv3, NULL, &ws, NULL) != 0) {
            ok("resize propagates", 0, strerror(errno));
        } else {
            char first[1024] = {0}, second[1024] = {0};
            /* Generous quiet windows: cold bash start under proot can exceed
             * 150ms, and if we resize before the first stty runs, both reads
             * report the new size and the test proves nothing. */
            drain(&p, first, sizeof(first), 800);
            tessl_pty_resize(&p, 50, 132);
            drain(&p, second, sizeof(second), 1800);
            tessl_pty_kill(&p);
            char d[256];
            snprintf(d, sizeof(d), "before[%s] after[%s]", first, second);
            ok("resize propagates",
               strstr(first, "24 80") != NULL && strstr(second, "50 132") != NULL, d);
        }
    }

    /* 6. write to the child and read its reply */
    {
        tessl_pty p;
        const char *argv4[] = {SH, "-c", "read -r line; printf 'got=%s' \"$line\"", NULL};
        if (tessl_pty_spawn(&p, argv4, NULL, NULL, NULL) != 0) {
            ok("interactive write/read", 0, strerror(errno));
        } else {

            tessl_pty_write(&p, "ping-551\n", 9);
            drain(&p, out, sizeof(out), 400);
            tessl_pty_kill(&p);
            ok("interactive write/read", strstr(out, "got=ping-551") != NULL, out);
        }
    }

    /* 7. exit status propagation */
    {
        tessl_pty p;
        const char *argv5[] = {SH, "-c", "exit 42", NULL};
        int code = -1;
        if (tessl_pty_spawn(&p, argv5, NULL, NULL, NULL) != 0) {
            ok("exit code", 0, strerror(errno));
        } else {
            drain(&p, out, sizeof(out), 200);
            tessl_pty_wait(&p, &code);
            tessl_pty_close(&p);
            char d[64]; snprintf(d, sizeof(d), "got %d", code);
            ok("exit code", code == 42, d);
        }
    }

    /* 8. envp is applied (execve path, not execv) */
    {
        tessl_pty p;
        const char *env[] = {"TESSL_TEST_VAR=envpass-7734", "PATH=/system/bin", NULL};
        const char *argv6[] = {SH, "-c", "printf '%s' \"$TESSL_TEST_VAR\"", NULL};
        int code;
        if (tessl_pty_spawn(&p, argv6, env, NULL, NULL) != 0) {
            ok("envp applied", 0, strerror(errno));
        } else {
            drain(&p, out, sizeof(out), 250);
            tessl_pty_wait(&p, &code);
            tessl_pty_close(&p);
            ok("envp applied", strstr(out, "envpass-7734") != NULL, out);
        }
    }

    /* 9. exec failure surfaces as 127 rather than hanging */
    {
        tessl_pty p;
        const char *argv7[] = {"/nonexistent/definitely-not-here", NULL};
        int code = -1;
        if (tessl_pty_spawn(&p, argv7, NULL, NULL, NULL) != 0) {
            ok("exec failure -> 127", 0, strerror(errno));
        } else {
            drain(&p, out, sizeof(out), 250);
            tessl_pty_wait(&p, &code);
            tessl_pty_close(&p);
            char d[64]; snprintf(d, sizeof(d), "got %d", code);
            ok("exec failure -> 127", code == 127, d);
        }
    }

    /* 10. the child's cwd is the one we asked for */
    {
        tessl_pty p;
        const char *argv8[] = {SH, "-c", "pwd", NULL};
        int code;
        char here[1024] = {0};
        if (getcwd(here, sizeof(here)) == NULL) here[0] = 0;
        if (tessl_pty_spawn(&p, argv8, NULL, NULL, "/tmp") != 0) {
            ok("child cwd", 0, strerror(errno));
        } else {
            drain(&p, out, sizeof(out), 250);
            tessl_pty_wait(&p, &code);
            tessl_pty_close(&p);
            ok("child cwd", strstr(out, "/tmp") != NULL, out);
        }
    }

    printf("\n%s (%d failure%s)\n", failures ? "FAILED" : "ALL PASSED",
           failures, failures == 1 ? "" : "s");
    return failures ? 1 : 0;
}
