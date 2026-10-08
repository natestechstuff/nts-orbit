package com.natestechstuff.jarvis;

import android.app.Application;

/** Checks the saved premium key once at process start, before any screen or service runs. */
public class OrbitApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        Pro.init(this);
    }
}
