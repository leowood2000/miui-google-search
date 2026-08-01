package com.leowood2000.miui.google.search;

import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.WeakHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Scoped to com.android.quicksearchbox. The hook rewrites the cloud payload
 * when it is written to searchengineNew.json, so changing the server hash or
 * update interval does not undo the local choice.
 */
public final class HookEntry implements IXposedHookLoadPackage {
    private static final String TARGET_PACKAGE = "com.android.quicksearchbox";
    private static final String GOOGLE_URL = "https://www.google.com/search?q={searchTerms}";
    private static final Map<FileOutputStream, ByteArrayOutputStream> TARGET_STREAMS =
            new WeakHashMap<>();
    private static final ThreadLocal<Boolean> RAW_WRITE = new ThreadLocal<>();
    private static Method RAW_WRITE_METHOD;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!TARGET_PACKAGE.equals(lpparam.packageName)) return;
        try {
            RAW_WRITE_METHOD = FileOutputStream.class.getDeclaredMethod(
                    "write", byte[].class, int.class, int.class);
            hookFileWrites();
            hookPreferenceWrites();
            XposedBridge.log("[MIUI Google Search Hook] loaded");
        } catch (Throwable t) {
            XposedBridge.log("[MIUI Google Search Hook] load failed: " + t);
        }
    }

    private static void hookFileWrites() {
        XC_MethodHook constructorHook = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                String path = null;
                if (param.args.length > 0 && param.args[0] instanceof String) {
                    path = (String) param.args[0];
                } else if (param.args.length > 0 && param.args[0] instanceof File) {
                    path = ((File) param.args[0]).getAbsolutePath();
                }
                if (path != null && path.contains("searchengineNew.json")) {
                    synchronized (TARGET_STREAMS) {
                        TARGET_STREAMS.put((FileOutputStream) param.thisObject, new ByteArrayOutputStream());
                    }
                }
            }
        };

        XposedHelpers.findAndHookConstructor(FileOutputStream.class, String.class, constructorHook);
        XposedHelpers.findAndHookConstructor(FileOutputStream.class, String.class, boolean.class, constructorHook);
        XposedHelpers.findAndHookConstructor(FileOutputStream.class, File.class, constructorHook);
        XposedHelpers.findAndHookConstructor(FileOutputStream.class, File.class, boolean.class, constructorHook);

        XposedHelpers.findAndHookMethod(FileOutputStream.class, "write", int.class,
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        ByteArrayOutputStream buffer = getBuffer(param.thisObject);
                        if (buffer == null || Boolean.TRUE.equals(RAW_WRITE.get())) return;
                        buffer.write((Integer) param.args[0]);
                        param.setResult(null);
                    }
                });

        XposedHelpers.findAndHookMethod(FileOutputStream.class, "write", byte[].class,
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        ByteArrayOutputStream buffer = getBuffer(param.thisObject);
                        if (buffer == null || Boolean.TRUE.equals(RAW_WRITE.get())) return;
                        byte[] data = (byte[]) param.args[0];
                        buffer.write(data, 0, data.length);
                        param.setResult(null);
                    }
                });

        XposedHelpers.findAndHookMethod(FileOutputStream.class, "write", byte[].class, int.class, int.class,
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        ByteArrayOutputStream buffer = getBuffer(param.thisObject);
                        if (buffer == null || Boolean.TRUE.equals(RAW_WRITE.get())) return;
                        buffer.write((byte[]) param.args[0], (Integer) param.args[1], (Integer) param.args[2]);
                        param.setResult(null);
                    }
                });

        XposedHelpers.findAndHookMethod(FileOutputStream.class, "close",
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        FileOutputStream stream = (FileOutputStream) param.thisObject;
                        ByteArrayOutputStream buffer;
                        synchronized (TARGET_STREAMS) { buffer = TARGET_STREAMS.remove(stream); }
                        if (buffer == null || Boolean.TRUE.equals(RAW_WRITE.get())) return;
                        byte[] output = rewriteSearchConfig(buffer.toByteArray());
                        try {
                            RAW_WRITE.set(Boolean.TRUE);
                            RAW_WRITE_METHOD.invoke(stream, output, 0, output.length);
                        } catch (Throwable t) {
                            XposedBridge.log("[MIUI Google Search Hook] write failed: " + t);
                        } finally {
                            RAW_WRITE.remove();
                        }
                    }
                });
    }

    private static ByteArrayOutputStream getBuffer(Object object) {
        synchronized (TARGET_STREAMS) {
            return TARGET_STREAMS.get(object);
        }
    }

    private static byte[] rewriteSearchConfig(byte[] input) {
        String text = new String(input, StandardCharsets.UTF_8);
        if (!text.contains("searchEngineName") || !text.trim().startsWith("{")) return input;
        try {
            JSONObject root = new JSONObject(text);
            JSONObject data = root.optJSONObject("data");
            if (data == null) return input;
            JSONObject defaults = data.optJSONObject("defaultSearchEngineMap");
            if (defaults != null) {
                defaults.put("globalSearchSearchBox", "google");
                defaults.put("globalSearchHotList", "google");
            }
            JSONObject scenes = data.optJSONObject("searchEngineSceneMap");
            if (scenes != null) {
                rewriteScene(scenes.optJSONObject("globalSearchSearchBox"));
                rewriteScene(scenes.optJSONObject("globalSearchHotList"));
            }
            return root.toString().getBytes(StandardCharsets.UTF_8);
        } catch (Throwable t) {
            XposedBridge.log("[MIUI Google Search Hook] JSON skipped: " + t);
            return input;
        }
    }

    private static void rewriteScene(JSONObject scene) throws Exception {
        if (scene == null) return;
        JSONArray engines = scene.optJSONArray("searchEngines");
        if (engines == null) return;
        for (int i = 0; i < engines.length(); i++) {
            JSONObject engine = engines.optJSONObject(i);
            if (engine != null && "360".equals(engine.optString("searchEngineName"))) {
                engine.put("searchEngineName", "google");
                engine.put("searchUrl", GOOGLE_URL);
                engine.put("iconUrl", "https://www.google.com/favicon.ico");
                engine.put("title_zh_CN", "Google");
                engine.put("title_zh_TW", "Google");
                engine.put("title_en_US", "Google");
                engine.put("title_bo_CN", "Google");
                engine.put("title_ug_CN", "Google");
                engine.put("channelNo", "google");
            }
        }
    }

    private static void hookPreferenceWrites() {
        XC_MethodHook hook = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                String key = (String) param.args[0];
                if ("current_engine".equals(key) || "common_setting_engine".equals(key)) {
                    param.args[1] = "google";
                } else if ("client_scene_info".equals(key) && param.args[1] instanceof String) {
                    param.args[1] = ((String) param.args[1]).replace(
                            "&quot;b&quot;:&quot;baidu&quot;", "&quot;b&quot;:&quot;google&quot;");
                }
            }
        };
        try {
            XposedHelpers.findAndHookMethod(
                    "android.app.SharedPreferencesImpl$EditorImpl", null,
                    "putString", String.class, String.class, hook);
        } catch (Throwable t) {
            XposedBridge.log("[MIUI Google Search Hook] preference hook skipped: " + t);
        }
    }
}
