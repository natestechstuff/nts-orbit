package com.natestechstuff.jarvis;

import android.content.Context;

/** JNI surface of the on-device brain (app/src/main/cpp). One native Engine per handle. */
final class LlamaBridge {
    interface PieceCallback { boolean onPiece(byte[] utf8); }   // return false to stop

    private static boolean loaded;
    private static String loadError;

    /** Loads libjarvis_llm.so and the best CPU backend for this phone. Returns null or an error. */
    static synchronized String init(Context c) {
        if (loaded) return null;
        if (loadError != null) return loadError;
        try {
            System.loadLibrary("jarvis_llm");
            nativeInit(c.getApplicationInfo().nativeLibraryDir);
            loaded = true;
            return null;
        } catch (Throwable t) {
            loadError = "on-device engine failed to start: " + t.getMessage();
            return loadError;
        }
    }

    static native void nativeInit(String nativeLibDir);
    static native long nativeCreate();
    static native void nativeDestroy(long h);
    static native String nativeLoad(long h, String path, int nCtx, int nThreads);
    static native void nativeUnload(long h);
    static native String nativeDescribe(long h);
    static native void nativeStop(long h);
    /** returns {promptTokens, reusedTokens, genTokens, promptMs, genMs, stopped, hitLimit, droppedTurns} */
    static native double[] nativeGenerate(long h, String[] roles, String[] contents, int maxTokens,
                                          float temp, int topK, float topP, float minP, float repeatPenalty,
                                          PieceCallback cb, String[] errOut);

    // Pro only (Pro menu): batch size, GGUF metadata, repeat window + seed (seed < 0 = random)
    static native String nativeLoad2(long h, String path, int nCtx, int nThreads, int nBatch);
    static native String nativeMeta(long h);
    static native double[] nativeGenerate2(long h, String[] roles, String[] contents, int maxTokens,
                                           float temp, int topK, float topP, float minP, float repeatPenalty,
                                           int repeatLastN, long seed, PieceCallback cb, String[] errOut);

    private LlamaBridge() {}
}
