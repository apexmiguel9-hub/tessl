/*
 * tessl pty core -- POSIX pseudo-terminal, no JNI, no bionic specifics
 * beyond the two documented shims below.
 *
 * Why this exists instead of libc's forkpty():
 *
 *   bionic has no forkpty(). It also has no login_tty(). Termux had to
 *   write its own for the same reason (the termux/termux-pty repo is now
 *   404, so there is nothing to link against anyway).
 *
 *   The two shims we need are:
 *     1. TIOCSCTTY -- not exposed by <sys/ioctl.h> on all bionic targets,
 *        so we define the asm-generic arm64 value ourselves.
 *     2. login_tty(fd) -- absent from bionic, expanded inline in the child.
 */

#ifndef TESSL_PTY_CORE_H
#define TESSL_PTY_CORE_H

#include <sys/types.h>
#include <termios.h>
#include <sys/ioctl.h>
#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct {
    int master;          /* fd on our side */
    pid_t pid;
    char slave_name[128];
} tessl_pty;

/* Spawn argv[0] on a new pty. argv must be NULL-terminated.
 * envp may be NULL to inherit, or a NULL-terminated array of "K=V".
 * Returns 0 on success (tessl_pty filled in), -1 with errno set on failure. */
int tessl_pty_spawn(tessl_pty *p, const char *const argv[],
                    const char *const envp[],
                    const struct winsize *ws);

/* Signal a window-size change. Returns 0 or -1/errno. */
int tessl_pty_resize(tessl_pty *p, int rows, int cols);

/* Non-blocking read. Returns bytes read, 0 if nothing available, -1 on
 * error (EIO means the child closed the slave, which is normal). */
ssize_t tessl_pty_read(tessl_pty *p, void *buf, size_t n);

/* Blocking write. Handles partial writes and EINTR internally. */
ssize_t tessl_pty_write(tessl_pty *p, const void *buf, size_t n);

/* Wait for the child. Returns the exit status, or -1/errno. */
int tessl_pty_wait(tessl_pty *p, int *exit_code);

/* SIGHUP the child's process group, then reap. */
void tessl_pty_kill(tessl_pty *p);

void tessl_pty_close(tessl_pty *p);

/* Milliseconds until the pty is readable, or -1 on error. 0 == readable now. */
int tessl_pty_poll_readable(tessl_pty *p, int timeout_ms);

#ifdef __cplusplus
}
#endif

#endif /* TESSL_PTY_CORE_H */
