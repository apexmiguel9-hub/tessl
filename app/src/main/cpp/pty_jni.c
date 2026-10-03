/*
 * JNI glue for the tessl pty core. Deliberately thin: every exported function
 * is short, non-blocking (except the explicit wait), and free of JNI callbacks
 * into Java. The read loop lives on the Kotlin side so that thread attachment
 * is handled once, in one place.
 *
 * Handles are the address of a heap-allocated tessl_pty, passed as a jlong.
 */

#include <jni.h>
#include <errno.h>
#include <signal.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <unistd.h>

#include "pty_core.h"

static tessl_pty *handle_of(jlong h) {
    return (tessl_pty *) (intptr_t) h;
}

/* Build a NULL-terminated char*[] from a Java String[]. Caller frees with
 * free_argv(). */
static char **build_argv(JNIEnv *env, jobjectArray arr, jsize n) {
    jclass string_cls = (*env)->FindClass(env, "java/lang/String");
    jmethodID getBytes = NULL;
    if (string_cls) getBytes = (*env)->GetMethodID(env, string_cls, "getBytes", "()[B");
    if (!string_cls || !getBytes) return NULL;

    char **argv = calloc((size_t) n + 1, sizeof(char *));
    if (!argv) return NULL;

    for (jsize i = 0; i < n; i++) {
        jobject s = (*env)->GetObjectArrayElement(env, arr, i);
        if (!s) goto fail;
        jbyteArray bytes = (jbyteArray) (*env)->CallObjectMethod(env, s, getBytes);
        (*env)->DeleteLocalRef(env, s);
        if (!bytes) goto fail;
        jsize len = (*env)->GetArrayLength(env, bytes);
        char *buf = malloc((size_t) len + 1);
        if (!buf) { (*env)->DeleteLocalRef(env, bytes); goto fail; }
        (*env)->GetByteArrayRegion(env, bytes, 0, len, (jbyte *) buf);
        (*env)->DeleteLocalRef(env, bytes);
        buf[len] = 0;
        argv[i] = buf;
    }
    argv[n] = NULL;
    return argv;

fail:
    for (jsize i = 0; i < n; i++) free(argv[i]);
    free(argv);
    return NULL;
}

static void free_argv(char **argv) {
    if (!argv) return;
    for (size_t i = 0; argv[i]; i++) free(argv[i]);
    free(argv);
}

JNIEXPORT jlong JNICALL
Java_io_github_apexmiguel9_termux_pty_PtyNative_nativeSpawn(JNIEnv *env, jclass cls,
                                                             jobjectArray argv,
                                                             jobjectArray envp,
                                                             jint rows, jint cols) {
    (void) cls;
    jsize n = (*env)->GetArrayLength(env, argv);
    char **c_argv = build_argv(env, argv, n);
    if (!c_argv) return 0;

    char **c_envp = NULL;
    if (envp) {
        jsize m = (*env)->GetArrayLength(env, envp);
        c_envp = build_argv(env, envp, m);
        if (!c_envp) { free_argv(c_argv); return 0; }
    }

    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_row = (unsigned short) rows;
    ws.ws_col = (unsigned short) cols;

    tessl_pty *p = calloc(1, sizeof(tessl_pty));
    if (!p) { free_argv(c_argv); free_argv(c_envp); return 0; }

    if (tessl_pty_spawn(p, (const char *const *) c_argv,
                        (const char *const *) c_envp, &ws) != 0) {
        free(p);
        free_argv(c_argv);
        free_argv(c_envp);
        (*env)->ThrowNew(env, "java/lang/RuntimeException", strerror(errno));
        return 0;
    }

    free_argv(c_argv);
    free_argv(c_envp);
    return (jlong) (intptr_t) p;
}

/* Returns a byte[] of whatever is available within timeoutMs, or null on
 * timeout. Throws on hard error. */
JNIEXPORT jbyteArray JNICALL
Java_io_github_apexmiguel9_termux_pty_PtyNative_nativeRead(JNIEnv *env, jclass cls,
                                                            jlong h, jint timeout_ms) {
    (void) cls;
    tessl_pty *p = handle_of(h);
    if (!p) return NULL;

    if (timeout_ms > 0) {
        int rc = tessl_pty_poll_readable(p, timeout_ms);
        if (rc == 0) return NULL;      /* timeout, caller loops */
        if (rc < 0) {
            (*env)->ThrowNew(env, "java/lang/RuntimeException", strerror(errno));
            return NULL;
        }
    }

    unsigned char stackbuf[4096];
    ssize_t n = tessl_pty_read(p, stackbuf, sizeof(stackbuf));
    if (n < 0) {
        if (errno == EIO) return NULL; /* child closed the slave: normal EOF */
        (*env)->ThrowNew(env, "java/lang/RuntimeException", strerror(errno));
        return NULL;
    }
    if (n == 0) return NULL;

    jbyteArray out = (*env)->NewByteArray(env, (jsize) n);
    if (!out) return NULL;
    (*env)->SetByteArrayRegion(env, out, 0, (jsize) n, (const jbyte *) stackbuf);
    return out;
}

JNIEXPORT jint JNICALL
Java_io_github_apexmiguel9_termux_pty_PtyNative_nativeWrite(JNIEnv *env, jclass cls,
                                                             jlong h, jbyteArray data) {
    (void) cls;
    tessl_pty *p = handle_of(h);
    if (!p) return -1;
    jsize n = (*env)->GetArrayLength(env, data);
    if (n <= 0) return 0;

    jbyte *buf = malloc((size_t) n);
    if (!buf) return -1;
    (*env)->GetByteArrayRegion(env, data, 0, n, buf);
    ssize_t w = tessl_pty_write(p, buf, (size_t) n);
    free(buf);
    return (jint) w;
}

JNIEXPORT void JNICALL
Java_io_github_apexmiguel9_termux_pty_PtyNative_nativeResize(JNIEnv *env, jclass cls,
                                                              jlong h, jint rows, jint cols) {
    (void) env; (void) cls;
    tessl_pty *p = handle_of(h);
    if (p) tessl_pty_resize(p, rows, cols);
}

JNIEXPORT jint JNICALL
Java_io_github_apexmiguel9_termux_pty_PtyNative_nativeWait(JNIEnv *env, jclass cls,
                                                            jlong h) {
    (void) cls;
    tessl_pty *p = handle_of(h);
    if (!p) return -1;
    int code = -1;
    if (tessl_pty_wait(p, &code) != 0) {
        (*env)->ThrowNew(env, "java/lang/RuntimeException", strerror(errno));
        return -1;
    }
    return code;
}

/* errno from the child if exec failed, else 0. */
JNIEXPORT jint JNICALL
Java_io_github_apexmiguel9_termux_pty_PtyNative_nativeExecErrno(JNIEnv *env, jclass cls, jlong h) {
    (void) env; (void) cls;
    tessl_pty *p = handle_of(h);
    return p ? p->exec_errno : 0;
}

JNIEXPORT jint JNICALL
Java_io_github_apexmiguel9_termux_pty_PtyNative_nativePid(JNIEnv *env, jclass cls, jlong h) {
    (void) env; (void) cls;
    tessl_pty *p = handle_of(h);
    return p ? (jint) p->pid : -1;
}

JNIEXPORT jboolean JNICALL
Java_io_github_apexmiguel9_termux_pty_PtyNative_nativeAlive(JNIEnv *env, jclass cls, jlong h) {
    (void) env; (void) cls;
    tessl_pty *p = handle_of(h);
    if (!p || p->pid <= 0) return JNI_FALSE;
    /* kill(0) probes without signalling. */
    return (kill(p->pid, 0) == 0) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_io_github_apexmiguel9_termux_pty_PtyNative_nativeKill(JNIEnv *env, jclass cls, jlong h) {
    (void) env; (void) cls;
    tessl_pty *p = handle_of(h);
    if (p) tessl_pty_kill(p);
}

JNIEXPORT void JNICALL
Java_io_github_apexmiguel9_termux_pty_PtyNative_nativeClose(JNIEnv *env, jclass cls, jlong h) {
    (void) env; (void) cls;
    tessl_pty *p = handle_of(h);
    if (!p) return;
    tessl_pty_close(p);
    free(p);
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void) vm; (void) reserved;
    return JNI_VERSION_1_6;
}
