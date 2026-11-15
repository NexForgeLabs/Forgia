/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
#include <jni.h>
#include <jvmti.h>
#include <dlfcn.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <pthread.h>

#define MAGIC 0x46524731
extern void JNICALL proof_field_read(jvmtiEnv *, JNIEnv *, jthread, jmethodID, jlocation, jclass, jobject, jfieldID);
extern void JNICALL proof_field_write(jvmtiEnv *, JNIEnv *, jthread, jmethodID, jlocation, jclass, jobject, jfieldID, char, jvalue);
extern void JNICALL proof_method_entry(jvmtiEnv *, JNIEnv *, jthread, jmethodID, jlocation);
extern void JNICALL proof_step(jvmtiEnv *, JNIEnv *, jthread, jmethodID, jlocation);
extern void JNICALL proof_native_bind(jvmtiEnv *, JNIEnv *, jthread, jmethodID, void *, void **);
extern void JNICALL proof_vm_start(jvmtiEnv *, JNIEnv *);
extern void proof_access_load(jvmtiEnv *);
extern void proof_access_init(jvmtiEnv *, JNIEnv *);
extern jboolean JNICALL proof_protect(JNIEnv *, jclass, jobject, jobject);
extern jboolean JNICALL proof_freeze(JNIEnv *, jclass, jobject);
extern jboolean JNICALL proof_access_ready(JNIEnv *, jclass);
extern jboolean JNICALL proof_begin_reservation(JNIEnv *, jclass);
extern void JNICALL proof_end_reservation(JNIEnv *, jclass);
#define LIMIT (4 * 1024 * 1024)
static jvmtiEnv *proof_vm;
static atomic_llong definition_epoch = 1;
static atomic_int healthy;
static jobject bridge_module;
#define MAX_DEFINITIONS 2048
#define MAX_DEFINITION_BYTES (32 * 1024 * 1024)
static struct { jweak loader; int bootstrap; char *name; unsigned char *bytes; jint length; } named_definitions[MAX_DEFINITIONS];
static int named_count;
static size_t named_bytes;
static pthread_mutex_t definition_monitor = PTHREAD_MUTEX_INITIALIZER;
static int core_name(const char *name) {
    if (!name) return 0;
    if (strncmp(name, "net/neoforged/neoforge/server/threading/", strlen("net/neoforged/neoforge/server/threading/")) == 0
            || (strncmp(name, "net/neoforged/neoforge/server/timings/TimeTracker", strlen("net/neoforged/neoforge/server/timings/TimeTracker")) == 0
                    && (!name[strlen("net/neoforged/neoforge/server/timings/TimeTracker")] || name[strlen("net/neoforged/neoforge/server/timings/TimeTracker")] == '$'))) return 1;
    const char *classes[] = {"net/minecraft/world/level/chunk/LevelChunk", "net/minecraft/world/level/Level", "net/minecraft/server/level/ServerLevel", "net/minecraft/world/level/block/entity/BlockEntity", "net/minecraft/world/level/block/entity/BlockEntityType", "net/minecraft/world/level/storage/PrimaryLevelData", "net/minecraft/world/level/border/WorldBorder", "net/minecraft/world/level/block/state/StateHolder", "net/minecraft/world/level/block/state/BlockBehaviour", "net/minecraft/core/BlockPos", "net/minecraft/core/Vec3i", "net/minecraft/world/level/block/state/BlockState", "net/minecraft/world/level/chunk/ChunkAccess", "net/minecraft/server/MinecraftServer", "net/minecraft/server/level/ChunkHolder", "net/minecraft/server/level/GenerationChunkHolder", "net/minecraft/server/level/ChunkLevel", "net/minecraft/util/profiling/InactiveProfiler", "net/minecraft/util/profiling/ProfilerFiller", "net/minecraft/resources/ResourceLocation"};
    for (size_t index = 0; index < sizeof(classes) / sizeof(classes[0]); ++index) {
        size_t length = strlen(classes[index]);
        if (strncmp(name, classes[index], length) == 0 && (!name[length] || name[length] == '$')) return 1;
    }
    return 0;
}
static void record_definition(JNIEnv *jni, jobject loader, const char *name, jint length, const unsigned char *bytes) {
    if (!core_name(name)) return;
    pthread_mutex_lock(&definition_monitor);
    for (int index = 0; index < named_count; ++index) {
        if (strcmp(named_definitions[index].name, name) == 0 && ((loader == NULL && named_definitions[index].bootstrap)
                || (loader && (*jni)->IsSameObject(jni, loader, named_definitions[index].loader)))) {
            fprintf(stderr, "FORGIA_VM_DUPLICATE_DEFINITION %s\n", name);
            atomic_store(&healthy, 0); atomic_fetch_add(&definition_epoch, 1); pthread_mutex_unlock(&definition_monitor); return;
        }
    }
    if (length <= 0 || length > LIMIT || named_count == MAX_DEFINITIONS || (size_t)length > MAX_DEFINITION_BYTES - named_bytes) {
        atomic_store(&healthy, 0); pthread_mutex_unlock(&definition_monitor); return;
    }
    size_t name_length = strlen(name) + 1;
    unsigned char *copy = malloc((size_t)length); char *name_copy = malloc(name_length);
    if (!copy || !name_copy) { free(copy); free(name_copy); atomic_store(&healthy, 0); pthread_mutex_unlock(&definition_monitor); return; }
    memcpy(copy, bytes, (size_t)length); memcpy(name_copy, name, name_length);
    named_definitions[named_count].loader = loader ? (*jni)->NewWeakGlobalRef(jni, loader) : NULL;
    named_definitions[named_count].bootstrap = loader == NULL;
    named_definitions[named_count].name = name_copy; named_definitions[named_count].bytes = copy;
    named_definitions[named_count++].length = length; named_bytes += (size_t)length;
    pthread_mutex_unlock(&definition_monitor);
}
static jbyteArray JNICALL definition_bytes(JNIEnv *jni, jclass caller, jclass actual) {
    (void)caller;
    if (!actual || !atomic_load(&healthy)) return NULL;
    jobject loader = NULL; char *signature = NULL;
    if ((*proof_vm)->GetClassLoader(proof_vm, actual, &loader) != 0 || (*proof_vm)->GetClassSignature(proof_vm, actual, &signature, NULL) != 0) return NULL;
    jbyteArray result = NULL;
    if (signature && signature[0] == 'L') {
        signature[strlen(signature) - 1] = 0;
        pthread_mutex_lock(&definition_monitor);
        for (int index = 0; index < named_count; ++index) {
            if (strcmp(named_definitions[index].name, signature + 1) || !((!loader && named_definitions[index].bootstrap)
                    || (loader && (*jni)->IsSameObject(jni, loader, named_definitions[index].loader)))) continue;
            result = (*jni)->NewByteArray(jni, named_definitions[index].length);
            if (result) (*jni)->SetByteArrayRegion(jni, result, 0, named_definitions[index].length, (jbyte *)named_definitions[index].bytes);
            break;
        }
        pthread_mutex_unlock(&definition_monitor);
    }
    if (signature) (*proof_vm)->Deallocate(proof_vm, (unsigned char *)signature);
    if (loader) (*jni)->DeleteLocalRef(jni, loader);
    return result;
}
static jobjectArray JNICALL known_core_types(JNIEnv *jni, jclass caller, jclass anchor) {
    (void)caller;
    if (!anchor || !atomic_load(&healthy)) return NULL;
    jobject loader = NULL;
    if ((*proof_vm)->GetClassLoader(proof_vm, anchor, &loader) != 0) return NULL;
    jint count = 0; jclass *classes = NULL;
    if ((*proof_vm)->GetClassLoaderClasses(proof_vm, loader, &count, &classes) != 0 || count > 131072) return NULL;
    jclass *selected = malloc(MAX_DEFINITIONS * sizeof(jclass));
    int size = 0;
    if (selected) for (int index = 0; index < count; ++index) {
        char *name = NULL;
        if ((*proof_vm)->GetClassSignature(proof_vm, classes[index], &name, NULL) != 0 || !name) continue;
        size_t length = strlen(name);
        if (length > 2 && name[0] == 'L') { name[length - 1] = 0; if (core_name(name + 1)) {
            if (size == MAX_DEFINITIONS) { size = -1; (*proof_vm)->Deallocate(proof_vm, (unsigned char *)name); break; }
            selected[size++] = classes[index];
        } }
        (*proof_vm)->Deallocate(proof_vm, (unsigned char *)name);
    }
    jobjectArray result = NULL;
    if (selected && size >= 0) {
        jclass class_type = (*jni)->FindClass(jni, "java/lang/Class");
        result = (*jni)->NewObjectArray(jni, size, class_type, NULL);
        if (result) for (int index = 0; index < size; ++index) (*jni)->SetObjectArrayElement(jni, result, index, selected[index]);
    }
    free(selected); (*proof_vm)->Deallocate(proof_vm, (unsigned char *)classes);
    if (loader) (*jni)->DeleteLocalRef(jni, loader);
    return result;
}
static void JNICALL prepare_module(jvmtiEnv *env, JNIEnv *jni, jthread thread, jclass actual) {
    (void)thread;
    if (!bridge_module || !atomic_load(&healthy)) return;
    char *signature = NULL;
    if ((*env)->GetClassSignature(env, actual, &signature, NULL) != 0 || !signature) { atomic_store(&healthy, 0); return; }
    int needs_bridge = strncmp(signature, "Lnet/neoforged/", 15) == 0 || strncmp(signature, "Lnet/minecraft/", 15) == 0;
    (*env)->Deallocate(env, (unsigned char *)signature);
    if (!needs_bridge) return;
    jobject module = (*jni)->GetModule(jni, actual);
    if ((*jni)->ExceptionCheck(jni)) { atomic_store(&healthy, 0); return; }
    if (module) {
        jvmtiError result = (*env)->AddModuleReads(env, module, bridge_module);
        if (result != 0) { fprintf(stderr, "FORGIA_VM_MODULE_REFUSAL error=%d\n", result); atomic_store(&healthy, 0); }
    }
    if (module) (*jni)->DeleteLocalRef(jni, module);
}
static _Thread_local int snapshot_thread;
static unsigned char *snapshot(unsigned char *out, const void *data, int length, int *used) {
    if (length < 0 || *used > LIMIT - length) return NULL;
    if (length) memcpy(out + *used, data, (size_t)length);
    *used += length;
    return out;
}
static int integer(unsigned char *out, int value, int *used) {
    unsigned char data[4] = {(unsigned char)((uint32_t)value >> 24), (unsigned char)((uint32_t)value >> 16), (unsigned char)((uint32_t)value >> 8), (unsigned char)value};
    return snapshot(out, data, 4, used) != NULL;
}
static int utf(unsigned char *out, const char *value, int *used) {
    size_t length = strlen(value);
    if (length > 65535) return 0;
    unsigned char size[2] = {(unsigned char)(length >> 8), (unsigned char)length};
    return snapshot(out, size, 2, used) && snapshot(out, value, (int)length, used);
}
static void release(void *value) { if (value) (*proof_vm)->Deallocate(proof_vm, value); }
static void JNICALL changed(jvmtiEnv *env, JNIEnv *jni, jclass redefining, jobject loader,
        const char *name, jobject domain, jint length, const unsigned char *bytes,
        jint *new_length, unsigned char **new_bytes) {
    (void)env; (void)domain;
    // This supported runtime excludes native FFI downcalls. Prevent the indispensable entry-point
    // class from linking at all; this also covers reflective construction without --enable-preview.
    // Returning an invalid definition is a refusal before its initializer/native stub can execute.
    if (name && strcmp(name, "jdk/internal/foreign/abi/NativeEntryPoint") == 0) {
        unsigned char *refused = NULL;
        if ((*proof_vm)->Allocate(proof_vm, 1, &refused) != 0) { (*jni)->FatalError(jni, "Cannot refuse unsupported native FFI"); return; }
        refused[0] = 0; *new_length = 1; *new_bytes = refused;
        return;
    }
    if (!redefining) record_definition(jni, loader, name, length, bytes);
    if (redefining) {
        atomic_store(&healthy, 0);
        atomic_fetch_add(&definition_epoch, 1);
        // Refuse installation itself. A running original invocation cannot be made safe by merely
        // noticing a new revision after it calls into newly redefined code.
        unsigned char *refused = NULL;
        if ((*proof_vm)->Allocate(proof_vm, 1, &refused) != 0) { (*jni)->FatalError(jni, "Cannot refuse runtime code replacement"); return; }
        refused[0] = 0; *new_length = 1; *new_bytes = refused;
    }
}
static jint JNICALL native_ready(JNIEnv *jni, jclass caller) {
    (void)jni; (void)caller;
    return atomic_load(&healthy) ? MAGIC : 0;
}
static jlong JNICALL native_epoch(JNIEnv *jni, jclass caller) {
    (void)jni; (void)caller;
    return atomic_load(&definition_epoch);
}
static jbyteArray JNICALL describe(JNIEnv *jni, jclass caller, jclass actual) {
    (void)caller;
    if (!atomic_load(&healthy) || !actual || snapshot_thread) return NULL;
    /* The VM supplies the bytecodes and constant-pool references for this actual Class, including
       hidden definitions. The optional service has no path accepting caller-supplied code evidence. */
    snapshot_thread = 1;
    unsigned char *out = malloc(LIMIT), *pool = NULL;
    jmethodID *methods = NULL; jfieldID *fields = NULL;
    int used = 0, okay = out != NULL;
    jint pool_count = 0, pool_length = 0, method_count = 0, field_count = 0, class_status = 0;
    if ((*proof_vm)->GetClassStatus(proof_vm, actual, &class_status) != 0) okay = 0;
    jlong epoch = atomic_load(&definition_epoch);
    if ((*proof_vm)->GetConstantPool(proof_vm, actual, &pool_count, &pool_length, &pool) != 0) okay = 0;
    if ((*proof_vm)->GetClassMethods(proof_vm, actual, &method_count, &methods) != 0 || method_count > 4096) okay = 0;
    if ((*proof_vm)->GetClassFields(proof_vm, actual, &field_count, &fields) != 0 || field_count > 4096) okay = 0;
    if (okay) okay = integer(out, MAGIC, &used) && integer(out, (int)(epoch >> 32), &used) && integer(out, (int)epoch, &used)
            && integer(out, class_status, &used) && integer(out, pool_count, &used) && integer(out, pool_length, &used) && snapshot(out, pool, pool_length, &used)
            && integer(out, method_count, &used);
    typedef jint (JNICALL *count_fn)(JNIEnv *, jclass);
    typedef const char *(JNICALL *text_fn)(JNIEnv *, jclass, jint);
    typedef jint (JNICALL *handlers_fn)(JNIEnv *, jclass, jint);
    typedef void (JNICALL *release_utf_fn)(const char *);
    release_utf_fn vm_release_utf = (release_utf_fn)dlsym(RTLD_DEFAULT, "JVM_ReleaseUTF");
    count_fn vm_count = (count_fn)dlsym(RTLD_DEFAULT, "JVM_GetClassMethodsCount");
    text_fn vm_name = (text_fn)dlsym(RTLD_DEFAULT, "JVM_GetMethodIxNameUTF");
    text_fn vm_descriptor = (text_fn)dlsym(RTLD_DEFAULT, "JVM_GetMethodIxSignatureUTF");
    handlers_fn vm_handlers = (handlers_fn)dlsym(RTLD_DEFAULT, "JVM_GetMethodIxExceptionTableLength");
    if (!vm_count || !vm_name || !vm_descriptor || !vm_handlers || !vm_release_utf) okay = 0;
    for (int i = 0; okay && i < method_count; i++) {
        char *name = NULL, *descriptor = NULL; unsigned char *code = NULL; jint access = 0, length = 0;
        okay = (*proof_vm)->GetMethodName(proof_vm, methods[i], &name, &descriptor, NULL) == 0
                && (*proof_vm)->GetMethodModifiers(proof_vm, methods[i], &access) == 0;
        if (okay && !(access & (0x0100 | 0x0400))) okay = (*proof_vm)->GetBytecodes(proof_vm, methods[i], &length, &code) == 0;
        int handlers = -1;
        for (int index = 0; okay && index < vm_count(jni, actual); index++) {
            const char *candidate_name = vm_name(jni, actual, index);
            const char *candidate_descriptor = vm_descriptor(jni, actual, index);
            int matches = strcmp(name, candidate_name) == 0 && strcmp(descriptor, candidate_descriptor) == 0;
            vm_release_utf(candidate_name); vm_release_utf(candidate_descriptor);
            if (matches) {
                if (handlers != -1) { okay = 0; break; }
                handlers = vm_handlers(jni, actual, index);
            }
        }
        if (okay) okay = handlers >= 0 && integer(out, access, &used) && utf(out, name, &used) && utf(out, descriptor, &used)
                && integer(out, length, &used) && snapshot(out, code, length, &used) && integer(out, handlers, &used);
        release(name); release(descriptor); release(code);
    }
    if (okay) okay = integer(out, field_count, &used);
    for (int i = 0; okay && i < field_count; i++) {
        char *name = NULL, *descriptor = NULL; jint access = 0;
        okay = (*proof_vm)->GetFieldName(proof_vm, actual, fields[i], &name, &descriptor, NULL) == 0
                && (*proof_vm)->GetFieldModifiers(proof_vm, actual, fields[i], &access) == 0;
        if (okay) okay = integer(out, access, &used) && utf(out, name, &used) && utf(out, descriptor, &used);
        release(name); release(descriptor);
    }
    jbyteArray result = NULL;
    if (okay && atomic_load(&healthy) && epoch == atomic_load(&definition_epoch)) {
        result = (*jni)->NewByteArray(jni, used);
        if (result) (*jni)->SetByteArrayRegion(jni, result, 0, used, (jbyte *)out);
    }
    release(pool); release(methods); release(fields); free(out); snapshot_thread = 0;
    return result;
}
static jclass JNICALL find_loaded(JNIEnv *jni, jclass caller, jclass anchor, jstring requested) {
    (void)caller;
    if (!atomic_load(&healthy) || !anchor || !requested) return NULL;
    jobject loader = NULL;
    if ((*proof_vm)->GetClassLoader(proof_vm, anchor, &loader) != 0) return NULL;
    const char *name = (*jni)->GetStringUTFChars(jni, requested, NULL);
    if (!name) return NULL;
    jint count = 0; jclass *classes = NULL; jclass result = NULL;
    if ((*proof_vm)->GetClassLoaderClasses(proof_vm, loader, &count, &classes) == 0) {
        size_t length = strlen(name);
        if (count > 131072) count = 0;
        for (int i = 0; i < count; i++) {
            char *signature = NULL;
            if ((*proof_vm)->GetClassSignature(proof_vm, classes[i], &signature, NULL) == 0 && signature) {
                if (strlen(signature) == length + 2 && signature[0] == 'L' && signature[length + 1] == ';'
                        && memcmp(signature + 1, name, length) == 0) {
                    if (result) { result = NULL; release(signature); break; }
                    result = classes[i];
                }
                release(signature);
            }
        }
    }
    (*jni)->ReleaseStringUTFChars(jni, requested, name); release(classes);
    if (loader) (*jni)->DeleteLocalRef(jni, loader);
    return result;
}
static void JNICALL vm_init(jvmtiEnv *env, JNIEnv *jni, jthread thread) {
    (void)env; (void)thread;
    jclass module_type = (*jni)->FindClass(jni, "java/lang/Module");
    jmethodID initialize_modules = module_type ? (*jni)->GetMethodID(jni, module_type, "canRead", "(Ljava/lang/Module;)Z") : NULL;
    if (!initialize_modules) return;
    jclass bridge = (*jni)->FindClass(jni, "net/neoforged/forgia/runtimeproof/RuntimeProof");
    if (!bridge) return;
    bridge_module = (*jni)->NewGlobalRef(jni, (*jni)->GetModule(jni, bridge));
    JNINativeMethod methods[] = {
        {"ready", "()I", (void *)native_ready}, {"epoch", "()J", (void *)native_epoch},
        {"describe", "(Ljava/lang/Class;)[B", (void *)describe},
        {"findLoaded", "(Ljava/lang/Class;Ljava/lang/String;)Ljava/lang/Class;", (void *)find_loaded},
        {"protectRoot", "(Ljava/lang/Object;Lnet/neoforged/forgia/runtimeproof/RuntimeProof$AccessCheck;)Z", (void *)proof_protect},
        {"freezeRoot", "(Ljava/lang/Object;)Z", (void *)proof_freeze},
        {"enforcementReady", "()Z", (void *)proof_access_ready},
        {"beginReservation", "()Z", (void *)proof_begin_reservation},
        {"endReservation", "()V", (void *)proof_end_reservation},
        {"definitionBytes", "(Ljava/lang/Class;)[B", (void *)definition_bytes},
        {"knownCoreTypes", "(Ljava/lang/Class;)[Ljava/lang/Class;", (void *)known_core_types}
    };
    /* Every declared bridge method must be published; a silently omitted entry turns the
       corresponding proof into an UnsatisfiedLinkError at the first admission attempt. */
    if ((*jni)->RegisterNatives(jni, bridge, methods, (jint)(sizeof(methods) / sizeof(methods[0]))) != 0) {
        fprintf(stderr, "FORGIA_VM_NATIVE_PUBLICATION_FAILED\n");
        atomic_store(&healthy, 0);
        return;
    }
    proof_access_init(env, jni);
    fprintf(stderr, "FORGIA_VM_INITIALIZED healthy=%d\n", atomic_load(&healthy));
}
JNIEXPORT jint JNICALL Agent_OnLoad(JavaVM *vm, char *options, void *reserved) {
    (void)reserved;
    if (!options || (*vm)->GetEnv(vm, (void **)&proof_vm, JVMTI_VERSION_1_2) != JNI_OK) return JNI_ERR;
    if ((*proof_vm)->AddToBootstrapClassLoaderSearch(proof_vm, options) != 0) return JNI_ERR;
    jvmtiCapabilities caps = {0}; caps.can_get_bytecodes = 1; caps.can_get_constant_pool = 1; caps.can_generate_all_class_hook_events = 1;
    caps.can_generate_field_access_events = 1; caps.can_generate_field_modification_events = 1;
    caps.can_generate_single_step_events = 1; caps.can_generate_native_method_bind_events = 1;
    caps.can_access_local_variables = 1; caps.can_tag_objects = 1; caps.can_generate_breakpoint_events = 1;
    caps.can_retransform_classes = 1; caps.can_redefine_classes = 1;
    if ((*proof_vm)->AddCapabilities(proof_vm, &caps) != 0) return JNI_ERR;
    proof_access_load(proof_vm);
    jvmtiEventCallbacks callbacks = {0}; callbacks.VMInit = &vm_init; callbacks.ClassFileLoadHook = &changed;
    callbacks.VMStart = &proof_vm_start; callbacks.FieldAccess = &proof_field_read; callbacks.ClassPrepare = &prepare_module;
    callbacks.FieldModification = &proof_field_write; callbacks.SingleStep = &proof_step; callbacks.Breakpoint = &proof_method_entry;
    callbacks.NativeMethodBind = &proof_native_bind;
    if ((*proof_vm)->SetEventCallbacks(proof_vm, &callbacks, sizeof(callbacks)) != 0) return JNI_ERR;
    if ((*proof_vm)->SetEventNotificationMode(proof_vm, JVMTI_ENABLE, JVMTI_EVENT_CLASS_PREPARE, NULL) != 0) return JNI_ERR;
    if ((*proof_vm)->SetEventNotificationMode(proof_vm, JVMTI_ENABLE, JVMTI_EVENT_VM_START, NULL) != 0) return JNI_ERR;
    if ((*proof_vm)->SetEventNotificationMode(proof_vm, JVMTI_ENABLE, JVMTI_EVENT_NATIVE_METHOD_BIND, NULL) != 0) return JNI_ERR;
    if ((*proof_vm)->SetEventNotificationMode(proof_vm, JVMTI_ENABLE, JVMTI_EVENT_BREAKPOINT, NULL) != 0) return JNI_ERR;
    if ((*proof_vm)->SetEventNotificationMode(proof_vm, JVMTI_ENABLE, JVMTI_EVENT_FIELD_ACCESS, NULL) != 0) return JNI_ERR;
    if ((*proof_vm)->SetEventNotificationMode(proof_vm, JVMTI_ENABLE, JVMTI_EVENT_FIELD_MODIFICATION, NULL) != 0) return JNI_ERR;
    if ((*proof_vm)->SetEventNotificationMode(proof_vm, JVMTI_ENABLE, JVMTI_EVENT_VM_INIT, NULL) != 0) return JNI_ERR;
    if ((*proof_vm)->SetEventNotificationMode(proof_vm, JVMTI_ENABLE, JVMTI_EVENT_CLASS_FILE_LOAD_HOOK, NULL) != 0) return JNI_ERR;
    atomic_store(&healthy, 1);
    return JNI_OK;
}
