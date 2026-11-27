#include <jni.h>
#include <string>
#include "whisper.h"

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_example_swipecull_WhisperLib_nativeInit(JNIEnv *env, jclass, jstring path) {
    const char *p = env->GetStringUTFChars(path, nullptr);
    whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(p, cp);
    env->ReleaseStringUTFChars(path, p);
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_com_example_swipecull_WhisperLib_nativeFree(JNIEnv *, jclass, jlong handle) {
    if (handle != 0) whisper_free(reinterpret_cast<whisper_context *>(handle));
}

// Returns UTF-8 text: first line "lang<TAB>code", then one line per segment
// "t0<TAB>t1<TAB>noSpeechProb<TAB>text" (times in 10 ms units). "ERR<TAB>code" on failure.
JNIEXPORT jbyteArray JNICALL
Java_com_example_swipecull_WhisperLib_nativeTranscribe(
        JNIEnv *env, jclass, jlong handle, jfloatArray pcm, jstring lang, jint threads) {
    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    std::string out;

    const char *langc = env->GetStringUTFChars(lang, nullptr);
    std::string language(langc);
    env->ReleaseStringUTFChars(lang, langc);

    jsize n = env->GetArrayLength(pcm);
    jfloat *data = env->GetFloatArrayElements(pcm, nullptr);

    whisper_full_params wp = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wp.print_realtime = false;
    wp.print_progress = false;
    wp.print_timestamps = false;
    wp.print_special = false;
    wp.translate = false;
    wp.no_context = true;
    wp.single_segment = false;
    wp.language = language.c_str();
    wp.n_threads = threads;
    wp.greedy.best_of = 1;

    int rc = whisper_full(ctx, wp, data, n);
    env->ReleaseFloatArrayElements(pcm, data, JNI_ABORT);

    if (rc != 0) {
        out = "ERR\t" + std::to_string(rc) + "\n";
    } else {
        int lid = whisper_full_lang_id(ctx);
        out += "lang\t";
        out += whisper_lang_str(lid);
        out += "\n";
        int segs = whisper_full_n_segments(ctx);
        for (int i = 0; i < segs; i++) {
            std::string text = whisper_full_get_segment_text(ctx, i);
            for (auto &c : text) if (c == '\t' || c == '\n' || c == '\r') c = ' ';
            out += std::to_string(whisper_full_get_segment_t0(ctx, i)) + "\t";
            out += std::to_string(whisper_full_get_segment_t1(ctx, i)) + "\t";
            out += std::to_string(whisper_full_get_segment_no_speech_prob(ctx, i)) + "\t";
            out += text + "\n";
        }
    }

    jbyteArray result = env->NewByteArray(static_cast<jsize>(out.size()));
    env->SetByteArrayRegion(result, 0, static_cast<jsize>(out.size()),
                            reinterpret_cast<const jbyte *>(out.data()));
    return result;
}

}  // extern "C"
