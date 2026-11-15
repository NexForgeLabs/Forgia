/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
#define _GNU_SOURCE
#include <jni.h>
#include <jvmti.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <limits.h>
#include <unistd.h>
#include <dlfcn.h>
#include <sys/mman.h>

static jlong JNICALL refused_native_downcall(JNIEnv *jni, jclass owner, jobject type, jobject abi, jobject arguments,
        jobject returns, jboolean transition, jint captured, jboolean heap_access) {
    (void)owner; (void)type; (void)abi; (void)arguments; (void)returns; (void)transition; (void)captured; (void)heap_access;
    jclass denied = (*jni)->FindClass(jni, "java/lang/IllegalStateException");
    (*jni)->ThrowNew(jni, denied, "Native downcalls are unsupported in the verified runtime");
    return 0;
}

static jstring (JNICALL *original_diagnostic_command)(JNIEnv *, jobject, jstring);
static jstring JNICALL guarded_diagnostic_command(JNIEnv *jni, jobject self, jstring command) {
    if (!command) return NULL;
    const char *text = (*jni)->GetStringUTFChars(jni, command, NULL);
    if (!text) return NULL;
    const char *start = text;
    while (*start == ' ' || *start == '\t') ++start;
    size_t token = strcspn(start, " \t\r\n");
    const char *reads[] = {"help", "VM.version", "VM.command_line", "VM.flags", "VM.info", "Thread.print", "GC.class_histogram", "GC.heap_info"};
    int allowed = !strchr(start, '\n') && !strchr(start, '\r') && strlen(start) <= 8192;
    int known = 0;
    for (size_t index = 0; index < sizeof(reads) / sizeof(reads[0]); ++index)
        if (strlen(reads[index]) == token && strncmp(start, reads[index], token) == 0) known = 1;
    jstring stable = allowed && known ? (*jni)->NewStringUTF(jni, start) : NULL;
    (*jni)->ReleaseStringUTFChars(jni, command, text);
    if (!allowed || !known) {
        jclass denied = (*jni)->FindClass(jni, "java/lang/IllegalStateException");
        (*jni)->ThrowNew(jni, denied, "Unsupported diagnostic command in the verified runtime");
        return NULL;
    }
    return stable ? original_diagnostic_command(jni, self, stable) : NULL;
}

static char trusted_java_lib[PATH_MAX];
static jboolean (JNICALL *original_library_load)(JNIEnv *, jclass, jobject, jstring, jboolean, jboolean);
static jboolean JNICALL guarded_library_load(JNIEnv *jni, jclass caller, jobject library, jstring name, jboolean builtin, jboolean throw_exception) {
    if (!name) return JNI_FALSE;
    const char *requested = (*jni)->GetStringUTFChars(jni, name, NULL);
    if (!requested) return JNI_FALSE;
    char actual[PATH_MAX]; int allowed = 0;
    if (!builtin && realpath(requested, actual) && trusted_java_lib[0]) {
        const char *names[] = {"libjava.so", "libnet.so", "libnio.so", "libzip.so", "libmanagement.so", "libmanagement_ext.so", "libjimage.so", "libverify.so", "libsyslookup.so", "libjsvml.so"};
        for (size_t index = 0; index < sizeof(names) / sizeof(names[0]); ++index) {
            char expected[PATH_MAX]; int length = snprintf(expected, sizeof(expected), "%s/%s", trusted_java_lib, names[index]);
            if (length > 0 && length < PATH_MAX && strcmp(actual, expected) == 0) { allowed = 1; break; }
        }
    }
    jstring stable = allowed ? (*jni)->NewStringUTF(jni, actual) : NULL;
    (*jni)->ReleaseStringUTFChars(jni, name, requested);
    /* Denied before the unknown library's JNI_OnLoad, not after native code executes. */
    return stable ? original_library_load(jni, caller, library, stable, builtin, throw_exception) : JNI_FALSE;
}

/* Interception metadata only. The callback consults the original Core authority; this table cannot
   reserve, release, transfer or grant ownership of any root. Weak keys never keep an unloaded BE alive. */
#define MAX_ROOTS 4096
#define MAX_EARLY 4096
static jvmtiEnv *access_vm;
static pthread_mutex_t access_monitor;
static struct { jweak root; jobject check; int frozen; } roots[MAX_ROOTS];
static int root_count;
static atomic_int access_ready;
static jclass refusal_class;
static jmethodID allowed_method;
static int active_accesses;
static _Thread_local int checking;
static _Thread_local int pending_field;
static _Thread_local int reservation_monitor;
static int publishing_reservation;
#define MAX_ALLOCATIONS 16384
static struct { uintptr_t base; jlong size; } allocations[MAX_ALLOCATIONS];
static jlong (JNICALL *unsafe_allocate_memory)(JNIEnv *, jobject, jlong);
static jlong (JNICALL *unsafe_reallocate_memory)(JNIEnv *, jobject, jlong, jlong);
static jint reference_width = 4;
static jlong thread_id_address;
static jmethodID thread_id_next;
static int original_thread_id_access(JNIEnv *jni, jlong offset, jlong length) {
    (void)jni;
    // The VM returns this exact native address; it is not a heap object or an author-supplied pointer.
    // Only its 8-byte primitive access is permitted. It cannot address a claimed source root.
    return thread_id_address && offset == thread_id_address && length == 8;
}
static void (JNICALL *unsafe_free_memory)(JNIEnv *, jobject, jlong);

static int tracked_memory(JNIEnv *jni, jobject target, jlong offset, jlong length) {
    if (!root_count) return 1;
    if (length < 0) return 0;
    if (target) {
        jlong size = 0;
        return offset >= 0 && (*access_vm)->GetObjectSize(access_vm, target, &size) == 0
                && offset <= size && length <= size - offset;
    }
    uintptr_t address = (uintptr_t)offset;
    int known = 0;
    pthread_mutex_lock(&access_monitor);
    for (int index = 0; index < MAX_ALLOCATIONS; ++index) {
        if (allocations[index].base && address >= allocations[index].base) {
            uintptr_t displacement = address - allocations[index].base;
            if (displacement <= (uint64_t)allocations[index].size && (uint64_t)length <= (uint64_t)allocations[index].size - displacement) { known = 1; break; }
        }
    }
    pthread_mutex_unlock(&access_monitor);
    (void)jni;
    return known;
}
static int trusted_image_read(jlong offset, jlong length) {
    if (!trusted_java_lib[0] || length < 0) return 0;
    FILE *maps = fopen("/proc/self/maps", "r");
    if (!maps) return 0;
    char line[8192], path[PATH_MAX], permissions[5], expected[PATH_MAX];
    int expected_length = snprintf(expected, sizeof(expected), "%s/modules", trusted_java_lib);
    if (expected_length < 0 || expected_length >= PATH_MAX) { fclose(maps); return 0; }
    unsigned long long start, end;
    int found = 0;
    while (fgets(line, sizeof(line), maps)) {
        path[0] = 0;
        if (sscanf(line, "%llx-%llx %4s %*s %*s %*s %4095[^\n]", &start, &end, permissions, path) != 4) continue;
        if (permissions[0] != 'r' || permissions[1] != '-' || strcmp(path, expected) != 0) continue;
        uint64_t address = (uint64_t)offset;
        if (address >= start && address <= end && (uint64_t)length <= end - address) { found = 1; break; }
    }
    fclose(maps);
    return found;
}
static int memory_access(JNIEnv *jni, jobject target, jlong offset, jlong length, int write);
static void record_allocation(uintptr_t old_address, uintptr_t address, jlong length) {
    pthread_mutex_lock(&access_monitor);
    int slot = -1;
    for (int index = 0; index < MAX_ALLOCATIONS; ++index) {
        if (!old_address && address && allocations[index].base == address && allocations[index].size == length) {
            pthread_mutex_unlock(&access_monitor); return;
        }
        if (old_address && allocations[index].base == old_address) { allocations[index].base = 0; allocations[index].size = 0; }
        if (!allocations[index].base) slot = index;
    }
    if (address && slot >= 0) { allocations[slot].base = address; allocations[slot].size = length; }
    else if (address) atomic_store(&access_ready, 0);
    pthread_mutex_unlock(&access_monitor);
}
static jobject (JNICALL *original_perf_long)(JNIEnv *, jobject, jstring, jint, jint, jlong);
static jobject (JNICALL *original_perf_bytes)(JNIEnv *, jobject, jstring, jint, jint, jbyteArray, jint);
static jobject (JNICALL *original_perf_attach)(JNIEnv *, jobject, jint);
static jobject record_native_buffer(JNIEnv *jni, jobject buffer) {
    if (buffer) {
        void *address = (*jni)->GetDirectBufferAddress(jni, buffer);
        jlong size = (*jni)->GetDirectBufferCapacity(jni, buffer);
        if (!address || size < 0) atomic_store(&access_ready, 0);
        else record_allocation(0, (uintptr_t)address, size);
    }
    return buffer;
}
static jobject JNICALL guarded_perf_long(JNIEnv *jni, jobject self, jstring name, jint variability, jint unit, jlong value) {
    return record_native_buffer(jni, original_perf_long(jni, self, name, variability, unit, value));
}
static jobject JNICALL guarded_perf_bytes(JNIEnv *jni, jobject self, jstring name, jint variability, jint unit, jbyteArray value, jint length) {
    return record_native_buffer(jni, original_perf_bytes(jni, self, name, variability, unit, value, length));
}
static jobject JNICALL guarded_perf_attach(JNIEnv *jni, jobject self, jint pid) {
    return record_native_buffer(jni, original_perf_attach(jni, self, pid));
}
static jlong JNICALL guarded_allocate_memory(JNIEnv *jni, jobject self, jlong length) {
    jlong address = unsafe_allocate_memory(jni, self, length);
    if (address) record_allocation(0, (uintptr_t)address, length);
    return address;
}
static jlong JNICALL guarded_reallocate_memory(JNIEnv *jni, jobject self, jlong old_address, jlong length) {
    if (!tracked_memory(jni, NULL, old_address, 0)) { (*jni)->ThrowNew(jni, refusal_class, "Unverified native allocation"); return 0; }
    jlong address = unsafe_reallocate_memory(jni, self, old_address, length);
    if (address || !length) record_allocation((uintptr_t)old_address, (uintptr_t)address, length);
    return address;
}
static void JNICALL guarded_free_memory(JNIEnv *jni, jobject self, jlong address) {
    if (!tracked_memory(jni, NULL, address, 0)) { (*jni)->ThrowNew(jni, refusal_class, "Unverified native allocation"); return; }
    record_allocation((uintptr_t)address, 0, 0);
    unsafe_free_memory(jni, self, address);
}
static struct JNINativeInterface_ original_jni;
static jmethodID early_methods[MAX_EARLY];
static void *early_addresses[MAX_EARLY];
static int early_count, vm_started, rebinding;
static jmethodID guarded_methods[256];
static void *guarded_addresses[256];
static int guarded_count;

static atomic_int denied_diagnostics;
static void denied_access_trace(JNIEnv *jni, jobject target, int write) {
    if (atomic_fetch_add(&denied_diagnostics, 1) >= 16 || (*jni)->ExceptionCheck(jni)) return;
    jclass type = (*jni)->GetObjectClass(jni, target); char *name = NULL;
    (*access_vm)->GetClassSignature(access_vm, type, &name, NULL);
    fprintf(stderr, "FORGIA_VM_ROOT_REFUSAL type=%s write=%d\n", name ? name : "?", write);
    if (name) (*access_vm)->Deallocate(access_vm, (unsigned char *)name);
    (*jni)->DeleteLocalRef(jni, type);
    jthread thread = NULL; jvmtiFrameInfo frames[12]; jint count = 0;
    if ((*access_vm)->GetCurrentThread(access_vm, &thread) == 0
            && (*access_vm)->GetStackTrace(access_vm, thread, 0, 12, frames, &count) == 0) {
        for (int index = 0; index < count; ++index) {
            char *method = NULL, *owner = NULL; jclass declaring = NULL;
            (*access_vm)->GetMethodName(access_vm, frames[index].method, &method, NULL, NULL);
            (*access_vm)->GetMethodDeclaringClass(access_vm, frames[index].method, &declaring);
            (*access_vm)->GetClassSignature(access_vm, declaring, &owner, NULL);
            fprintf(stderr, "FORGIA_VM_ROOT_FRAME %s.%s\n", owner ? owner : "?", method ? method : "?");
            if (method) (*access_vm)->Deallocate(access_vm, (unsigned char *)method);
            if (owner) (*access_vm)->Deallocate(access_vm, (unsigned char *)owner);
            if (declaring) (*jni)->DeleteLocalRef(jni, declaring);
        }
    }
    if (thread) (*jni)->DeleteLocalRef(jni, thread);
}
static void end_access(int token);
static int begin_access(JNIEnv *jni, jobject target, int write) {
    if (!target) return 2;
    pthread_mutex_lock(&access_monitor);
    jobject check = NULL;
    int matched = 0, frozen = 0;
    for (int index = 0; index < root_count; ++index) {
        if (roots[index].root && (*jni)->IsSameObject(jni, roots[index].root, target)) {
            matched = 1;
            frozen = roots[index].frozen;
            if (!frozen) check = (*jni)->NewLocalRef(jni, roots[index].check);
            break;
        }
    }
    if (frozen) {
        pthread_mutex_unlock(&access_monitor);
        if (write) {
            denied_access_trace(jni, target, write);
            (*jni)->ThrowNew(jni, refusal_class, "Frozen original value cannot be mutated");
            return 0;
        }
        return 2;
    }
    if (checking) {
        pthread_mutex_unlock(&access_monitor);
        if (check) (*jni)->DeleteLocalRef(jni, check);
        if (matched) {
            (*jni)->ThrowNew(jni, refusal_class, "Access callbacks cannot enter a protected mutable root");
            return 0;
        }
        return 2;
    }
    int allow = (!matched || check) && (!check || !publishing_reservation || reservation_monitor);
    if (publishing_reservation && check && !reservation_monitor) allow = 0;
    if (allow && check) ++active_accesses;
    pthread_mutex_unlock(&access_monitor);
    if (!check && allow) return 2;
    if (allow && check) {
        checking = 1;
        allow = atomic_load(&access_ready) && (*jni)->CallBooleanMethod(jni, check, allowed_method, target, write ? JNI_TRUE : JNI_FALSE);
        checking = 0;
        if ((*jni)->ExceptionCheck(jni)) allow = 0;
        if (!allow) end_access(1);
    }
    if (check) (*jni)->DeleteLocalRef(jni, check);
    if (!allow && !(*jni)->ExceptionCheck(jni)) {
        denied_access_trace(jni, target, write);
        (*jni)->ThrowNew(jni, refusal_class, "Original object access has no current Core authority");
    }
    return allow ? 1 : 0;
}
static int memory_access(JNIEnv *jni, jobject target, jlong offset, jlong length, int write) {
    if (!tracked_memory(jni, target, offset, length)
            && !(!target && original_thread_id_access(jni, offset, length))
            && !(!target && !write && trusted_image_read(offset, length))) {
        char *signature = NULL; jlong size = -1;
        if (target) { jclass klass = (*jni)->GetObjectClass(jni, target); (*access_vm)->GetClassSignature(access_vm, klass, &signature, NULL); (*access_vm)->GetObjectSize(access_vm, target, &size); }
        fprintf(stderr, "FORGIA_VM_ACCESS_REFUSAL type=%s offset=%lld length=%lld size=%lld write=%d\n", signature ? signature : "native", (long long)offset, (long long)length, (long long)size, write);
        if (signature) (*access_vm)->Deallocate(access_vm, (unsigned char *)signature);
        jthread current = NULL; jvmtiFrameInfo frames[12]; jint frame_count = 0;
        if ((*access_vm)->GetCurrentThread(access_vm, &current) == 0
                && (*access_vm)->GetStackTrace(access_vm, current, 0, 12, frames, &frame_count) == 0) {
            for (int index = 0; index < frame_count; ++index) {
                char *method = NULL; char *owner = NULL; jclass declaring = NULL;
                (*access_vm)->GetMethodName(access_vm, frames[index].method, &method, NULL, NULL);
                (*access_vm)->GetMethodDeclaringClass(access_vm, frames[index].method, &declaring);
                (*access_vm)->GetClassSignature(access_vm, declaring, &owner, NULL);
                fprintf(stderr, "FORGIA_VM_ACCESS_FRAME %s.%s\n", owner ? owner : "?", method ? method : "?");
                if (method) (*access_vm)->Deallocate(access_vm, (unsigned char *)method);
                if (owner) (*access_vm)->Deallocate(access_vm, (unsigned char *)owner);
                if (declaring) (*jni)->DeleteLocalRef(jni, declaring);
            }
        }
        if (current) (*jni)->DeleteLocalRef(jni, current);
        (*jni)->ThrowNew(jni, refusal_class, "Unsafe access has no bounded object or native allocation");
        return 0;
    }
    if (write && target && root_count) {
        jclass type = (*jni)->GetObjectClass(jni, target);
        jboolean array = JNI_FALSE;
        jvmtiError classified = (*access_vm)->IsArrayClass(access_vm, type, &array);
        (*jni)->DeleteLocalRef(jni, type);
        if (classified != 0 || offset < (array ? 16 : 12)) {
            (*jni)->ThrowNew(jni, refusal_class, "Unsafe object or array-header mutation is outside the supported runtime");
            return 0;
        }
    }
    return begin_access(jni, target, write);
}
static void end_access(int token) {
    if (token != 1) return;
    pthread_mutex_lock(&access_monitor);
    if (--active_accesses < 0) abort();
    pthread_mutex_unlock(&access_monitor);
}
static void field_event(JNIEnv *jni, jthread thread, jobject target, int write) {
    if (!target) return;
    jlong tag = 0;
    pthread_mutex_lock(&access_monitor);
    jvmtiError tagged = (*access_vm)->GetTag(access_vm, target, &tag);
    pthread_mutex_unlock(&access_monitor);
    if (tagged != 0) { (*jni)->ThrowNew(jni, refusal_class, "Original field identity is unavailable"); return; }
    if (tag <= 0) return;
    if (pending_field) { atomic_store(&access_ready, 0); (*jni)->FatalError(jni, "Unfinished original field instruction"); return; }
    int token = begin_access(jni, target, write);
    if (token == 1) {
        pending_field = 1;
        if ((*access_vm)->SetEventNotificationMode(access_vm, JVMTI_ENABLE, JVMTI_EVENT_SINGLE_STEP, thread) != 0)
            (*jni)->FatalError(jni, "Original field access lifetime is unavailable");
    }
}
void JNICALL proof_method_entry(jvmtiEnv *env, JNIEnv *jni, jthread thread, jmethodID method, jlocation location) {
    (void)method; (void)location;
    if (!root_count) return;
    jobject receiver = NULL; jlong tag = 0;
    if ((*env)->GetLocalInstance(env, thread, 0, &receiver) != 0 || !receiver) {
        (*jni)->ThrowNew(jni, refusal_class, "Protected method receiver is unavailable");
        return;
    }
    if ((*env)->GetTag(env, receiver, &tag) != 0) {
        (*jni)->ThrowNew(jni, refusal_class, "Protected method tag is unavailable");
        (*jni)->DeleteLocalRef(jni, receiver);
        return;
    }
    if (tag > 0) {
        int token = begin_access(jni, receiver, 0);
        end_access(token);
    }
    (*jni)->DeleteLocalRef(jni, receiver);
}
/* Read-only accessors are called for every block entity of the level, not only for a protected
   root. A breakpoint on them would make the whole server pay for one Mod root. They cannot cause an
   earlier effect or a store, and the semantic verifier refuses a source class that declares them, so
   the inherited Core implementations stay outside the method-entry boundary. Writes remain covered by
   the field watches; wrapper.tick, rebind and every source method are still guarded. */
static int read_only_entry(const char *name) {
    static const char *names[] = {"getPos", "getBlockPos", "getType", "isRemoved", "getLevel", "getBlockState",
                                  "hasLevel", "hasAttachments", "getDelegate", "toString", "hashCode", "equals"};
    for (size_t index = 0; index < sizeof(names) / sizeof(names[0]); ++index)
        if (strcmp(name, names[index]) == 0) return 1;
    return 0;
}
static int protect_method_entries(JNIEnv *jni, jclass klass, int depth) {
    if (depth > 16) return 0;
    jint count = 0; jmethodID *methods = NULL;
    if ((*access_vm)->GetClassMethods(access_vm, klass, &count, &methods) != 0) return 0;
    int okay = 1;
    for (int index = 0; index < count; ++index) {
        jint access = 0; char *name = NULL;
        if ((*access_vm)->GetMethodModifiers(access_vm, methods[index], &access) != 0) { okay = 0; break; }
        if (access & (0x0008 | 0x0100 | 0x0400)) continue;
        if ((*access_vm)->GetMethodName(access_vm, methods[index], &name, NULL, NULL) != 0) { okay = 0; break; }
        if (name[0] != '<' && !read_only_entry(name)) {
            jvmtiError result = (*access_vm)->SetBreakpoint(access_vm, methods[index], 0);
            if (result != 0 && result != JVMTI_ERROR_DUPLICATE) okay = 0;
        }
        (*access_vm)->Deallocate(access_vm, (unsigned char *)name);
    }
    if (methods) (*access_vm)->Deallocate(access_vm, (unsigned char *)methods);
    jint interfaces_count = 0; jclass *interfaces = NULL;
    if ((*access_vm)->GetImplementedInterfaces(access_vm, klass, &interfaces_count, &interfaces) != 0 || interfaces_count > 256) okay = 0;
    for (int index = 0; okay && index < interfaces_count; ++index) okay = protect_method_entries(jni, interfaces[index], depth + 1);
    if (interfaces) (*access_vm)->Deallocate(access_vm, (unsigned char *)interfaces);
    (void)jni;
    return okay;
}
void JNICALL proof_field_read(jvmtiEnv *env, JNIEnv *jni, jthread thread, jmethodID method, jlocation location, jclass klass, jobject object, jfieldID field) {
    (void)env; (void)method; (void)location; (void)klass; (void)field; field_event(jni, thread, object, 0);
}
void JNICALL proof_field_write(jvmtiEnv *env, JNIEnv *jni, jthread thread, jmethodID method, jlocation location, jclass klass, jobject object, jfieldID field, char type, jvalue value) {
    (void)env; (void)method; (void)location; (void)klass; (void)field; (void)type; (void)value; field_event(jni, thread, object, 1);
}
void JNICALL proof_step(jvmtiEnv *env, JNIEnv *jni, jthread thread, jmethodID method, jlocation location) {
    (void)jni; (void)method; (void)location;
    if (pending_field) { pending_field = 0; end_access(1); (*env)->SetEventNotificationMode(env, JVMTI_DISABLE, JVMTI_EVENT_SINGLE_STEP, thread); }
}

#define SIMPLE_ACCESS(Type, Name, JniName, Descriptor) \
static Type (JNICALL *unsafe_get_##Name)(JNIEnv *, jobject, jobject, jlong); \
static void (JNICALL *unsafe_put_##Name)(JNIEnv *, jobject, jobject, jlong, Type); \
static Type (JNICALL *unsafe_get_##Name##Volatile)(JNIEnv *, jobject, jobject, jlong); \
static void (JNICALL *unsafe_put_##Name##Volatile)(JNIEnv *, jobject, jobject, jlong, Type); \
static Type JNICALL guarded_get_##Name(JNIEnv *jni, jobject self, jobject target, jlong offset) { \
    int token = memory_access(jni, target, offset, Descriptor[0] == 'L' ? reference_width : (jlong)sizeof(Type), 0); if (!token) return (Type)0; Type value = unsafe_get_##Name(jni, self, target, offset); end_access(token); return value; } \
static void JNICALL guarded_put_##Name(JNIEnv *jni, jobject self, jobject target, jlong offset, Type value) { \
    int token = memory_access(jni, target, offset, Descriptor[0] == 'L' ? reference_width : (jlong)sizeof(Type), 1); if (token) { unsafe_put_##Name(jni, self, target, offset, value); end_access(token); } } \
static Type JNICALL guarded_get_##Name##Volatile(JNIEnv *jni, jobject self, jobject target, jlong offset) { \
    int token = memory_access(jni, target, offset, Descriptor[0] == 'L' ? reference_width : (jlong)sizeof(Type), 0); if (!token) return (Type)0; Type value = unsafe_get_##Name##Volatile(jni, self, target, offset); end_access(token); return value; } \
static void JNICALL guarded_put_##Name##Volatile(JNIEnv *jni, jobject self, jobject target, jlong offset, Type value) { \
    int token = memory_access(jni, target, offset, Descriptor[0] == 'L' ? reference_width : (jlong)sizeof(Type), 1); if (token) { unsafe_put_##Name##Volatile(jni, self, target, offset, value); end_access(token); } } \
static Type JNICALL jni_get_##Name(JNIEnv *jni, jobject target, jfieldID field) { \
    int token = begin_access(jni, target, 0); if (!token) return (Type)0; Type value = original_jni.Get##JniName##Field(jni, target, field); end_access(token); return value; } \
static void JNICALL jni_put_##Name(JNIEnv *jni, jobject target, jfieldID field, Type value) { \
    int token = begin_access(jni, target, 1); if (token) { original_jni.Set##JniName##Field(jni, target, field, value); end_access(token); } }
SIMPLE_ACCESS(jboolean, Boolean, Boolean, "Z")
SIMPLE_ACCESS(jbyte, Byte, Byte, "B")
SIMPLE_ACCESS(jshort, Short, Short, "S")
SIMPLE_ACCESS(jchar, Char, Char, "C")
SIMPLE_ACCESS(jint, Int, Int, "I")
SIMPLE_ACCESS(jlong, Long, Long, "J")
SIMPLE_ACCESS(jfloat, Float, Float, "F")
SIMPLE_ACCESS(jdouble, Double, Double, "D")
SIMPLE_ACCESS(jobject, Reference, Object, "Ljava/lang/Object;")

#define ATOMIC_ACCESS(Type, Name, Zero) \
static jboolean (JNICALL *unsafe_cas_##Name)(JNIEnv *, jobject, jobject, jlong, Type, Type); \
static Type (JNICALL *unsafe_exchange_##Name)(JNIEnv *, jobject, jobject, jlong, Type, Type); \
static jboolean JNICALL guarded_cas_##Name(JNIEnv *jni, jobject self, jobject target, jlong offset, Type before, Type after) { \
    int token = memory_access(jni, target, offset, strcmp(#Name, "Reference") == 0 ? reference_width : (jlong)sizeof(Type), 1); if (!token) return JNI_FALSE; jboolean result = unsafe_cas_##Name(jni, self, target, offset, before, after); end_access(token); return result; } \
static Type JNICALL guarded_exchange_##Name(JNIEnv *jni, jobject self, jobject target, jlong offset, Type before, Type after) { \
    int token = memory_access(jni, target, offset, strcmp(#Name, "Reference") == 0 ? reference_width : (jlong)sizeof(Type), 1); if (!token) return Zero; Type result = unsafe_exchange_##Name(jni, self, target, offset, before, after); end_access(token); return result; }
ATOMIC_ACCESS(jint, Int, 0)
ATOMIC_ACCESS(jlong, Long, 0)
ATOMIC_ACCESS(jobject, Reference, NULL)

static void (JNICALL *unsafe_set_memory)(JNIEnv *, jobject, jobject, jlong, jlong, jbyte);
static void (JNICALL *unsafe_copy_memory)(JNIEnv *, jobject, jobject, jlong, jobject, jlong, jlong);
static void (JNICALL *unsafe_copy_swap)(JNIEnv *, jobject, jobject, jlong, jobject, jlong, jlong, jlong);
static void JNICALL guarded_set_memory(JNIEnv *jni, jobject self, jobject target, jlong offset, jlong length, jbyte value) {
    int token = memory_access(jni, target, offset, length, 1);
    if (token) { unsafe_set_memory(jni, self, target, offset, length, value); end_access(token); }
}
static void JNICALL guarded_copy_memory(JNIEnv *jni, jobject self, jobject source, jlong source_offset, jobject target, jlong target_offset, jlong length) {
    int read = memory_access(jni, source, source_offset, length, 0); if (!read) return;
    int write = memory_access(jni, target, target_offset, length, 1);
    if (write) { unsafe_copy_memory(jni, self, source, source_offset, target, target_offset, length); end_access(write); }
    end_access(read);
}
static void JNICALL guarded_copy_swap(JNIEnv *jni, jobject self, jobject source, jlong source_offset, jobject target, jlong target_offset, jlong length, jlong element_size) {
    int read = memory_access(jni, source, source_offset, length, 0); if (!read) return;
    int write = memory_access(jni, target, target_offset, length, 1);
    if (write) { unsafe_copy_swap(jni, self, source, source_offset, target, target_offset, length, element_size); end_access(write); }
    end_access(read);
}

static void *replacement_for(JNIEnv *jni, jmethodID method, void *address) {
    char *name = NULL, *desc = NULL, *class_name = NULL; jclass klass = NULL; void *result = NULL;
    if ((*access_vm)->GetMethodName(access_vm, method, &name, &desc, NULL) != 0) return NULL;
    (*access_vm)->GetMethodDeclaringClass(access_vm, method, &klass); (*access_vm)->GetClassSignature(access_vm, klass, &class_name, NULL);
    int observed = 0;
    for (int index = 0; index < guarded_count; ++index) {
        if (guarded_methods[index] != method) continue;
        observed = 1;
        if (guarded_addresses[index] != address) {
            atomic_store(&access_ready, 0);
            if (root_count) (*jni)->FatalError(jni, "A guarded native method changed while original roots exist");
            goto finished_binding;
        }
    }
    if (class_name && strcmp(class_name, "Ljdk/internal/perf/Perf;") == 0) {
        if (strcmp(name, "createLong") == 0) { original_perf_long = address; result = (void *)guarded_perf_long; }
        if (strcmp(name, "createByteArray") == 0) { original_perf_bytes = address; result = (void *)guarded_perf_bytes; }
        if (strcmp(name, "attach0") == 0) { original_perf_attach = address; result = (void *)guarded_perf_attach; }
    }
    if (class_name && strcmp(class_name, "Ljdk/internal/foreign/abi/NativeEntryPoint;") == 0 && strcmp(name, "makeDowncallStub") == 0) {
        result = (void *)refused_native_downcall;
    }
    if (class_name && strcmp(class_name, "Lcom/sun/management/internal/DiagnosticCommandImpl;") == 0
            && strcmp(name, "executeDiagnosticCommand") == 0) {
        original_diagnostic_command = address; result = (void *)guarded_diagnostic_command;
    }
    if (class_name && strcmp(class_name, "Ljdk/internal/loader/NativeLibraries;") == 0 && strcmp(name, "load") == 0) {
        original_library_load = address; result = (void *)guarded_library_load;
    }
    if (class_name && strcmp(class_name, "Ljdk/internal/misc/Unsafe;") == 0) {
#define BIND_SIMPLE(Type, Name, JniName, Descriptor) \
        if (strcmp(name, "get" #Name) == 0) { unsafe_get_##Name = address; result = (void *)guarded_get_##Name; } \
        if (strcmp(name, "put" #Name) == 0) { unsafe_put_##Name = address; result = (void *)guarded_put_##Name; } \
        if (strcmp(name, "get" #Name "Volatile") == 0) { unsafe_get_##Name##Volatile = address; result = (void *)guarded_get_##Name##Volatile; } \
        if (strcmp(name, "put" #Name "Volatile") == 0) { unsafe_put_##Name##Volatile = address; result = (void *)guarded_put_##Name##Volatile; }
        BIND_SIMPLE(jboolean, Boolean, Boolean, "Z") BIND_SIMPLE(jbyte, Byte, Byte, "B") BIND_SIMPLE(jshort, Short, Short, "S")
        BIND_SIMPLE(jchar, Char, Char, "C") BIND_SIMPLE(jint, Int, Int, "I") BIND_SIMPLE(jlong, Long, Long, "J")
        BIND_SIMPLE(jfloat, Float, Float, "F") BIND_SIMPLE(jdouble, Double, Double, "D") BIND_SIMPLE(jobject, Reference, Object, "Ljava/lang/Object;")
#define BIND_ATOMIC(Name) \
        if (strcmp(name, "compareAndSet" #Name) == 0) { unsafe_cas_##Name = address; result = (void *)guarded_cas_##Name; } \
        if (strcmp(name, "compareAndExchange" #Name) == 0) { unsafe_exchange_##Name = address; result = (void *)guarded_exchange_##Name; }
        BIND_ATOMIC(Int) BIND_ATOMIC(Long) BIND_ATOMIC(Reference)
        if (strcmp(name, "setMemory0") == 0) { unsafe_set_memory = address; result = (void *)guarded_set_memory; }
        if (strcmp(name, "copyMemory0") == 0) { unsafe_copy_memory = address; result = (void *)guarded_copy_memory; }
        if (strcmp(name, "copySwapMemory0") == 0) { unsafe_copy_swap = address; result = (void *)guarded_copy_swap; }
        if (strcmp(name, "allocateMemory0") == 0) { unsafe_allocate_memory = address; result = (void *)guarded_allocate_memory; }
        if (strcmp(name, "reallocateMemory0") == 0) { unsafe_reallocate_memory = address; result = (void *)guarded_reallocate_memory; }
        if (strcmp(name, "freeMemory0") == 0) { unsafe_free_memory = address; result = (void *)guarded_free_memory; }
    }
    if (result && !observed) {
        if (guarded_count == 256) { atomic_store(&access_ready, 0); result = NULL; }
        else { guarded_methods[guarded_count] = method; guarded_addresses[guarded_count++] = address; }
    }
finished_binding:
    if (name) (*access_vm)->Deallocate(access_vm, (unsigned char *)name);
    if (desc) (*access_vm)->Deallocate(access_vm, (unsigned char *)desc);
    if (class_name) (*access_vm)->Deallocate(access_vm, (unsigned char *)class_name);
    (void)jni; return result;
}
void JNICALL proof_native_bind(jvmtiEnv *env, JNIEnv *jni, jthread thread, jmethodID method, void *address, void **replacement) {
    (void)env; (void)thread;
    if (rebinding) return;
    if (!vm_started) {
        if (early_count == MAX_EARLY) { atomic_store(&access_ready, 0); (*jni)->FatalError(jni, "Native observation overflow"); return; }
        early_methods[early_count] = method; early_addresses[early_count++] = address; return;
    }
    void *guard = replacement_for(jni, method, address);
    if (guard) *replacement = guard;
}
void JNICALL proof_vm_start(jvmtiEnv *env, JNIEnv *jni) {
    (void)env; vm_started = 1; rebinding = 1;
    for (int index = 0; index < early_count; ++index) {
        void *guard = replacement_for(jni, early_methods[index], early_addresses[index]);
        if (!guard) continue;
        char *name = NULL, *descriptor = NULL; jclass klass = NULL;
        (*access_vm)->GetMethodName(access_vm, early_methods[index], &name, &descriptor, NULL);
        (*access_vm)->GetMethodDeclaringClass(access_vm, early_methods[index], &klass);
        JNINativeMethod method = {name, descriptor, guard};
        if ((*jni)->RegisterNatives(jni, klass, &method, 1) != 0) (*jni)->FatalError(jni, "Original Unsafe guard unavailable");
        (*access_vm)->Deallocate(access_vm, (unsigned char *)name); (*access_vm)->Deallocate(access_vm, (unsigned char *)descriptor);
    }
    rebinding = 0;
}
void proof_access_init(jvmtiEnv *env, JNIEnv *jni) {
    access_vm = env;
    jclass object_class = (*jni)->FindClass(jni, "java/lang/Object");
    jobjectArray empty = (*jni)->NewObjectArray(jni, 0, object_class, NULL);
    jobjectArray sixteen = (*jni)->NewObjectArray(jni, 16, object_class, NULL);
    jlong empty_size = 0, array_size = 0;
    if (!empty || !sixteen || (*env)->GetObjectSize(env, empty, &empty_size) != 0
            || (*env)->GetObjectSize(env, sixteen, &array_size) != 0) return;
    reference_width = (jint)((array_size - empty_size) / 16);
    if (reference_width != 4 && reference_width != 8) return;
    typedef jlong (JNICALL *next_id_offset)(JNIEnv *, jclass);
    next_id_offset address = (next_id_offset)dlsym(RTLD_DEFAULT, "JVM_GetNextThreadIdOffset");
    jclass thread_type = (*jni)->FindClass(jni, "java/lang/Thread");
    jclass identifiers = (*jni)->FindClass(jni, "java/lang/Thread$ThreadIdentifiers");
    if (!address || !thread_type || !identifiers) return;
    thread_id_address = address(jni, thread_type);
    thread_id_next = (*jni)->GetStaticMethodID(jni, identifiers, "next", "()J");
    if (!thread_id_next || !thread_id_address) return;
    jclass check = (*jni)->FindClass(jni, "net/neoforged/forgia/runtimeproof/RuntimeProof$AccessCheck");
    refusal_class = (*jni)->NewGlobalRef(jni, (*jni)->FindClass(jni, "java/lang/IllegalStateException"));
    allowed_method = check ? (*jni)->GetMethodID(jni, check, "allowed", "(Ljava/lang/Object;Z)Z") : NULL;
    struct JNINativeInterface_ *table = NULL;
    if (!allowed_method || (*env)->GetJNIFunctionTable(env, &table) != 0) return;
    original_jni = *table;
#define JNI_GUARD(Name, JniName) table->Get##JniName##Field = jni_get_##Name; table->Set##JniName##Field = jni_put_##Name;
    JNI_GUARD(Boolean, Boolean) JNI_GUARD(Byte, Byte) JNI_GUARD(Short, Short) JNI_GUARD(Char, Char)
    JNI_GUARD(Int, Int) JNI_GUARD(Long, Long) JNI_GUARD(Float, Float) JNI_GUARD(Double, Double) JNI_GUARD(Reference, Object)
    if ((*env)->SetJNIFunctionTable(env, table) == 0) atomic_store(&access_ready, 1);
    (*env)->Deallocate(env, (unsigned char *)table);
}
void proof_access_load(jvmtiEnv *env) {
    access_vm = env;
    char *java_home = NULL;
    if ((*env)->GetSystemProperty(env, "java.home", &java_home) == 0 && java_home) {
        char candidate[PATH_MAX];
        int length = snprintf(candidate, sizeof(candidate), "%s/lib", java_home);
        if (length > 0 && length < PATH_MAX) realpath(candidate, trusted_java_lib);
        (*env)->Deallocate(env, (unsigned char *)java_home);
    }
    pthread_mutexattr_t attributes; pthread_mutexattr_init(&attributes); pthread_mutexattr_settype(&attributes, PTHREAD_MUTEX_RECURSIVE);
    pthread_mutex_init(&access_monitor, &attributes); pthread_mutexattr_destroy(&attributes);
}
static jboolean register_root(JNIEnv *jni, jclass caller, jobject target, jobject check, int frozen) {
    (void)caller;
    jvmtiEnv *env = access_vm;
    if (!target || (!check && !frozen) || !atomic_load(&access_ready)) { fprintf(stderr, "FORGIA_VM_PROTECT_REFUSAL initial ready=%d\n", atomic_load(&access_ready)); return JNI_FALSE; }
    pthread_mutex_lock(&access_monitor);
    int slot = -1;
    for (int index = 0; index < root_count; ++index) {
        if ((*jni)->IsSameObject(jni, roots[index].root, target)) {
            int same = roots[index].frozen == frozen && (frozen || (*jni)->IsSameObject(jni, roots[index].check, check));
            pthread_mutex_unlock(&access_monitor); return same ? JNI_TRUE : JNI_FALSE;
        }
        if ((*jni)->IsSameObject(jni, roots[index].root, NULL)) {
            (*jni)->DeleteWeakGlobalRef(jni, roots[index].root); (*jni)->DeleteGlobalRef(jni, roots[index].check);
            roots[index].root = NULL; roots[index].check = NULL; roots[index].frozen = 0; slot = index;
        }
    }
    if (slot < 0 && root_count < MAX_ROOTS) slot = root_count++;
    if (slot < 0 || active_accesses || pending_field) { fprintf(stderr, "FORGIA_VM_PROTECT_REFUSAL slot=%d active=%d pending=%d\n", slot, active_accesses, pending_field); pthread_mutex_unlock(&access_monitor); return JNI_FALSE; }
    int okay = 1;
    /* Watches cover every inherited instance field in the containing storage root. The authorized body
       must still have a verified closure; this is no proof about objects reachable through its fields. */
    for (jclass klass = (*jni)->GetObjectClass(jni, target); klass; klass = (*jni)->GetSuperclass(jni, klass)) {
        if (!frozen && !(*jni)->IsSameObject(jni, klass, (*jni)->FindClass(jni, "java/lang/Object")) && !protect_method_entries(jni, klass, 0)) okay = 0;
        jint count = 0; jfieldID *fields = NULL;
        if ((*env)->GetClassFields(env, klass, &count, &fields) != 0) { okay = 0; break; }
        for (int index = 0; index < count; ++index) {
            jint access = 0; (*env)->GetFieldModifiers(env, klass, fields[index], &access);
            if (access & 8) continue;
            jvmtiError read = frozen ? JVMTI_ERROR_NONE : (*env)->SetFieldAccessWatch(env, klass, fields[index]);
            jvmtiError write = (*env)->SetFieldModificationWatch(env, klass, fields[index]);
            if ((read && read != JVMTI_ERROR_DUPLICATE) || (write && write != JVMTI_ERROR_DUPLICATE)) { fprintf(stderr, "FORGIA_VM_PROTECT_REFUSAL watches read=%d write=%d\n", read, write); okay = 0; }
        }
        if (fields) (*env)->Deallocate(env, (unsigned char *)fields);
    }
    if (okay) {
        roots[slot].root = (*jni)->NewWeakGlobalRef(jni, target);
        roots[slot].check = frozen ? NULL : (*jni)->NewGlobalRef(jni, check);
        roots[slot].frozen = frozen;
        if (roots[slot].root && (frozen || roots[slot].check) && (*env)->SetTag(env, target, (jlong)(slot + 1)) != 0) okay = 0;
        if (!roots[slot].root || (!frozen && !roots[slot].check) || !okay) {
            if (roots[slot].root) (*jni)->DeleteWeakGlobalRef(jni, roots[slot].root);
            if (roots[slot].check) (*jni)->DeleteGlobalRef(jni, roots[slot].check);
            roots[slot].root = NULL; roots[slot].check = NULL; roots[slot].frozen = 0;
            okay = 0;
        }
    }
    pthread_mutex_unlock(&access_monitor);
    return okay ? JNI_TRUE : JNI_FALSE;
}
jboolean JNICALL proof_protect(JNIEnv *jni, jclass caller, jobject target, jobject check) {
    return register_root(jni, caller, target, check, 0);
}
jboolean JNICALL proof_freeze(JNIEnv *jni, jclass caller, jobject target) {
    return register_root(jni, caller, target, NULL, 1);
}
jboolean JNICALL proof_access_ready(JNIEnv *jni, jclass caller) { (void)jni; (void)caller; return atomic_load(&access_ready) ? JNI_TRUE : JNI_FALSE; }
jboolean JNICALL proof_begin_reservation(JNIEnv *jni, jclass caller) {
    (void)jni; (void)caller;
    if (reservation_monitor || !atomic_load(&access_ready)) return JNI_FALSE;
    pthread_mutex_lock(&access_monitor);
    if (active_accesses || publishing_reservation) {
        fprintf(stderr, "FORGIA_VM_RESERVATION_REFUSAL active=%d publishing=%d\n", active_accesses, publishing_reservation);
        pthread_mutex_unlock(&access_monitor); return JNI_FALSE;
    }
    publishing_reservation = 1;
    reservation_monitor = 1;
    pthread_mutex_unlock(&access_monitor);
    return JNI_TRUE;
}
void JNICALL proof_end_reservation(JNIEnv *jni, jclass caller) {
    (void)caller;
    if (!reservation_monitor) { (*jni)->FatalError(jni, "Foreign access-monitor completion"); return; }
    pthread_mutex_lock(&access_monitor);
    reservation_monitor = 0;
    publishing_reservation = 0;
    pthread_mutex_unlock(&access_monitor);
}
