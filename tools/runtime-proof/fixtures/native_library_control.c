/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
#include <jni.h>
#include <stdio.h>

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)vm;
    (void)reserved;
    fprintf(stderr, "PROBE_UNKNOWN_NATIVE_INITIALIZER_EXECUTED\n");
    return JNI_VERSION_1_8;
}

JNIEXPORT jint JNICALL Java_RuntimeNativeLibraryProbe_originalNativeBody(JNIEnv *jni, jclass type) {
    (void)jni;
    (void)type;
    return 7;
}
