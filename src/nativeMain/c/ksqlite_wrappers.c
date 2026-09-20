#include "ksqlite_wrappers.h"

int ksqlite_total_changes_int(sqlite3 *db) {
    return (int)sqlite3_total_changes64(db);
}

int ksqlite_changes_int(sqlite3 *db) {
    return (int)sqlite3_changes64(db);
}

int ksqlite_busy_timeout(sqlite3 *db, int ms) {
    return sqlite3_busy_timeout(db, ms);
}