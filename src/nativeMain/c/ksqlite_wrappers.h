/*
 * Thin non-deprecated wrappers around sqlite3_* functions. Some
 * Kotlin/Native cinterops indexers mark the upstream functions as
 * deprecated; we sidestep that by exposing the same functionality under
 * our own names so the Kotlin side can call them unconditionally.
 *
 * Kept intentionally small — these are inline passthroughs. Anything
 * nontrivial (lifetime handling, ownership, encoding) lives in
 * ksqlite_shim.c.
 */
#ifndef KSQLITE_WRAPPERS_H
#define KSQLITE_WRAPPERS_H

#include "sqlite3.h"

#ifdef __cplusplus
extern "C" {
#endif

/* Returns sqlite3_total_changes64 cast to int — fits the common case
 * (millions of rows in a single exec); callers can still call
 * sqlite3_total_changes64 directly for the full 64-bit range. */
int ksqlite_total_changes_int(sqlite3 *db);

int ksqlite_changes_int(sqlite3 *db);

/* Busy-timeout wrapper so we don't have to import the upstream symbol. */
int ksqlite_busy_timeout(sqlite3 *db, int ms);

#ifdef __cplusplus
}
#endif

#endif /* KSQLITE_WRAPPERS_H */