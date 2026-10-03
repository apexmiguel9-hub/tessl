#include "pty_core.h"

#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <poll.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/wait.h>

#ifdef __linux__
#include <sys/prctl.h>
#endif

#ifndef TIOCSCTTY
/* asm-generic value for arm64/aarch64. bionic does not always surface this
 * through <sys/ioctl.h>, so we pin it. Verified against the Linux uapi
 * headers: _IO('T', 0xE) == 0x540E. */
#define TIOCSCTTY 0x540E
#endif

static int set_winsize(int fd, int rows, int cols) {
    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_row = (unsigned short) rows;
    ws.ws_col = (unsigned short) cols;
    return ioctl(fd, TIOCSWINSZ, &ws);
}

/* Expand login_tty(3), which bionic does not provide. */
static int child_login_tty(int slave_fd) {
    if (setsid() == (pid_t) -1) return -1;
    if (ioctl(slave_fd, TIOCSCTTY, 0) == -1) return -1;
    if (dup2(slave_fd, STDIN_FILENO) == -1) return -1;
    if (dup2(slave_fd, STDOUT_FILENO) == -1) return -1;
    if (dup2(slave_fd, STDERR_FILENO) == -1) return -1;
    if (slave_fd > STDERR_FILENO) close(slave_fd);
    return 0;
}

int tessl_pty_spawn(tessl_pty *p, const char *const argv[],
                    const char *const envp[],
                    const struct winsize *ws) {
    int master = -1, slave = -1;
    pid_t pid;
    char name[PATH_MAX];

    if (!p || !argv || !argv[0]) {
        errno = EINVAL;
        return -1;
    }
    memset(p, 0, sizeof(*p));
    p->master = -1;
    p->pid = -1;

    /* posix_openpt(3): allocates a master and unlocks it. grantpt/unlockpt
     * are required by POSIX and are no-ops on bionic, but glibc needs them,
     * and we build the same object file for both. */
    master = posix_openpt(O_RDWR | O_NOCTTY);
    if (master == -1) return -1;
    if (grantpt(master) == -1) goto fail;
    if (unlockpt(master) == -1) goto fail;
    if (ptsname_r(master, name, sizeof(name)) != 0) goto fail;

    slave = open(name, O_RDWR | O_NOCTTY);
    if (slave == -1) goto fail;

    if (ws && set_winsize(slave, ws->ws_row, ws->ws_col) == -1) goto fail;

    pid = fork();
    if (pid == (pid_t) -1) goto fail;

    if (pid == 0) {
        /* ---- child ---- */
        close(master);
#ifdef __linux__
        /* Android will SIGKILL the whole process group when the app is
         * killed or evicted. Without PDEATHSIG every shell we ever started
         * becomes an orphan holding an fd on the pty master. */
        prctl(PR_SET_PDEATHSIG, SIGHUP, 0, 0, 0);
#endif
        if (child_login_tty(slave) == -1) _exit(127);
        if (envp) execve(argv[0], (char *const *) argv, (char *const *) envp);
        else execv(argv[0], (char *const *) argv);
        /* Only reached on exec failure. 127 is the conventional "not found". */
        _exit(127);
    }

    /* ---- parent ---- */
    close(slave);
    p->master = master;
    p->pid = pid;
    snprintf(p->slave_name, sizeof(p->slave_name), "%s", name);
    return 0;

fail: {
        int saved = errno;
        if (slave != -1) close(slave);
        if (master != -1) close(master);
        errno = saved;
        return -1;
    }
}

int tessl_pty_resize(tessl_pty *p, int rows, int cols) {
    if (!p || p->master == -1) {
        errno = EBADF;
        return -1;
    }
    /* The kernel propagates TIOCSWINSZ + SIGWINCH to the foreground
     * process group, so ncurses apps resize without us signalling anything. */
    return set_winsize(p->master, rows, cols);
}

ssize_t tessl_pty_read(tessl_pty *p, void *buf, size_t n) {
    ssize_t r;
    if (!p || p->master == -1) {
        errno = EBADF;
        return -1;
    }
    do {
        r = read(p->master, buf, n);
    } while (r == -1 && errno == EINTR);
    return r;
}

ssize_t tessl_pty_write(tessl_pty *p, const void *buf, size_t n) {
    size_t off = 0;
    if (!p || p->master == -1) {
        errno = EBADF;
        return -1;
    }
    while (off < n) {
        ssize_t w = write(p->master, (const char *) buf + off, n - off);
        if (w == -1) {
            if (errno == EINTR) continue;
            return off > 0 ? (ssize_t) off : -1;
        }
        if (w == 0) break;
        off += (size_t) w;
    }
    return (ssize_t) off;
}

int tessl_pty_poll_readable(tessl_pty *p, int timeout_ms) {
    struct pollfd fds[1];
    int rc;
    if (!p || p->master == -1) {
        errno = EBADF;
        return -1;
    }
    fds[0].fd = p->master;
    fds[0].events = POLLIN;
    fds[0].revents = 0;
    do {
        rc = poll(fds, 1, timeout_ms);
    } while (rc == -1 && errno == EINTR);
    if (rc <= 0) return rc;
    return (fds[0].revents & (POLLIN | POLLHUP | POLLERR)) ? 1 : 0;
}

int tessl_pty_wait(tessl_pty *p, int *exit_code) {
    int status = 0;
    pid_t r;
    if (!p || p->pid <= 0) {
        errno = EBADF;
        return -1;
    }
    do {
        r = waitpid(p->pid, &status, 0);
    } while (r == -1 && errno == EINTR);
    if (r == -1) return -1;

    p->pid = -1;
    if (exit_code) {
        if (WIFEXITED(status)) *exit_code = WEXITSTATUS(status);
        else if (WIFSIGNALED(status)) *exit_code = 128 + WTERMSIG(status);
        else *exit_code = -1;
    }
    return 0;
}

void tessl_pty_kill(tessl_pty *p) {
    int code;
    if (!p) return;
    if (p->pid > 0) {
        /* The child called setsid(), so its pgid == its pid. */
        kill(-p->pid, SIGHUP);
        kill(-p->pid, SIGKILL);
    }
    if (p->master != -1) {
        close(p->master);
        p->master = -1;
    }
    if (p->pid > 0) {
        tessl_pty_wait(p, &code);
    }
}

void tessl_pty_close(tessl_pty *p) {
    if (!p) return;
    if (p->master != -1) {
        close(p->master);
        p->master = -1;
    }
}
