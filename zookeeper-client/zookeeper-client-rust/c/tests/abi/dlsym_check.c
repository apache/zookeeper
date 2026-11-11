/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
 * Verifies dynamic symbol resolution via dlopen/dlsym.
 *
 * Usage: dlsym_check LIBRARY GOLDEN_NAMES ABSENT_NAMES
 *
 * Each symbol list file contains one unmangled C identifier per line.
 */

#include <dlfcn.h>
#include <stdio.h>
#include <string.h>

/*
 * Resolves each symbol listed in `file` against `lib`. Returns the count of
 * symbols whose resolution state differs from `want_present`, or -1 if `file`
 * cannot be opened.
 */
static int check_names(void *lib, const char *file, int want_present, int *total)
{
    char name[256];
    int bad = 0;
    FILE *f = fopen(file, "r");

    if (f == NULL) {
        printf("FAIL cannot open %s\n", file);
        return -1;
    }
    while (fgets(name, sizeof(name), f) != NULL) {
        size_t n = strcspn(name, "\r\n");
        int present;
        name[n] = '\0';
        if (n == 0) {
            continue;
        }
        (*total)++;
        present = dlsym(lib, name) != NULL;
        if (present != want_present) {
            printf("FAIL %s: %s\n", name, present ? "present" : "absent");
            bad++;
        }
    }
    fclose(f);
    return bad;
}

int main(int argc, char **argv)
{
    void *lib;
    int golden = 0;
    int absent = 0;
    int bad_g;
    int bad_a;

    if (argc != 4) {
        fprintf(stderr, "usage: dlsym_check LIBRARY GOLDEN_NAMES ABSENT_NAMES\n");
        return 2;
    }
    lib = dlopen(argv[1], RTLD_NOW | RTLD_LOCAL);
    if (lib == NULL) {
        printf("FAIL dlopen: %s\n", dlerror());
        return 1;
    }
    bad_g = check_names(lib, argv[2], 1, &golden);
    bad_a = check_names(lib, argv[3], 0, &absent);
    printf("dlsym_check: %d golden names, %d absent names, %d mismatches\n", golden, absent,
           (bad_g < 0 ? 1 : bad_g) + (bad_a < 0 ? 1 : bad_a));
    return bad_g == 0 && bad_a == 0 ? 0 : 1;
}
