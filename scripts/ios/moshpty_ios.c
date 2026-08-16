/* iOS mosh 集成：PTY + posix_spawn 桥接（Kotlin/Native 无 openpty/posix_spawn 绑定）。 */
#include <stddef.h>
#include <string.h>
#include <sys/ioctl.h>
#include <termios.h>
#include <util.h>
#include <spawn.h>
#include <unistd.h>

int mssh_openpty(int fds[2], int rows, int cols) {
    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_row = (unsigned short)rows;
    ws.ws_col = (unsigned short)cols;
    int m = -1, s = -1;
    if (openpty(&m, &s, NULL, NULL, &ws) != 0) return -1;
    fds[0] = m;
    fds[1] = s;
    return 0;
}

pid_t mssh_spawn(const char *path, char *const argv[], char *const envp[],
                 int master, int slave) {
    posix_spawn_file_actions_t fa;
    pid_t pid = -1;
    if (posix_spawn_file_actions_init(&fa) != 0) return -1;
    posix_spawn_file_actions_adddup2(&fa, slave, 0);
    posix_spawn_file_actions_adddup2(&fa, slave, 1);
    posix_spawn_file_actions_adddup2(&fa, slave, 2);
    posix_spawn_file_actions_addclose(&fa, master);
    posix_spawn_file_actions_addclose(&fa, slave);
    int rc = posix_spawn(&pid, path, &fa, NULL, argv, envp);
    posix_spawn_file_actions_destroy(&fa);
    return rc == 0 ? pid : -1;
}

int mssh_resize(int master, int rows, int cols) {
    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_row = (unsigned short)rows;
    ws.ws_col = (unsigned short)cols;
    return ioctl(master, TIOCSWINSZ, &ws);
}

ssize_t mssh_read(int master, void *buf, size_t len) {
    return read(master, buf, len);
}

ssize_t mssh_write(int master, const void *buf, size_t len) {
    return write(master, buf, len);
}

void mssh_close(int master, int slave) {
    if (master >= 0) close(master);
    if (slave >= 0) close(slave);
}

#include <zlib.h>

int mssh_zlib_compress(const void *src, int srcLen, void *dst, int dstCap) {
    uLongf destLen = (uLongf)dstCap;
    if (compress2((Bytef *)dst, &destLen, (const Bytef *)src, (uLong)srcLen, Z_DEFAULT_COMPRESSION) != Z_OK)
        return -1;
    return (int)destLen;
}

int mssh_zlib_uncompress(const void *src, int srcLen, void *dst, int dstCap) {
    uLongf destLen = (uLongf)dstCap;
    if (uncompress((Bytef *)dst, &destLen, (const Bytef *)src, (uLong)srcLen) != Z_OK)
        return -1;
    return (int)destLen;
}
