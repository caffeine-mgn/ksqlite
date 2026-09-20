/*
 * SQLite helpers shared between the Kotlin/Native and JNI sides. See
 * ksqlite_shim.c for the rationale behind each function.
 *
 * These wrappers are deliberately tiny: they exist so the Kotlin callers
 * can bind and read columns without dealing with SQLITE_STATIC vs
 * SQLITE_TRANSIENT or with sqlite3_malloc lifetime rules.
 */
#ifndef KSQLITE_SHIM_H
#define KSQLITE_SHIM_H

#include "sqlite3.h"

#ifdef __cplusplus
extern "C" {
#endif

/* Bind UTF-8 text by copying (SQLITE_TRANSIENT). */
int ksqlite_bind_text(sqlite3_stmt *stmt, int idx, const char *text, int len);

/* Bind a BLOB by copying (SQLITE_TRANSIENT). */
int ksqlite_bind_blob(sqlite3_stmt *stmt, int idx, const void *data, int len);

/* Allocate a sqlite3_malloc'd copy of the column text. NULL on SQL NULL. */
char *ksqlite_column_text_dup(sqlite3_stmt *stmt, int idx, int *out_len);

/* Allocate a sqlite3_malloc'd copy of the column blob. NULL on SQL NULL. */
unsigned char *ksqlite_column_blob_dup(sqlite3_stmt *stmt, int idx, int *out_len);

/* Wrapper around sqlite3_free for callers that don't want to import the
 * SQLite header. */
void ksqlite_free(void *p);

/*
 * Register sqlite-vec (sqlite3_vec_init) with sqlite3_auto_extension so
 * every subsequently opened connection automatically loads the vec0
 * virtual table and the vec_F32() distance helpers.
 *
 * Safe to call multiple times: each sqlite3_auto_extension call is
 * idempotent for the same function pointer. The Kotlin/Native side calls
 * this from main()'s auto-init; the JNI side calls it from JNI_OnLoad().
 *
 * Returns SQLITE_OK on success.
 */
int ksqlite_init(void);

#ifdef __cplusplus
}
#endif

#endif /* KSQLITE_SHIM_H */