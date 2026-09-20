/*
 * Helpers exported alongside the SQLite amalgamation so the Kotlin/Native
 * bindings don't have to deal with SQLITE_STATIC vs SQLITE_TRANSIENT
 * lifetime subtleties directly. Every ksqlite_bind_* call asks SQLite to
 * copy the bytes, so the caller can release the Kotlin-side buffer
 * immediately. ksqlite_column_text_dup / ksqlite_column_blob_dup likewise
 * allocate a SQLite-owned copy that the caller frees via sqlite3_free
 * (or, from Kotlin, via the platform.internal_sqlite helper below).
 */
#include "ksqlite_shim.h"

#include "sqlite3.h"
#include "sqlite-vec/sqlite-vec.h"
#include <stdlib.h>
#include <string.h>

int ksqlite_bind_text(sqlite3_stmt *stmt, int idx, const char *text, int len) {
    return sqlite3_bind_text(stmt, idx, text, len, SQLITE_TRANSIENT);
}

int ksqlite_bind_blob(sqlite3_stmt *stmt, int idx, const void *data, int len) {
    return sqlite3_bind_blob(stmt, idx, data, len, SQLITE_TRANSIENT);
}

/*
 * Allocate a sqlite3-owned copy of the column text and write its byte
 * length into *out_len. Caller frees via sqlite3_free(). Returns NULL on
 * SQL NULL or OOM. We always NUL-terminate so the result can be read as a
 * C string, but the on-the-wire length may be smaller if the column value
 * itself contains an embedded NUL (SQLite text columns permit that).
 */
char *ksqlite_column_text_dup(sqlite3_stmt *stmt, int idx, int *out_len) {
    const unsigned char *text = sqlite3_column_text(stmt, idx);
    if (text == NULL) {
        *out_len = 0;
        return NULL;
    }
    int len = sqlite3_column_bytes(stmt, idx);
    char *out = (char *)sqlite3_malloc((sqlite3_uint64)len + 1);
    if (out == NULL) {
        *out_len = 0;
        return NULL;
    }
    if (len > 0) memcpy(out, text, (size_t)len);
    out[len] = '\0';
    *out_len = len;
    return out;
}

/*
 * Allocate a sqlite3-owned copy of the column blob and write its byte
 * length into *out_len. Caller frees via sqlite3_free(). Returns NULL on
 * SQL NULL or OOM.
 */
unsigned char *ksqlite_column_blob_dup(sqlite3_stmt *stmt, int idx, int *out_len) {
    const void *blob = sqlite3_column_blob(stmt, idx);
    if (blob == NULL) {
        *out_len = 0;
        return NULL;
    }
    int len = sqlite3_column_bytes(stmt, idx);
    unsigned char *out = (unsigned char *)sqlite3_malloc((sqlite3_uint64)len > 0 ? (sqlite3_uint64)len : 1);
    if (out == NULL) {
        *out_len = 0;
        return NULL;
    }
    if (len > 0) memcpy(out, blob, (size_t)len);
    *out_len = len;
    return out;
}

void ksqlite_free(void *p) {
    sqlite3_free(p);
}

/*
 * sqlite3_auto_extension() accepts a `void(*)(void)` per its public API,
 * but SQLite actually calls the registered function with `(sqlite3 *,
 * char **, const sqlite3_api_routines *)` when each new connection is
 * opened. The public type is intentional unspecifiedness so you can
 * cast your real entry-point function to `void(*)(void)`. sqlite-vec
 * follows this convention — its `sqlite3_vec_init` already matches the
 * signature SQLite passes — so a straight cast works on every platform
 * we ship to.
 *
 * For details see https://sqlite.org/c3ref/auto_extension.html and the
 * `sqlite3AutoExtensionEntry` impl in sqlite3.c.
 */
int ksqlite_init(void) {
    sqlite3_auto_extension((void (*)(void)) sqlite3_vec_init);
    return SQLITE_OK;
}