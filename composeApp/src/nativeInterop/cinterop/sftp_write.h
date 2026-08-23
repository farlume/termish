#ifndef TERMISH_SFTP_WRITE_H
#define TERMISH_SFTP_WRITE_H

#include <libssh2_sftp.h>

/* 字节版写入：libssh2 的 const char* 会被 cinterop 映射为 String，
 * 这里用 unsigned char* 让 cinterop 生成指针参数，避免二进制经 UTF-8 损坏。 */
static inline long termish_sftp_write(LIBSSH2_SFTP_HANDLE *handle,
                                      const unsigned char *buffer,
                                      size_t count) {
    return (long)libssh2_sftp_write(handle, (const char *)buffer, count);
}

static inline long termish_channel_write(LIBSSH2_CHANNEL *channel,
                                         const unsigned char *buffer,
                                         size_t count) {
    return (long)libssh2_channel_write_ex(channel, 0, (const char *)buffer, count);
}

#endif
