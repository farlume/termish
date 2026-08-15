#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <pty.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/ioctl.h>

/*
 * mosh 集成用的 PTY 辅助：Android 的 java ProcessBuilder 无法直接创建 PTY，
 * 这里用 bionic 的 openpty() 创建一对 master/slave，把 fd 和 slave 路径
 * 交回 Java：mosh-client 的 stdin/stdout 重定向到 slave，app 通过 master 读写。
 */
JNIEXPORT jstring JNICALL
Java_dev_mssh_ssh_MoshPty_openPty(JNIEnv *env, jclass clazz,
                                   jintArray fds, jint rows, jint cols) {
    int master = -1, slave = -1;
    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_row = (unsigned short)rows;
    ws.ws_col = (unsigned short)cols;

    if (openpty(&master, &slave, NULL, NULL, &ws) != 0) {
        return NULL;
    }
    char path[128];
    if (ttyname_r(slave, path, sizeof(path)) != 0) {
        close(master);
        close(slave);
        return NULL;
    }
    jint fds_arr[2] = { master, slave };
    (*env)->SetIntArrayRegion(env, fds, 0, 2, fds_arr);
    return (*env)->NewStringUTF(env, path);
}

JNIEXPORT jint JNICALL
Java_dev_mssh_ssh_MoshPty_resizePty(JNIEnv *env, jclass clazz,
                                     jint masterFd, jint rows, jint cols) {
    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_row = (unsigned short)rows;
    ws.ws_col = (unsigned short)cols;
    return ioctl(masterFd, TIOCSWINSZ, &ws);
}

JNIEXPORT jint JNICALL
Java_dev_mssh_ssh_MoshPty_readPty(JNIEnv *env, jclass clazz,
                                   jint masterFd, jbyteArray buf) {
    jsize len = (*env)->GetArrayLength(env, buf);
    jbyte *data = (*env)->GetByteArrayElements(env, buf, NULL);
    if (data == NULL) return -1;
    ssize_t n = read(masterFd, data, (size_t)len);
    (*env)->ReleaseByteArrayElements(env, buf, data, 0);
    return (jint)n;
}

JNIEXPORT jint JNICALL
Java_dev_mssh_ssh_MoshPty_writePty(JNIEnv *env, jclass clazz,
                                    jint masterFd, jbyteArray buf, jint len) {
    jbyte *data = (*env)->GetByteArrayElements(env, buf, NULL);
    if (data == NULL) return -1;
    ssize_t n = write(masterFd, data, (size_t)len);
    (*env)->ReleaseByteArrayElements(env, buf, data, JNI_ABORT);
    return (jint)n;
}

JNIEXPORT void JNICALL
Java_dev_mssh_ssh_MoshPty_closePty(JNIEnv *env, jclass clazz,
                                    jint masterFd, jint slaveFd) {
    if (masterFd >= 0) close(masterFd);
    if (slaveFd >= 0) close(slaveFd);
}
