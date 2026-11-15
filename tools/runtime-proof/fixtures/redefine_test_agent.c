/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
/* Test-only variant. The production service exports no code replacement API. Compile this translation
   unit instead of runtime_proof.c, plus the same access_enforcement.c, to exercise the actual refusal. */
#define Agent_OnLoad original_proof_agent_load
#define prepare_module original_prepare_module
#include "runtime_proof.c"
#undef prepare_module
#undef Agent_OnLoad

static jint JNICALL attempt_redefinition(JNIEnv *jni, jclass caller, jclass target, jbyteArray replacement) {
    (void)caller;
    jbyte *bytes = (*jni)->GetByteArrayElements(jni, replacement, NULL);
    if (!bytes) return JVMTI_ERROR_OUT_OF_MEMORY;
    jvmtiClassDefinition definition = {target, (*jni)->GetArrayLength(jni, replacement), (unsigned char *)bytes};
    jvmtiError result = (*proof_vm)->RedefineClasses(proof_vm, 1, &definition);
    (*jni)->ReleaseByteArrayElements(jni, replacement, bytes, JNI_ABORT);
    return result;
}
static void JNICALL prepare_test(jvmtiEnv *env, JNIEnv *jni, jthread thread, jclass actual) {
    original_prepare_module(env, jni, thread, actual);
    char *name = NULL;
    if ((*env)->GetClassSignature(env, actual, &name, NULL) == JVMTI_ERROR_NONE && name) {
        if (strcmp(name, "LRedefinitionAttemptProbe;") == 0) {
            JNINativeMethod method = {"attempt", "(Ljava/lang/Class;[B)I", (void *)attempt_redefinition};
            if ((*jni)->RegisterNatives(jni, actual, &method, 1) != 0) (*jni)->FatalError(jni, "Test JNI registration failed");
        }
        (*env)->Deallocate(env, (unsigned char *)name);
    }
}
JNIEXPORT jint JNICALL Agent_OnLoad(JavaVM *vm, char *options, void *reserved) {
    jint result = original_proof_agent_load(vm, options, reserved);
    if (result != JNI_OK) return result;
    jvmtiEventCallbacks callbacks = {0};
    callbacks.VMInit = &vm_init;
    callbacks.ClassFileLoadHook = &changed;
    callbacks.VMStart = &proof_vm_start;
    callbacks.FieldAccess = &proof_field_read;
    callbacks.ClassPrepare = &prepare_test;
    callbacks.FieldModification = &proof_field_write;
    callbacks.SingleStep = &proof_step;
    callbacks.Breakpoint = &proof_method_entry;
    callbacks.NativeMethodBind = &proof_native_bind;
    return (*proof_vm)->SetEventCallbacks(proof_vm, &callbacks, sizeof(callbacks)) == JVMTI_ERROR_NONE ? JNI_OK : JNI_ERR;
}
