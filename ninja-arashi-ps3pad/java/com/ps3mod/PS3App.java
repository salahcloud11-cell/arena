package com.ps3mod;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Window;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * Application hook for the Ninja Arashi PS3 mod.
 *
 * Ninja Arashi's activity is Unity's player activity, which overrides
 * dispatchKeyEvent / dispatchGenericMotionEvent and feeds the events straight
 * into the engine -- so a plain View listener never sees the controller.
 * To get in first, the window callback is replaced with a dynamic proxy that
 * inspects key / motion events, hands controller input to {@link PS3Mapper}
 * and delegates everything else to the original callback.
 *
 * A Proxy is used (instead of a hand-written Window.Callback implementation) so
 * the code needs only java.lang.reflect and cannot break when Android adds
 * methods to that interface.
 *
 * Wired in with android:name="com.ps3mod.PS3App" on <application>.
 */
public class PS3App extends Application {

    private static volatile boolean installed;

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
                @Override
                public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
                    install(activity);
                }

                @Override
                public void onActivityStarted(Activity activity) {
                }

                @Override
                public void onActivityResumed(Activity activity) {
                    install(activity);
                }

                @Override
                public void onActivityPaused(Activity activity) {
                }

                @Override
                public void onActivityStopped(Activity activity) {
                }

                @Override
                public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
                }

                @Override
                public void onActivityDestroyed(Activity activity) {
                }
            });
        } catch (Throwable ignored) {
        }
        try {
            PS3Mapper.start(this);
        } catch (Throwable ignored) {
        }
    }

    private static void install(Activity activity) {
        try {
            PS3Mapper.attach(activity);
        } catch (Throwable ignored) {
        }
        if (installed) {
            return;
        }
        try {
            final Window window = activity.getWindow();
            if (window == null) {
                return;
            }
            final Window.Callback original = window.getCallback();
            if (original == null) {
                return;
            }
            if (Proxy.isProxyClass(original.getClass())) {
                installed = true;
                return;
            }
            final ClassLoader loader = PS3App.class.getClassLoader();
            Object proxy = Proxy.newProxyInstance(loader,
                    new Class<?>[]{Window.Callback.class},
                    new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxyObj, Method method, Object[] args) {
                            try {
                                String name = method.getName();
                                if (args != null && args.length > 0) {
                                    if ("dispatchKeyEvent".equals(name)
                                            && args[0] instanceof KeyEvent) {
                                        if (PS3Mapper.onKey(activity, (KeyEvent) args[0])) {
                                            return Boolean.TRUE;
                                        }
                                    } else if ("dispatchGenericMotionEvent".equals(name)
                                            && args[0] instanceof MotionEvent) {
                                        if (PS3Mapper.onMotion(activity, (MotionEvent) args[0])) {
                                            return Boolean.TRUE;
                                        }
                                    }
                                }
                            } catch (Throwable ignored) {
                            }
                            try {
                                return method.invoke(original, args);
                            } catch (Throwable t) {
                                return defaultValue(method.getReturnType());
                            }
                        }
                    });
            window.setCallback((Window.Callback) proxy);
            installed = true;
        } catch (Throwable ignored) {
        }
    }

    private static Object defaultValue(Class<?> type) {
        if (type == null || !type.isPrimitive() || type == void.class) {
            return null;
        }
        if (type == boolean.class) {
            return Boolean.FALSE;
        }
        if (type == int.class) {
            return Integer.valueOf(0);
        }
        if (type == long.class) {
            return Long.valueOf(0L);
        }
        if (type == float.class) {
            return Float.valueOf(0f);
        }
        if (type == double.class) {
            return Double.valueOf(0d);
        }
        if (type == char.class) {
            return Character.valueOf((char) 0);
        }
        if (type == byte.class) {
            return Byte.valueOf((byte) 0);
        }
        if (type == short.class) {
            return Short.valueOf((short) 0);
        }
        return null;
    }
}
