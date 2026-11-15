/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
/* Test-only variant. The production service exposes no JNI mutation helper. Compile this translation
   unit instead of runtime_proof.c, plus the same access_enforcement.c, so the probe can enter the real
   intercepted JNIEnv table while the single startup agent keeps the supported profile. */
#define Agent_OnLoad original_proof_agent_load
#define prepare_module original_prepare_module
#include "runtime_proof.c"
#undef prepare_module
#undef Agent_OnLoad

static void JNICALL set_int_field(JNIEnv *jni, jclass caller, jobject target, jstring field_name, jint value) {
    (void)caller;
    if (!target || !field_name) return;
    const char *name = (*jni)->GetStringUTFChars(jni, field_name, NULL);
    if (!name || (*jni)->ExceptionCheck(jni)) return;
    jclass type = (*jni)->GetObjectClass(jni, target);
    jfieldID field = type ? (*jni)->GetFieldID(jni, type, name, "I") : NULL;
    if (field && !(*jni)->ExceptionCheck(jni)) (*jni)->SetIntField(jni, target, field, value);
    if (type) (*jni)->DeleteLocalRef(jni, type);
    (*jni)->ReleaseStringUTFChars(jni, field_name, name);
}

static void JNICALL invoke_void(JNIEnv *jni, jclass caller, jobject target, jstring method_name) {
    (void)caller;
    if (!target || !method_name) return;
    const char *name = (*jni)->GetStringUTFChars(jni, method_name, NULL);
    if (!name || (*jni)->ExceptionCheck(jni)) return;
    jclass type = (*jni)->GetObjectClass(jni, target);
    jmethodID method = type ? (*jni)->GetMethodID(jni, type, name, "()V") : NULL;
    if (method && !(*jni)->ExceptionCheck(jni)) (*jni)->CallVoidMethod(jni, target, method);
    if (type) (*jni)->DeleteLocalRef(jni, type);
    (*jni)->ReleaseStringUTFChars(jni, method_name, name);
}

static void JNICALL prepare_test(jvmtiEnv *env, JNIEnv *jni, jthread thread, jclass actual) {
    original_prepare_module(env, jni, thread, actual);
    char *name = NULL;
    if ((*env)->GetClassSignature(env, actual, &name, NULL) == JVMTI_ERROR_NONE && name) {
        if (strcmp(name, "LJniAccessProbe;") == 0) {
            JNINativeMethod methods[] = {
                {"setIntField", "(Ljava/lang/Object;Ljava/lang/String;I)V", (void *)set_int_field},
                {"invokeVoid", "(Ljava/lang/Object;Ljava/lang/String;)V", (void *)invoke_void}
            };
            if ((*jni)->RegisterNatives(jni, actual, methods, (jint)(sizeof(methods) / sizeof(methods[0]))) != 0)
                (*jni)->FatalError(jni, "Test JNI registration failed");
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
