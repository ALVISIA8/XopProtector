package com.yqsh.protector.shell;

import android.app.Application;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.Keep;
import androidx.annotation.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Replace ProxyApplication with the real Application via ActivityThread / LoadedApk,
 * matching dpt-shell's replaceApplicationOnLoadedApk flow.
 */
@Keep
public final class ApplicationReplacer {
    private static final String TAG = "protector.AppReplace";

    private ApplicationReplacer() {
    }

    /**
     * @return the single real Application instance, or null on failure
     */
    @Nullable
    public static Application replace(String realApplicationClassName) {
        if (TextUtils.isEmpty(realApplicationClassName)) {
            return null;
        }
        try {
            Object activityThread = currentActivityThread();
            if (activityThread == null) {
                Log.e(TAG, "ActivityThread is null");
                return null;
            }

            Object boundApp = getField(activityThread, "mBoundApplication");
            if (boundApp == null) {
                Log.e(TAG, "mBoundApplication is null");
                return null;
            }

            Object loadedApk = getField(boundApp, "info");
            if (loadedApk == null) {
                Log.e(TAG, "LoadedApk is null");
                return null;
            }

            // installContentProviders() may already be iterating AppBindData.providers.
            // Sophix/DRouter attach often mutates that list → ConcurrentModificationException.
            // Retarget the field to a fresh copy so mutations cannot touch the live iterator.
            detachProvidersList(boundApp);

            // Clear proxy Application so makeApplication creates a new one
            setField(loadedApk, "mApplication", null);

            Object allApps = getField(activityThread, "mAllApplications");
            if (allApps instanceof List) {
                @SuppressWarnings("unchecked")
                List<Object> list = (List<Object>) allApps;
                if (!list.isEmpty()) {
                    list.remove(0);
                    Log.i(TAG, "removed proxy from mAllApplications");
                }
            }

            // ApplicationInfo.className is a binary name (dot-separated) for ClassLoader.loadClass
            ApplicationInfo loadedAi = (ApplicationInfo) getField(loadedApk, "mApplicationInfo");
            ApplicationInfo bindAi = (ApplicationInfo) getField(boundApp, "appInfo");
            if (loadedAi != null) {
                loadedAi.className = realApplicationClassName;
            }
            if (bindAi != null) {
                bindAi.className = realApplicationClassName;
            }

            Method makeApplication = loadedApk.getClass().getDeclaredMethod(
                    "makeApplication", boolean.class, Class.forName("android.app.Instrumentation"));
            makeApplication.setAccessible(true);
            Object newApp = makeApplication.invoke(loadedApk, false, null);
            if (!(newApp instanceof Application)) {
                Log.e(TAG, "makeApplication returned null/non-Application");
                return null;
            }

            Application real = (Application) newApp;
            setField(activityThread, "mInitialApplication", real);

            Object allApps2 = getField(activityThread, "mAllApplications");
            if (allApps2 instanceof List) {
                @SuppressWarnings("unchecked")
                List<Object> list = (List<Object>) allApps2;
                if (!list.contains(real)) {
                    list.add(real);
                }
            }

            Log.i(TAG, "replaced with " + real.getClass().getName());
            return real;
        } catch (Throwable t) {
            Log.e(TAG, "replace failed", t);
            return null;
        }
    }

    /**
     * Re-pin system Application pointers to an already-created real Application.
     * <p>Early {@link #replace} runs inside Proxy {@code attachBaseContext}. After that
     * returns, the outer {@code LoadedApk.makeApplication} assigns {@code mApplication}
     * / {@code mInitialApplication} back to the Proxy. Call this from Proxy
     * {@code onCreate} (after outer makeApplication finished) so
     * {@code Activity.getApplication()} / {@code Context.getApplicationContext()}
     * resolve to the real Application — without creating a second instance.
     *
     * @return true if pointers were updated
     */
    public static boolean reattach(Application real) {
        if (real == null) {
            return false;
        }
        try {
            Object activityThread = currentActivityThread();
            if (activityThread == null) {
                Log.e(TAG, "reattach: ActivityThread is null");
                return false;
            }

            Object boundApp = getField(activityThread, "mBoundApplication");
            if (boundApp == null) {
                Log.e(TAG, "reattach: mBoundApplication is null");
                return false;
            }

            Object loadedApk = getField(boundApp, "info");
            if (loadedApk == null) {
                Log.e(TAG, "reattach: LoadedApk is null");
                return false;
            }

            setField(loadedApk, "mApplication", real);
            setField(activityThread, "mInitialApplication", real);

            Object allApps = getField(activityThread, "mAllApplications");
            if (allApps instanceof List) {
                @SuppressWarnings("unchecked")
                List<Object> list = (List<Object>) allApps;
                Iterator<Object> it = list.iterator();
                while (it.hasNext()) {
                    Object o = it.next();
                    if (!(o instanceof Application)) {
                        continue;
                    }
                    Application app = (Application) o;
                    if (app == real) {
                        continue;
                    }
                    String name = app.getClass().getName();
                    if (!name.startsWith("com.yqsh.protector.shell.")) {
                        continue;
                    }
                    // Point leftover Proxy ContextImpl at real before dropping it.
                    syncOuterContext(app, real);
                    it.remove();
                }
                if (!list.contains(real)) {
                    list.add(real);
                }
            }

            syncOuterContext(real, real);

            // ApplicationInfo.className may still say Proxy after packer rewrite;
            // keep it aligned with the live instance for any late framework reads.
            String realName = real.getClass().getName();
            ApplicationInfo loadedAi = (ApplicationInfo) getField(loadedApk, "mApplicationInfo");
            ApplicationInfo bindAi = (ApplicationInfo) getField(boundApp, "appInfo");
            if (loadedAi != null) {
                loadedAi.className = realName;
            }
            if (bindAi != null) {
                bindAi.className = realName;
            }

            Log.i(TAG, "reattached " + realName);
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "reattach failed", t);
            return false;
        }
    }

    /** Ensure ContextImpl.mOuterContext points at {@code real} (OEM / getApplicationContext). */
    private static void syncOuterContext(Application holder, Application real) {
        try {
            Context base = holder.getBaseContext();
            if (base != null) {
                setField(base, "mOuterContext", real);
            }
        } catch (Throwable t) {
            Log.w(TAG, "syncOuterContext failed for " + holder.getClass().getName(), t);
        }
    }

    /**
     * Point AppBindData.providers at a new ArrayList so in-flight enhanced-for
     * iteration (installContentProviders) keeps the old list identity.
     */
    private static void detachProvidersList(Object boundApp) {
        try {
            Object providers = getField(boundApp, "providers");
            if (!(providers instanceof List)) {
                return;
            }
            @SuppressWarnings("unchecked")
            List<Object> list = (List<Object>) providers;
            setField(boundApp, "providers", new ArrayList<>(list));
            Log.i(TAG, "detached providers list (size=" + list.size() + ")");
        } catch (Throwable t) {
            Log.w(TAG, "detach providers failed", t);
        }
    }

    /**
     * After providers were already installed under the replaced Application
     * (early {@code attachBaseContext} replace and/or createPackageContext path),
     * clear AppBindData.providers so Sophix {@code onCreate} does not call
     * {@code installContentProviders} again.
     * Re-install breaks InitProvider-style libs (e.g. AutoSize "can only be called once")
     * and Sophix then abandons before the business Application.onCreate runs.
     * <p>Safe only when invoked from Application.onCreate (after framework
     * Provider install). Do not call from attachBaseContext.
     */
    public static void markProvidersAlreadyInstalled() {
        try {
            Object activityThread = currentActivityThread();
            if (activityThread == null) {
                return;
            }
            Object boundApp = getField(activityThread, "mBoundApplication");
            if (boundApp == null) {
                return;
            }
            Object providers = getField(boundApp, "providers");
            int size = (providers instanceof List) ? ((List<?>) providers).size() : -1;
            // Idempotent: already empty means install finished or never scheduled.
            if (size == 0) {
                Log.i(TAG, "providers already empty before Application.onCreate");
                return;
            }
            setField(boundApp, "providers", new ArrayList<>());
            Log.i(TAG, "cleared providers before Application.onCreate (was size=" + size + ")");
        } catch (Throwable t) {
            Log.w(TAG, "markProvidersAlreadyInstalled failed", t);
        }
    }

    /**
     * Sophix may have already attached the business Application during stub attach.
     * Find that instance in {@code mAllApplications} (not proxy, not the stub itself).
     */
    @Nullable
    public static Application findBusinessApplication(@Nullable Application stubOrProxy) {
        try {
            Object activityThread = currentActivityThread();
            if (activityThread == null) {
                return null;
            }
            Object allApps = getField(activityThread, "mAllApplications");
            if (!(allApps instanceof List)) {
                return null;
            }
            @SuppressWarnings("unchecked")
            List<Object> list = (List<Object>) allApps;
            Application fallback = null;
            for (Object o : list) {
                if (!(o instanceof Application)) {
                    continue;
                }
                Application app = (Application) o;
                String name = app.getClass().getName();
                if (name.startsWith("com.yqsh.protector.shell.")) {
                    continue;
                }
                if (stubOrProxy != null && app == stubOrProxy) {
                    continue;
                }
                // Prefer non-Sophix stub (business Application).
                if (name.contains("SophixStub") || name.endsWith("SophixApplication")) {
                    if (fallback == null) {
                        fallback = app;
                    }
                    continue;
                }
                return app;
            }
            return fallback;
        } catch (Throwable t) {
            Log.w(TAG, "findBusinessApplication failed", t);
            return null;
        }
    }

    private static Object currentActivityThread() throws Exception {
        Class<?> clz = Class.forName("android.app.ActivityThread");
        Method m = clz.getDeclaredMethod("currentActivityThread");
        m.setAccessible(true);
        return m.invoke(null);
    }

    private static Object getField(Object obj, String name) throws Exception {
        Class<?> c = obj.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(obj);
            } catch (NoSuchFieldException ignored) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static void setField(Object obj, String name, Object value) throws Exception {
        Class<?> c = obj.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                f.set(obj, value);
                return;
            } catch (NoSuchFieldException ignored) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }
}
