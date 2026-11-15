/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
/* Fault injection only. The production service has no API accepting supplied VM evidence. */
#define Agent_OnLoad original_proof_agent_load
#define vm_init original_vm_init
#define prepare_module original_prepare_module
#include "runtime_proof.c"
#undef prepare_module
#undef vm_init
#undef Agent_OnLoad

static jbyteArray injected_snapshot;
static int inject_snapshot;
static jbyteArray JNICALL evidence_snapshot(JNIEnv *jni, jclass caller, jclass actual) {
    if (inject_snapshot) return injected_snapshot ? (*jni)->NewLocalRef(jni, injected_snapshot) : NULL;
    return describe(jni, caller, actual);
}
static void JNICALL configure_snapshot(JNIEnv *jni, jclass caller, jbyteArray bytes, jboolean enabled) {
    (void)caller;
    if (injected_snapshot) (*jni)->DeleteGlobalRef(jni, injected_snapshot);
    injected_snapshot = bytes ? (*jni)->NewGlobalRef(jni, bytes) : NULL;
    inject_snapshot = enabled;
}
static void JNICALL evidence_vm_init(jvmtiEnv *env, JNIEnv *jni, jthread thread) {
    original_vm_init(env, jni, thread);
    jclass bridge = (*jni)->FindClass(jni, "net/neoforged/forgia/runtimeproof/RuntimeProof");
    JNINativeMethod method = {"describe", "(Ljava/lang/Class;)[B", (void *)evidence_snapshot};
    if (!bridge || (*jni)->RegisterNatives(jni, bridge, &method, 1) != 0)
        (*jni)->FatalError(jni, "Evidence test bridge registration failed");
}
static void JNICALL evidence_prepare(jvmtiEnv *env, JNIEnv *jni, jthread thread, jclass actual) {
    original_prepare_module(env, jni, thread, actual);
    char *name = NULL;
    if ((*env)->GetClassSignature(env, actual, &name, NULL) == JVMTI_ERROR_NONE && name) {
        if (strcmp(name, "LMalformedEvidenceProbe;") == 0) {
            JNINativeMethod method = {"configureSnapshot", "([BZ)V", (void *)configure_snapshot};
            if ((*jni)->RegisterNatives(jni, actual, &method, 1) != 0)
                (*jni)->FatalError(jni, "Evidence test fixture registration failed");
        }
        (*env)->Deallocate(env, (unsigned char *)name);
    }
}
JNIEXPORT jint JNICALL Agent_OnLoad(JavaVM *vm, char *options, void *reserved) {
    jint result = original_proof_agent_load(vm, options, reserved);
    if (result != JNI_OK) return result;
    jvmtiEventCallbacks callbacks = {0};
    callbacks.VMInit = &evidence_vm_init;
    callbacks.ClassFileLoadHook = &changed;
    callbacks.VMStart = &proof_vm_start;
    callbacks.FieldAccess = &proof_field_read;
    callbacks.ClassPrepare = &evidence_prepare;
    callbacks.FieldModification = &proof_field_write;
    callbacks.SingleStep = &proof_step;
    callbacks.Breakpoint = &proof_method_entry;
    callbacks.NativeMethodBind = &proof_native_bind;
    return (*proof_vm)->SetEventCallbacks(proof_vm, &callbacks, sizeof(callbacks)) == JVMTI_ERROR_NONE ? JNI_OK : JNI_ERR;
}
