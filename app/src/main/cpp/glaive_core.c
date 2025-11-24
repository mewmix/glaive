#include <jni.h>
#include <dirent.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/syscall.h>
#include <errno.h>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <ctype.h>
#include <stdint.h>
#include <arm_neon.h>
#include <android/log.h>

#define LOG_TAG "GLAIVE_C"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Kernel struct for getdents64
struct linux_dirent64 {
    unsigned long long d_ino;
    long long d_off;
    unsigned short d_reclen;
    unsigned char d_type;
    char d_name[];
};

typedef enum {
    TYPE_UNKNOWN = 0,
    TYPE_DIR = 1,
    TYPE_IMG = 2,
    TYPE_VID = 3,
    TYPE_APK = 4,
    TYPE_DOC = 5,
    TYPE_FILE = 6
} FileType;

static inline unsigned int hash_ext(const char *str) {
    unsigned int hash = 5381;
    int c;
    while ((c = *str++)) hash = ((hash << 5) + hash) + tolower(c);
    return hash;
}

static inline FileType get_type(const char *name, int d_type) {
    if (d_type == DT_DIR) return TYPE_DIR;

    const char *dot = strrchr(name, '.');
    if (!dot) return TYPE_UNKNOWN;
    dot++;

    switch (hash_ext(dot)) {
        case 193490416: /* png */
        case 193497021: /* jpg */
        case 2090499160: /* jpeg */ return TYPE_IMG;
        case 193500088: /* mp4 */
        case 193498837: /* mkv */ return TYPE_VID;
        case 193486360: /* apk */ return TYPE_APK;
        case 193499403: /* pdf */ return TYPE_DOC;
        default: return TYPE_FILE;
    }
}

// ==========================================
// SEARCH HELPERS
// ==========================================
static inline int has_glob_tokens(const char *pattern) {
    while (*pattern) {
        if (*pattern == '*' || *pattern == '?') return 1;
        pattern++;
    }
    return 0;
}

static inline unsigned char fold_ci(unsigned char c) {
    if (c >= 'A' && c <= 'Z') return (unsigned char)(c + 32);
    return c;
}

int neon_contains(const char *haystack, const char *needle, size_t n_len) {
    size_t h_len = strlen(haystack);
    if (h_len < n_len) return 0;

    uint8_t first = (uint8_t)tolower(needle[0]);
    uint8x16_t v_first = vdupq_n_u8(first);
    uint8x16_t v_case = vdupq_n_u8(0x20);

    size_t i = 0;
    for (; i + 16 <= h_len; i += 16) {
        uint8x16_t block = vld1q_u8((const uint8_t*)(haystack + i));
        uint8x16_t block_lower = vorrq_u8(block, v_case);
        uint8x16_t eq = vceqq_u8(v_first, block_lower);
        uint64x2_t fold = vpaddlq_u32(vpaddlq_u16(vpaddlq_u8(eq)));
        if (vgetq_lane_u64(fold, 0) | vgetq_lane_u64(fold, 1)) {
            for (int k = 0; k < 16; k++) {
                if (tolower(haystack[i + k]) == first) {
                    if (strncasecmp(haystack + i + k, needle, n_len) == 0) return 1;
                }
            }
        }
    }

    for (; i < h_len; i++) {
        if (tolower(haystack[i]) == first) {
            if (strncasecmp(haystack + i, needle, n_len) == 0) return 1;
        }
    }
    return 0;
}

static int glob_match_ci(const char *text, const char *pattern) {
    const char *t = text;
    const char *p = pattern;
    const char *star = NULL;
    const char *match = NULL;

    while (*t) {
        unsigned char tc = fold_ci((unsigned char)*t);
        unsigned char pc = (unsigned char)*p;

        if (pc && (pc == '?' || fold_ci(pc) == tc)) {
            t++;
            p++;
            continue;
        }
        if (pc == '*') {
            star = ++p;
            match = t;
            continue;
        }
        if (star) {
            p = star;
            t = ++match;
            continue;
        }
        return 0;
    }
    while (*p == '*') p++;
    return *p == '\0';
}

static inline int matches_query(const char *name, const char *query, size_t qlen, int glob_mode) {
    return glob_mode ? glob_match_ci(name, query) : neon_contains(name, query, qlen);
}

// ==========================================
// FAST LISTING CORE
// ==========================================
static inline unsigned char fast_get_type(const char *name, int name_len) {
    if (name_len < 4) return TYPE_FILE;

    const char *ptr = name + name_len - 4;
    uint32_t tail;
    __asm__ volatile (
        "ldr %w[val], [%[addr]] \n\t"
        "orr %w[val], %w[val], #0x20202020 \n\t"
        : [val] "=r" (tail)
        : [addr] "r" (ptr)
        : "cc"
    );

    switch (tail) {
        case 0x676e702e: return TYPE_IMG; // .png
        case 0x67706a2e: return TYPE_IMG; // .jpg
        case 0x34706d2e: return TYPE_VID; // .mp4
        case 0x766b6d2e: return TYPE_VID; // .mkv
        case 0x6b70612e: return TYPE_APK; // .apk
        case 0x6664702e: return TYPE_DOC; // .pdf
        default: return TYPE_FILE;
    }
}

JNIEXPORT jint JNICALL
Java_com_mewmix_glaive_core_NativeCore_nativeFillBuffer(JNIEnv *env, jobject clazz, jstring jPath, jobject jBuffer, jint capacity) {
    if (capacity <= 0) return 0;

    const char *path = (*env)->GetStringUTFChars(env, jPath, NULL);
    unsigned char *buffer = (*env)->GetDirectBufferAddress(env, jBuffer);
    if (!buffer) {
        (*env)->ReleaseStringUTFChars(env, jPath, path);
        return -2;
    }

    int fd = open(path, O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    if (fd == -1) {
        (*env)->ReleaseStringUTFChars(env, jPath, path);
        return -1;
    }

    unsigned char *head = buffer;
    unsigned char *end = buffer + capacity;
    char kbuf[4096];
    struct linux_dirent64 *d;
    struct stat st;
    int nread;

    while ((nread = syscall(__NR_getdents64, fd, kbuf, sizeof(kbuf))) > 0) {
        int bpos = 0;
        while (bpos < nread) {
            d = (struct linux_dirent64 *)(kbuf + bpos);
            bpos += d->d_reclen;

            if (d->d_name[0] == '.') continue;

            int name_len = 0;
            while (d->d_name[name_len] && name_len < 255) name_len++;

            if (head + 2 + name_len + 16 > end) {
                close(fd);
                (*env)->ReleaseStringUTFChars(env, jPath, path);
                return (jint)(head - buffer);
            }

            unsigned char type = TYPE_UNKNOWN;
            int64_t size = 0;
            int64_t time = 0;

            if (d->d_type == DT_DIR) {
                type = TYPE_DIR;
            } else if (d->d_type == DT_REG) {
                type = fast_get_type(d->d_name, name_len);
                if (fstatat(fd, d->d_name, &st, AT_SYMLINK_NOFOLLOW) == 0) {
                    size = st.st_size;
                    time = st.st_mtime;
                }
            } else {
                if (fstatat(fd, d->d_name, &st, AT_SYMLINK_NOFOLLOW) == 0) {
                    if (S_ISDIR(st.st_mode)) {
                        type = TYPE_DIR;
                    } else {
                        type = fast_get_type(d->d_name, name_len);
                        size = st.st_size;
                        time = st.st_mtime;
                    }
                } else {
                    type = TYPE_FILE;
                }
            }

            if (type == TYPE_UNKNOWN) type = fast_get_type(d->d_name, name_len);
            if (type == TYPE_UNKNOWN) type = TYPE_FILE;

            *head++ = type;
            *head++ = (unsigned char)name_len;
            memcpy(head, d->d_name, name_len);
            head += name_len;
            memcpy(head, &size, sizeof(int64_t));
            head += sizeof(int64_t);
            memcpy(head, &time, sizeof(int64_t));
            head += sizeof(int64_t);
        }
    }

    if (nread == -1) {
        LOGE("getdents64 failed: %s", strerror(errno));
    }

    close(fd);
    (*env)->ReleaseStringUTFChars(env, jPath, path);
    return (jint)(head - buffer);
}

// ==========================================
// SEARCH PIPELINE
// ==========================================
void recursive_scan(const char* base, const char* query, size_t qlen, int glob_mode, JNIEnv *env, jobjectArray result, int* idx, int max, jclass cls, jmethodID ctor) {
    if (*idx >= max) return;

    DIR* dir = opendir(base);
    if (!dir) return;

    struct dirent* d;
    char path[4096];

    while ((d = readdir(dir)) != NULL && *idx < max) {
        if (d->d_name[0] == '.') continue;

        snprintf(path, sizeof(path), "%s/%s", base, d->d_name);

        if (d->d_type == DT_DIR) {
            if (strcmp(d->d_name, "Android") != 0) {
                recursive_scan(path, query, qlen, glob_mode, env, result, idx, max, cls, ctor);
            }
        } else {
            if (matches_query(d->d_name, query, qlen, glob_mode)) {
                jstring jName = (*env)->NewStringUTF(env, d->d_name);
                jstring jPath = (*env)->NewStringUTF(env, path);
                jobject item = (*env)->NewObject(env, cls, ctor, jName, jPath, get_type(d->d_name, d->d_type), (jlong)0, (jlong)0);
                (*env)->SetObjectArrayElement(env, result, *idx, item);
                (*idx)++;

                (*env)->DeleteLocalRef(env, jName);
                (*env)->DeleteLocalRef(env, jPath);
                (*env)->DeleteLocalRef(env, item);
            }
        }
    }
    closedir(dir);
}

JNIEXPORT jobjectArray JNICALL
Java_com_mewmix_glaive_core_NativeCore_nativeSearch(JNIEnv *env, jclass clazz, jstring jRoot, jstring jQuery) {
    const char *root = (*env)->GetStringUTFChars(env, jRoot, NULL);
    const char *query = (*env)->GetStringUTFChars(env, jQuery, NULL);
    size_t qlen = strlen(query);
    int glob_mode = has_glob_tokens(query);

    jclass cls = (*env)->FindClass(env, "com/mewmix/glaive/data/GlaiveItem");
    jmethodID ctor = (*env)->GetMethodID(env, cls, "<init>", "(Ljava/lang/String;Ljava/lang/String;IJJ)V");
    jobjectArray result = (*env)->NewObjectArray(env, 500, cls, NULL);

    int count = 0;
    recursive_scan(root, query, qlen, glob_mode, env, result, &count, 500, cls, ctor);

    (*env)->ReleaseStringUTFChars(env, jRoot, root);
    (*env)->ReleaseStringUTFChars(env, jQuery, query);
    return result;
}
