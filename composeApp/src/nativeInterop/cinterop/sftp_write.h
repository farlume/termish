#ifndef TERMISH_SFTP_WRITE_H
#define TERMISH_SFTP_WRITE_H

#include <libssh2_sftp.h>

/* 字节版 SFTP 写：libssh2_sftp_write 的 cinterop 映射只暴露 String 版本，
 * 这里用 unsigned char* 让 cinterop 生成指针参数，避免二进制经 UTF-8 损坏。 */
static inline long termish_sftp_write(LIBSSH2_SFTP_HANDLE *handle,
                                      const unsigned char *buffer,
                                      size_t count) {
    return (long)libssh2_sftp_write(handle, (const char *)buffer, count);
}

#endif
