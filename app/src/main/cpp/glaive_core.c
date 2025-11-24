#include <jni.h>
#include <dirent.h>
#include <sys/stat.h>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <ctype.h>
#include <stdint.h>
#include <arm_neon.h>
#include <android/log.h>

#define LOG_TAG "GLAIVE_C"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ==========================================
// FAST TYPE HASHING
// ==========================================
// Maps extensions to integers to avoid string comparison chains.
typedef enum {
    TYPE_UNKNOWN = 0,
    TYPE_DIR = 1,
    TYPE_IMG = 2,
    TYPE_VID = 3,
    TYPE_APK = 4,
    TYPE_DOC = 5
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
    dot++; // Skip dot

    // Precomputed hashes (DJB2)
    switch (hash_ext(dot)) {
        case 193490416: /* png */
        case 193497021: /* jpg */
        case 2090499160: /* jpeg */ return TYPE_IMG;
        case 193500088: /* mp4 */
        case 193498837: /* mkv */ return TYPE_VID;
        case 193486360: /* apk */ return TYPE_APK;
        case 193499403: /* pdf */ return TYPE_DOC;
        default: return TYPE_UNKNOWN;
    }
}

// ==========================================
// DATA STRUCTURES
// ==========================================
typedef struct {
    char *name;
    char *path;
    int type;
    long size;
    long mtime;
} Entry;

// Custom QSort Comparator: Dirs first, then Name Case-Insensitive
int compare_entries(const void *a, const void *b) {
    Entry *ea = (Entry *)a;
    Entry *eb = (Entry *)b;

    if ((ea->type == TYPE_DIR) != (eb->type == TYPE_DIR)) {
        return (eb->type == TYPE_DIR) - (ea->type == TYPE_DIR);
    }
    return strcasecmp(ea->name, eb->name);
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

// NEON substring match for literal queries
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

        // Check 128 bits at once
        uint64x2_t fold = vpaddlq_u32(vpaddlq_u16(vpaddlq_u8(eq)));
        if (vgetq_lane_u64(fold, 0) | vgetq_lane_u64(fold, 1)) {
            for (int k = 0; k < 16; k++) {
                if (tolower(haystack[i + k]) == first) {
                    if (strncasecmp(haystack + i + k, needle, n_len) == 0) return 1;
                }
            }
        }
    }
    // Cleanup tail
    for (; i < h_len; i++) {
        if (tolower(haystack[i]) == first) {
             if (strncasecmp(haystack + i, needle, n_len) == 0) return 1;
        }
    }
    return 0;
}

// Glob matcher with ASCII folding and non-recursive backtracking
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
// JNI: LIST DIRECTORY
// ==========================================
#define BUFFER_CAPACITY (2 * 1024 * 1024) // 2MB Chunk
#define TYPE_DIR 1
#define TYPE_FILE 2

// Write helper
static inline void write_byte(unsigned char** ptr, unsigned char val) { *(*ptr)++ = val; }
static inline void write_long(unsigned char** ptr, long val) {
    // Little Endian packing
    *(*ptr)++ = (unsigned char)(val);
    *(*ptr)++ = (unsigned char)(val >> 8);
    *(*ptr)++ = (unsigned char)(val >> 16);
    *(*ptr)++ = (unsigned char)(val >> 24);
    *(*ptr)++ = (unsigned char)(val >> 32);
    *(*ptr)++ = (unsigned char)(val >> 40);
    *(*ptr)++ = (unsigned char)(val >> 48);
    *(*ptr)++ = (unsigned char)(val >> 56);
}

JNIEXPORT jobject JNICALL
Java_com_mewmix_glaive_core_NativeCore_nativeListBuffer(JNIEnv *env, jclass clazz, jstring jPath) {
    const char *path = (*env)->GetStringUTFChars(env, jPath, NULL);
    DIR *dir = opendir(path);
    if (!dir) {
        (*env)->ReleaseStringUTFChars(env, jPath, path);
        return NULL;
    }

    // Allocate a raw block of memory. 
    // In a real crusade, you'd reuse this buffer to avoid malloc overhead.
    unsigned char* buffer = malloc(BUFFER_CAPACITY);
    unsigned char* head = buffer;
    unsigned char* end = buffer + BUFFER_CAPACITY;

    struct dirent *d;
    char full_path[4096];
    struct stat st;

    while ((d = readdir(dir)) != NULL) {
        if (d->d_name[0] == '.') continue;
        
        size_t name_len = strlen(d->d_name);
        if (name_len > 255) name_len = 255; // Clamp for protocol
        
        // Check bounds: Type(1) + Len(1) + Name(N) + Size(8)
        if (head + 1 + 1 + name_len + 8 >= end) break; // Buffer full (handle logic later)

        // 1. TYPE
        unsigned char type = (d->d_type == DT_DIR) ? TYPE_DIR : TYPE_FILE;
        // Simple heuristic for specific types if FILE
        if (type == TYPE_FILE) {
             // Re-use your fast hash_ext logic here to set TYPE_IMG, TYPE_APK etc.
             // For now, packing simplified types.
             const char *dot = strrchr(d->d_name, '.');
             if (dot) {
                 dot++;
                 switch (hash_ext(dot)) {
                    case 193490416: /* png */
                    case 193497021: /* jpg */
                    case 2090499160: /* jpeg */ type = 2; break; // TYPE_IMG
                    case 193500088: /* mp4 */
                    case 193498837: /* mkv */ type = 3; break; // TYPE_VID
                    case 193486360: /* apk */ type = 4; break; // TYPE_APK
                    case 193499403: /* pdf */ type = 5; break; // TYPE_DOC
                 }
             }
        }
        write_byte(&head, type);

        // 2. NAME LENGTH
        write_byte(&head, (unsigned char)name_len);

        // 3. NAME BYTES
        memcpy(head, d->d_name, name_len);
        head += name_len;

        // 4. SIZE (Only stat if needed, expensive!)
        long size = 0;
        if (d->d_type != DT_DIR) {
            snprintf(full_path, sizeof(full_path), "%s/%s", path, d->d_name);
            // stat is the bottleneck. Consider fstatat or skipping size for speed mode
            if (stat(full_path, &st) == 0) size = st.st_size;
        }
        write_long(&head, size);
    }
    
    closedir(dir);
    (*env)->ReleaseStringUTFChars(env, jPath, path);

    // Create a DirectByteBuffer. This wraps the C memory without copying.
    // Java will free it if we attach a clean-up, but for now we return a copy
    // to ensure safety, or use NewDirectByteBuffer if you manage lifecycle manually.
    // Safest for MVP:
    // jobject directBuffer = (*env)->NewDirectByteBuffer(env, buffer, head - buffer);
    
    // Note: If you use malloc + NewDirectByteBuffer, the JVM doesn't free the C memory automatically 
    // unless you rely on sun.misc.Cleaner or copy it. 
    // BETTER APPROACH for JNI: Return a standard Byte Array copy for safety unless you are an expert.
    
    jbyteArray retArr = (*env)->NewByteArray(env, (jsize)(head - buffer));
    (*env)->SetByteArrayRegion(env, retArr, 0, (jsize)(head - buffer), (jbyte*)buffer);
    
    free(buffer);
    return retArr;
}

// ==========================================
// JNI: SEARCH (Recursive)
// ==========================================
// Simplified implementation for brevity
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
             if (strcmp(d->d_name, "Android") != 0) { // Skip Android data
                 recursive_scan(path, query, qlen, glob_mode, env, result, idx, max, cls, ctor);
             }
        } else {
            if (matches_query(d->d_name, query, qlen, glob_mode)) {
                // Match found
                jstring jName = (*env)->NewStringUTF(env, d->d_name);
                jstring jPath = (*env)->NewStringUTF(env, path);
                // Mock size/time for search speed
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
    jobjectArray result = (*env)->NewObjectArray(env, 500, cls, NULL); // Cap at 500

    int count = 0;
    recursive_scan(root, query, qlen, glob_mode, env, result, &count, 500, cls, ctor);

    (*env)->ReleaseStringUTFChars(env, jRoot, root);
    (*env)->ReleaseStringUTFChars(env, jQuery, query);
    return result;
}
