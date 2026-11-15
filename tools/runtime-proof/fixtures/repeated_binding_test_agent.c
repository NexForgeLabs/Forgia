/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
/* Test-only service: repeat real NativeMethodBind observations while exercising the unchanged guards.
   No extra mutation, binding or permission API is exposed to Java. */
#define Agent_OnLoad original_proof_agent_load
#include "runtime_proof.c"
#undef Agent_OnLoad

static void JNICALL repeated_native_bind(jvmtiEnv *env, JNIEnv *jni, jthread thread, jmethodID method,
                                         void *address, void **replacement) {
    proof_native_bind(env, jni, thread, method, address, replacement);
    proof_native_bind(env, jni, thread, method, address, replacement);
}

JNIEXPORT jint JNICALL Agent_OnLoad(JavaVM *vm, char *options, void *reserved) {
    jint result = original_proof_agent_load(vm, options, reserved);
    if (result != JNI_OK) return result;
    jvmtiEventCallbacks callbacks = {0};
    callbacks.VMInit = &vm_init;
    callbacks.ClassFileLoadHook = &changed;
    callbacks.VMStart = &proof_vm_start;
    callbacks.FieldAccess = &proof_field_read;
    callbacks.ClassPrepare = &prepare_module;
    callbacks.FieldModification = &proof_field_write;
    callbacks.SingleStep = &proof_step;
    callbacks.Breakpoint = &proof_method_entry;
    callbacks.NativeMethodBind = &repeated_native_bind;
    return (*proof_vm)->SetEventCallbacks(proof_vm, &callbacks, sizeof(callbacks)) == JVMTI_ERROR_NONE ? JNI_OK : JNI_ERR;
}
