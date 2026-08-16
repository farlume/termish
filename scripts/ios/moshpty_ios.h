#ifndef MOSHPTS_IOS_H
#define MOSHPTS_IOS_H

#include <stddef.h>
#include <sys/types.h>

/* 创建 PTY，fds[0]=master, fds[1]=slave。成功返回 0。 */
int mssh_openpty(int fds[2], int rows, int cols);

/* posix_spawn：子进程 stdin/stdout/stderr 重定向到 slave。成功返回 pid，失败返回 -1。 */
pid_t mssh_spawn(const char *path, char *const argv[], char *const envp[],
                 int master, int slave);

/* 更新 PTY 窗口尺寸（mosh 收到 SIGWINCH 后重新查询）。 */
int mssh_resize(int master, int rows, int cols);

ssize_t mssh_read(int master, void *buf, size_t len);
ssize_t mssh_write(int master, const void *buf, size_t len);
void mssh_close(int master, int slave);

#endif

/* zlib 压缩包装（KMP mosh 的 SSP 分片压缩用；内部调 libz 的 compress2/uncompress）。
 * destCap 为目标缓冲容量；返回实际长度，失败返回 -1。 */
int mssh_zlib_compress(const void *src, int srcLen, void *dst, int dstCap);
int mssh_zlib_uncompress(const void *src, int srcLen, void *dst, int dstCap);
