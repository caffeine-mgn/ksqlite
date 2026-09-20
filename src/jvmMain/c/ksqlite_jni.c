/*
 * JNI bindings for ksqlite: 1:1 mapping from Java_pw_binom_db_ksqlite_SQLiteNative_*
 * functions to their sqlite3_* counterparts. Where SQLite returns a pointer
 * to memory owned by the engine (column text / blob), we copy the bytes
 * into a Java-managed byte[] so the JVM does not depend on SQLite's
 * allocator or its stmt lifetime.
 *
 * All K/N and JVM native targets register sqlite-vec as an auto-extension
 * at startup via JNI_OnLoad, so callers never have to opt in.
 */

#include <jni.h>
#include <stddef.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "sqlite3.h"
#include "ksqlite_shim.h"

static JavaVM *ksql_jvm = NULL;

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)reserved;
    JNIEnv *env = NULL;
    if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
    ksql_jvm = vm;
    ksqlite_init();
    return JNI_VERSION_1_6;
}

/* ---- SQLite handle lifecycle -------------------------------------- */

JNIEXPORT jlong JNICALL Java_pw_binom_db_ksqlite_SQLiteNative_open(
        JNIEnv *env, jclass cls, jstring jpath, jint flags) {
    (void)cls;
    const char *path = (*env)->GetStringUTFChars(env, jpath, NULL);
    sqlite3 *db = NULL;
    int rc = sqlite3_open_v2(path, &db, (int)flags, NULL);
    (*env)->ReleaseStringUTFChars(env, jpath, path);
    if (rc != SQLITE_OK) {
        if (db != NULL) sqlite3_close(db);
        return (jlong)rc;
    }
    return (jlong)(intptr_t)db;
}

JNIEXPORT jint JNICALL Java_pw_binom_db_ksqlite_SQLiteNative_close(
        JNIEnv *env, jclass cls, jlong handle) {
    (void)env; (void)cls;
    return sqlite3_close((sqlite3 *)(intptr_t)handle);
}

/* ---- One-shot exec (multi-statement SQL) --------------------------- */

JNIEXPORT jint JNICALL Java_pw_binom_db_ksqlite_SQLiteNative_exec(
        JNIEnv *env, jclass cls, jlong handle, jstring jsql, jintArray jchangesOut) {
    (void)cls;
    int before = sqlite3_total_changes((sqlite3 *)(intptr_t)handle);
    const char *sql = (*env)->GetStringUTFChars(env, jsql, NULL);
    char *errmsg = NULL;
    int rc = sqlite3_exec((sqlite3 *)(intptr_t)handle, sql, NULL, NULL, &errmsg);
    (*env)->ReleaseStringUTFChars(env, jsql, sql);
    if (rc != SQLITE_OK) {
        if (errmsg != NULL) sqlite3_free(errmsg);
        return rc;
    }
    int delta = sqlite3_total_changes((sqlite3 *)(intptr_t)handle) - before;
    if (jchangesOut != NULL) (*env)->SetIntArrayRegion(env, jchangesOut, 0, 1, &delta);
    return SQLITE_OK;
}

/* ---- Compilation and stepping -------------------------------------- */

JNIEXPORT jint JNICALL Java_pw_binom_db_ksqlite_SQLiteNative_prepare(
        JNIEnv *env, jclass cls, jlong handle, jstring jsql, jlongArray jstmtOut) {
    (void)cls;
    sqlite3_stmt *stmt = NULL;
    const char *sql = (*env)->GetStringUTFChars(env, jsql, NULL);
    int rc = sqlite3_prepare_v2(
        (sqlite3 *)(intptr_t)handle,
        sql,
        -1,
        &stmt,
        NULL);
    (*env)->ReleaseStringUTFChars(env, jsql, sql);
    if (rc != SQLITE_OK) {
        if (stmt != NULL) sqlite3_finalize(stmt);
        return rc;
    }
    if (jstmtOut != NULL) {
        jlong out = (jlong)(intptr_t)stmt;
        (*env)->SetLongArrayRegion(env, jstmtOut, 0, 1, &out);
    }
    return SQLITE_OK;
}

JNIEXPORT jint JNICALL Java_pw_binom_db_ksqlite_SQLiteNative_step(
        JNIEnv *env, jclass cls, jlong stmt) {
    (void)env; (void)cls;
    return sqlite3_step((sqlite3_stmt *)(intptr_t)stmt);
}

JNIEXPORT jint JNICALL Java_pw_binom_db_ksqlite_SQLiteNative_reset(
        JNIEnv *env, jclass cls, jlong stmt) {
    (void)env; (void)cls;
    return sqlite3_reset((sqlite3_stmt *)(intptr_t)stmt);
}

JNIEXPORT jint JNICALL Java_pw_binom_db_ksqlite_SQLiteNative_finalize(
        JNIEnv *env, jclass cls, jlong stmt) {
    (void)env; (void)cls;
    return sqlite3_finalize((sqlite3_stmt *)(intptr_t)stmt);
}

JNIEXPORT jint JNICALL Java_pw_binom_db_ksqlite_SQLiteNative_clearBindings(
        JNIEnv *env, jclass cls, jlong stmt) {
    (void)env; (void)cls;
    return sqlite3_clear_bindings((sqlite3_stmt *)(intptr_t)stmt);
}

JNIEXPORT jint JNICALL Java_pw_binom_db_ksqlite_SQLiteNative_busyTimeout(
        JNIEnv *env, jclass cls, jlong handle, jint ms) {
    (void)env; (void)cls;
    return sqlite3_busy_timeout((sqlite3 *)(intptr_t)handle, (int)ms);
}

/* ---- Per-connection stats ------------------------------------------ */

JNIEXPORT jlong JNICALL Java_pw_binom_db_ksqlite_SQLiteNative_lastInsertRowId(
        JNIEnv *env, jclass cls, jlong handle) {
    (void)env; (void)cls;
    return (jlong)sqlite3_last_insert_rowid((sqlite3 *)(intptr_t)handle);
}

JNIEXPORT jint JNICALL Java_pw_binom_db_ksqlite_SQLiteNative_changes(
        JNIEnv *env, jclass cls, jlong handle) {
    (void)env; (void)cls;
    return sqlite3_changes((sqlite3 *)(intptr_t)handle);
}

JNIEXPORT jint JNICALL Java_pw_binom_db_ksqlite_SQLiteNative_totalChanges(
        JNIEnv *env, jclass cls, jlong handle) {
    (void)env; (void)cls;
    return sqlite3_total_changes((sqlite3 *)(intptr_t)handle);
}

/* ---- Bindings ----------------------------------------------------- */

JNIEXPORT jint JNICALL Java_pw_binom_db_ksqlite_SQLiteNative_bindNull(
        JNIEnv *env, jclass cls, jlong stmt, jint idx) {
    (void)env; (void)cls;
    return sqlite3_bind_null((sqlite3_stmt *)(intptr_t)stmt, (int)idx);
}

JNIEXPORT jint JNICALL Java_pw_binom_db_ksqlite_SQLiteNative_bindLong(
        JNIEnv *env, jclass cls, jlong stmt, jint idx, jlong value) {
    (void)env; (void)cls;
    return sqlite3_bind_int64((sqlite3_stmt *)(intptr_t)stmt, (int)idx, (sqlite3_int64)value);
}

JNIEXPORT jint JNICALL Java_pw_binom_db_ksqlite_SQLiteNative_bindDouble(
        JNIEnv *env, jclass cls, jlong stmt, jint idx, jdouble value) {
    (void)env; (void)cls;
    return sqlite3_bind_double((sqlite3_stmt *)(intptr_t)stmt, (int)idx, (double)value);
}

JNIEXPORT jint JNICALL Java_pw_binom_db_ksqlite_SQLiteNative_bindText(
        JNIEnv *env, jclass cls, jlong stmt, jint idx, jstring value) {
    (void)cls;
    const char *utf = (*env)->GetStringUTFChars(env, value, NULL);
    int len = (*env)->GetStringUTFLength(env, value);
    int rc = sqlite3_bind_text(
        (sqlite3_stmt *)(intptr_t)stmt,
        (int)idx,
        utf,
        len,
        SQLITE_TRANSIENT);
    (*env)->ReleaseStringUTFChars(env, value, utf);
    return rc;
}

JNIEXPORT jint JNICALL Java_pw_binom_db_ksqlite_SQLiteNative_bindBlob(
        JNIEnv *env, jclass cls, jlong stmt, jint idx, jbyteArray value) {
    (void)cls;
    if (value == NULL) {
        return sqlite3_bind_null((sqlite3_stmt *)(intptr_t)stmt, (int)idx);
    }
    jsize len = (*env)->GetArrayLength(env, value);
    jbyte *bytes = (*env)->GetByteArrayElements(env, value, NULL);
    int rc = sqlite3_bind_blob(
        (sqlite3_stmt *)(intptr_t)stmt,
        (int)idx,
        bytes,
        (int)len,
        SQLITE_TRANSIENT);
    (*env)->ReleaseByteArrayElements(env, value, bytes, JNI_ABORT);
    return rc;
}

/* ---- Column metadata ---------------------------------------------- */

JNIEXPORT jint JNICALL Java_pw_binom_db_ksqlite_SQLiteNative_columnCount(
        JNIEnv *env, jclass cls, jlong stmt) {
    (void)env; (void)cls;
    return sqlite3_column_count((sqlite3_stmt *)(intptr_t)stmt);
}

JNIEXPORT jstring JNICALL Java_pw_binom_db_ksqlite_SQLiteNative_columnName(
        JNIEnv *env, jclass cls, jlong stmt, jint idx) {
    (void)cls;
    const char *name = sqlite3_column_name((sqlite3_stmt *)(intptr_t)stmt, (int)idx);
    if (name == NULL) return NULL;
    return (*env)->NewStringUTF(env, name);
}

JNIEXPORT jint JNICALL Java_pw_binom_db_ksqlite_SQLiteNative_columnType(
        JNIEnv *env, jclass cls, jlong stmt, jint idx) {
    (void)env; (void)cls;
    return sqlite3_column_type((sqlite3_stmt *)(intptr_t)stmt, (int)idx);
}

/* ---- Column reads -------------------------------------------------- */

JNIEXPORT jlong JNICALL Java_pw_binom_db_ksqlite_SQLiteNative_getLong(
        JNIEnv *env, jclass cls, jlong stmt, jint idx) {
    (void)env; (void)cls;
    return (jlong)sqlite3_column_int64((sqlite3_stmt *)(intptr_t)stmt, (int)idx);
}

JNIEXPORT jdouble JNICALL Java_pw_binom_db_ksqlite_SQLiteNative_getDouble(
        JNIEnv *env, jclass cls, jlong stmt, jint idx) {
    (void)env; (void)cls;
    return (jdouble)sqlite3_column_double((sqlite3_stmt *)(intptr_t)stmt, (int)idx);
}

JNIEXPORT jstring JNICALL Java_pw_binom_db_ksqlite_SQLiteNative_getText(
        JNIEnv *env, jclass cls, jlong stmt, jint idx) {
    (void)cls;
    const unsigned char *text = sqlite3_column_text((sqlite3_stmt *)(intptr_t)stmt, (int)idx);
    if (text == NULL) return NULL;
    /*
     * NewStringUTF reads up to the first NUL. SQLite TEXT values that
     * intentionally contain embedded NULs should be stored as BLOB and
     * fetched via getBytes — getText's C-string semantics are the documented
     * contract.
     */
    return (*env)->NewStringUTF(env, (const char *)text);
}

/*
 * Better-typed text read: explicit length, no implicit truncation at
 * NUL. Used by SQLiteResultSet.getText() so TEXT values that contain
 * binary-looking data round-trip correctly.
 */
JNIEXPORT jbyteArray JNICALL Java_pw_binom_db_ksqlite_SQLiteNative_getBytes(
        JNIEnv *env, jclass cls, jlong stmt, jint idx) {
    (void)cls;
    const void *bytes = sqlite3_column_blob((sqlite3_stmt *)(intptr_t)stmt, (int)idx);
    if (bytes == NULL) return NULL;
    int len = sqlite3_column_bytes((sqlite3_stmt *)(intptr_t)stmt, (int)idx);
    jbyteArray out = (*env)->NewByteArray(env, (jsize)len);
    if (out == NULL) return NULL;
    (*env)->SetByteArrayRegion(env, out, 0, (jsize)len, (const jbyte *)bytes);
    return out;
}

JNIEXPORT jint JNICALL Java_pw_binom_db_ksqlite_SQLiteNative_errmsg(
        JNIEnv *env, jclass cls, jlong handle, jbyteArray out, jint maxLen) {
    (void)cls;
    const char *msg = sqlite3_errmsg((sqlite3 *)(intptr_t)handle);
    if (msg == NULL || out == NULL) return 0;
    int len = (int)strnlen(msg, (size_t)maxLen);
    (*env)->SetByteArrayRegion(env, out, 0, (jsize)len, (const jbyte *)msg);
    return len;
}

/* ---- Flag constants exposed for the JVM caller --------------------- */

JNIEXPORT jint JNICALL Java_pw_binom_db_ksqlite_SQLiteNative_flagOpenReadWrite(
        JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    return SQLITE_OPEN_READWRITE;
}

JNIEXPORT jint JNICALL Java_pw_binom_db_ksqlite_SQLiteNative_flagOpenCreate(
        JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    return SQLITE_OPEN_CREATE;
}

JNIEXPORT jint JNICALL Java_pw_binom_db_ksqlite_SQLiteNative_flagOpenReadOnly(
        JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    return SQLITE_OPEN_READONLY;
}

JNIEXPORT jint JNICALL Java_pw_binom_db_ksqlite_SQLiteNative_flagOpenMemory(
        JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    return SQLITE_OPEN_MEMORY;
}

JNIEXPORT jint JNICALL Java_pw_binom_db_ksqlite_SQLiteNative_sqliteOk(
        JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    return SQLITE_OK;
}

JNIEXPORT jint JNICALL Java_pw_binom_db_ksqlite_SQLiteNative_sqliteRow(
        JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    return SQLITE_ROW;
}

JNIEXPORT jint JNICALL Java_pw_binom_db_ksqlite_SQLiteNative_sqliteDone(
        JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    return SQLITE_DONE;
}

JNIEXPORT jint JNICALL Java_pw_binom_db_ksqlite_SQLiteNative_sqliteInteger(
        JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    return SQLITE_INTEGER;
}

JNIEXPORT jint JNICALL Java_pw_binom_db_ksqlite_SQLiteNative_sqliteFloat(
        JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    return SQLITE_FLOAT;
}

JNIEXPORT jint JNICALL Java_pw_binom_db_ksqlite_SQLiteNative_sqliteText(
        JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    return SQLITE_TEXT;
}

JNIEXPORT jint JNICALL Java_pw_binom_db_ksqlite_SQLiteNative_sqliteBlob(
        JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    return SQLITE_BLOB;
}

JNIEXPORT jint JNICALL Java_pw_binom_db_ksqlite_SQLiteNative_sqliteNull(
        JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    return SQLITE_NULL;
}