// Isolated Qualcomm QNN / QAIRT integration boundary.
//
// IMPORTANT — what this does and does not prove:
//   * it only checks whether a QNN backend library can be dlopen()ed inside the
//     app process and whether the documented public entry point
//     QnnInterface_getProviders is exported by it;
//   * it NEVER initializes a QNN context, never compiles a graph and never
//     executes a single operator, so it can never claim Hexagon execution. The
//     Kotlin adapter maps a positive probe result to BackendStatus.AVAILABLE
//     (not INITIALIZED, not VERIFIED_EXECUTION);
//   * the Qualcomm runtime is proprietary. Nothing is linked against it and no
//     Qualcomm binary is part of this repository — see docs/QNN_HTP_SETUP.md.
//
// The backend library must be loaded with dlopen per Qualcomm's HTP backend
// guidance, which is what the probe does; a real integration would continue
// from the resolved function table.
#include <jni.h>

#include <dlfcn.h>

#include <string>

namespace {

enum ProbeCode : int {
    kProbeInvalidName = -1,
    kProbeLibraryNotFound = 0,
    kProbeLibraryLoadedButSymbolMissing = 1,
    kProbeLibraryLoadedAndSymbolFound = 2,
};

bool isPlainSoname(const std::string &name) {
    if (name.empty() || name.size() > 128) return false;
    if (name.find('/') != std::string::npos) return false;  // no path injection
    if (name.find("..") != std::string::npos) return false;
    if (name[0] == '.') return false;
    return name.size() > 3 && name.compare(name.size() - 3, 3, ".so") == 0;
}

bool isPlainSymbol(const std::string &name) {
    if (name.empty() || name.size() > 128) return false;
    for (char c : name) {
        const bool ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_';
        if (!ok) return false;
    }
    return true;
}

jobjectArray makeResult(JNIEnv *env, int code, const std::string &detail) {
    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray out = env->NewObjectArray(2, stringClass, nullptr);
    if (out == nullptr) return nullptr;
    const std::string codeStr = std::to_string(code);
    jstring codeJ = env->NewStringUTF(codeStr.c_str());
    jstring detailJ = env->NewStringUTF(detail.c_str());
    if (codeJ != nullptr) {
        env->SetObjectArrayElement(out, 0, codeJ);
        env->DeleteLocalRef(codeJ);
    }
    if (detailJ != nullptr) {
        env->SetObjectArrayElement(out, 1, detailJ);
        env->DeleteLocalRef(detailJ);
    }
    return out;
}

}  // namespace

extern "C" {

/**
 * Probes one shared library by soname. Returns a two-element String array:
 *   [0] numeric ProbeCode as decimal text
 *   [1] human-readable, truthful detail (never implies execution)
 */
JNIEXPORT jobjectArray JNICALL Java_com_slop4hexagon_app_nativebridge_QnnNativeProbe_nativeProbeLibrary(JNIEnv *env, jobject /*thiz*/, jstring libraryName, jstring symbolName) {
    if (libraryName == nullptr || symbolName == nullptr) {
        return makeResult(env, kProbeInvalidName, "library name and symbol name are required");
    }
    const char *libChars = env->GetStringUTFChars(libraryName, nullptr);
    if (libChars == nullptr) return makeResult(env, kProbeInvalidName, "could not read library name");
    const std::string lib(libChars);
    env->ReleaseStringUTFChars(libraryName, libChars);

    const char *symChars = env->GetStringUTFChars(symbolName, nullptr);
    if (symChars == nullptr) return makeResult(env, kProbeInvalidName, "could not read symbol name");
    const std::string sym(symChars);
    env->ReleaseStringUTFChars(symbolName, symChars);

    if (!isPlainSoname(lib)) {
        return makeResult(env, kProbeInvalidName, "refusing name that is not a plain .so soname: " + lib);
    }
    if (!isPlainSymbol(sym)) {
        return makeResult(env, kProbeInvalidName, "refusing symbol name with unexpected characters: " + sym);
    }

    void *handle = dlopen(lib.c_str(), RTLD_NOW | RTLD_LOCAL);
    if (handle == nullptr) {
        const char *err = dlerror();
        return makeResult(env, kProbeLibraryNotFound, "not loadable in this process (checked the app's native lib dir + system namespaces): " + std::string(err != nullptr ? err : "no dlerror detail"));
    }

    void *symbol = dlsym(handle, sym.c_str());
    const char *symErr = symbol == nullptr ? dlerror() : nullptr;
    // The probe intentionally does not keep the library loaded: holding QNN
    // handles without a full context would only obscure later real init.
    dlclose(handle);

    if (symbol == nullptr) {
        return makeResult(env, kProbeLibraryLoadedButSymbolMissing, "loaded " + lib + " but the entry point " + sym + " is missing" + (symErr != nullptr ? std::string(" (") + symErr + ")" : ""));
    }
    return makeResult(env, kProbeLibraryLoadedAndSymbolFound, "loaded " + lib + " and resolved exported symbol " + sym +
                                                                 " — runtime presence only; no QNN context was created and no operator was executed");
}

}  // extern "C"
