/* fast-anything profiler runtime: compiled into the app for one run. */
#ifndef _CRT_SECURE_NO_WARNINGS
#define _CRT_SECURE_NO_WARNINGS
#endif
#include <stdio.h>
#include <stdlib.h>
#ifdef _WIN32
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#include <windows.h>
#else
#include <signal.h>
#include <unistd.h>
#endif
#include "fa_clock.h"
#include "fa_prof.h"

#define FA_MAX 65536
typedef struct { long long calls, own, cum; int active; } fa_stat;
static fa_stat fa_stats[FA_MAX];
static __thread long long fa_children[1024];
static __thread int fa_depth;
static long long fa_started;

#ifdef __cplusplus
extern "C" {
#endif
fa_prof_frame fa_prof_enter(int id) {
    fa_prof_frame f;
    f.id = id;
    if (id >= 0 && id < FA_MAX) fa_stats[id].active++;
    if (fa_depth < 1024) fa_children[fa_depth] = 0;
    fa_depth++;
    f.t = fa_now();
    return f;
}

void fa_prof_exit(fa_prof_frame *f) {
    long long took = fa_now() - f->t;
    fa_depth--;
    long long children = fa_depth < 1024 ? fa_children[fa_depth] : 0;
    if (f->id >= 0 && f->id < FA_MAX) {
        fa_stat *s = &fa_stats[f->id];
        s->calls++;
        s->own += took - children;
        if (--s->active == 0) s->cum += took;
    }
    if (fa_depth > 0 && fa_depth <= 1024) fa_children[fa_depth - 1] += took;
}
#ifdef __cplusplus
}
#endif

static void fa_prof_save(void) {
    const char *path = getenv("FAST_ANYTHING_PROF_OUT");
    FILE *out = path ? fopen(path, "wb") : NULL;
    if (!out) return;
    fprintf(out, "{\"wall\":%.9f,\"stats\":{", (double) (fa_now() - fa_started) / 1e9);
    int first = 1;
    for (int i = 0; i < FA_MAX; i++) {
        if (!fa_stats[i].calls) continue;
        fprintf(out, "%s\"%d\":[%lld,%.9f,%.9f]", first ? "" : ",", i, fa_stats[i].calls, (double) fa_stats[i].own / 1e9, (double) fa_stats[i].cum / 1e9);
        first = 0;
    }
    fprintf(out, "}}");
    fclose(out);
}

/* scan --duration: a server runs until closed, so end it after that long; exit() writes the profile
 * (atexit above). If the app is stuck where exit can't run, scan stops it from outside. */
#ifdef _WIN32
static DWORD WINAPI fa_prof_stop(LPVOID ms) {
    Sleep((DWORD) (size_t) ms);
    exit(0);
}
#else
static void fa_prof_stop(int sig) {
    (void) sig;
    exit(0);
}
#endif

__attribute__((constructor)) static void fa_prof_start(void) {
    fa_started = fa_now();
    atexit(fa_prof_save);
    const char *stop = getenv("FAST_ANYTHING_STOP_AFTER_MS");
    long ms = stop ? atol(stop) : 0;
    if (ms > 0) {
#ifdef _WIN32
        CreateThread(NULL, 0, fa_prof_stop, (LPVOID) (size_t) ms, 0, NULL);
#else
        signal(SIGALRM, fa_prof_stop);
        alarm((unsigned) ((ms + 999) / 1000));
#endif
    }
}
