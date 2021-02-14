/*
 * Copyright (C) 2019 Peter Gregus for GravityBox Project (C3C076@xda)
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.ceco.oreo.gravitybox;

import java.io.File;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.os.Message;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.SparseArray;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.Space;
import android.widget.ImageView.ScaleType;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class ModNavigationBar {
    public static final String PACKAGE_NAME = "com.android.systemui";
    private static final String TAG = "GB:ModNavigationBar";
    private static final boolean DEBUG = false;

    private static final String CLASS_NAVBAR_VIEW = "com.android.systemui.statusbar.phone.NavigationBarView";
    static final String CLASS_NAVBAR_FRAGMENT = "com.android.systemui.statusbar.phone.NavigationBarFragment";
    private static final String CLASS_KEY_BUTTON_VIEW = "com.android.systemui.statusbar.policy.KeyButtonView";
    private static final String CLASS_KEY_BUTTON_RIPPLE = "com.android.systemui.statusbar.policy.KeyButtonRipple";
    private static final String CLASS_NAVBAR_TRANSITIONS = 
            "com.android.systemui.statusbar.phone.NavigationBarTransitions";
    private static final String CLASS_STATUSBAR = "com.android.systemui.statusbar.phone.StatusBar";
    private static final String CLASS_NAVBAR_INFLATER_VIEW = "com.android.systemui.statusbar.phone.NavigationBarInflaterView";
    private static final String CLASS_LIGHT_BAR_CTRL = "com.android.systemui.statusbar.phone.LightBarTransitionsController";

    @SuppressWarnings("unused")
    public static final int MODE_OPAQUE = 0;
    public static final int MODE_LIGHTS_OUT = 3;
    public static final int MODE_TRANSPARENT = 4;
    public static final int MODE_LIGHTS_OUT_TRANSPARENT = 6;
    private static final int MSG_LIGHTS_OUT = 1;

    static final int NAVIGATION_HINT_BACK_ALT = 1 << 0;
    static final int STATUS_BAR_DISABLE_RECENT = 0x01000000;
    static final int STATUS_BAR_DISABLE_SEARCH = 0x02000000;

    private static boolean mAlwaysShowMenukey;
    private static View mNavigationBarView;
    private static ModHwKeys.HwKeyAction mRecentsSingletapAction = new ModHwKeys.HwKeyAction(0, null);
    private static ModHwKeys.HwKeyAction mRecentsLongpressAction = new ModHwKeys.HwKeyAction(0, null);
    private static ModHwKeys.HwKeyAction mRecentsDoubletapAction = new ModHwKeys.HwKeyAction(0, null);
    private static boolean mHwKeysEnabled;
    private static boolean mCursorControlEnabled;
    private static boolean mDpadKeysVisible;
    private static boolean mHideImeSwitcher;
    private static PowerManager mPm;
    private static long mLastTouchMs;
    private static int mBarModeOriginal;
    private static int mAutofadeTimeoutMs;
    private static String mAutofadeShowKeysPolicy;
    private static boolean mUpdateDisabledFlags;
    private static boolean mUpdateIconHints;
    private static String mPreferenceDir;

    // Navbar dimensions
    private static int mNavbarHeight;
    private static int mNavbarWidth;

    // Custom key
    private enum CustomKeyIconStyle { SIX_DOT, THREE_DOT, TRANSPARENT, CUSTOM }

    private static boolean mCustomKeyEnabled;
    private static Resources mResources;
    private static Context mGbContext;
    private static NavbarViewInfo[] mNavbarViewInfo = new NavbarViewInfo[2];
    private static boolean mCustomKeySwapEnabled;
    private static CustomKeyIconStyle mCustomKeyIconStyle;

    // Colors
    private static boolean mNavbarColorsEnabled;
    private static int mKeyDefaultColor = 0xe8ffffff;
    private static int mKeyDefaultGlowColor = 0x33ffffff;
    private static int mKeyColor;
    private static int mKeyGlowColor;

    private static void log(String message) {
        XposedBridge.log(TAG + ": " + message);
    }

    static class NavbarViewInfo {
        ViewGroup navButtons;
        ViewGroup endsGroup;
        ViewGroup centerGroup;
        View backView;
        View recentsView;
        KeyButtonView customKey;
        View customKeyPlaceHolder;
        ViewGroup customKeyParent;
        boolean customKeyVisible;
        KeyButtonView dpadLeft;
        KeyButtonView dpadRight;
        boolean menuCustomSwapped;
        ViewGroup menuImeGroup;
        View menuImeView;
        View imeSwitcher;
        View menuKey;
        View backKey;
        View recentsKey;
        SparseArray<ScaleType> originalScaleType = new SparseArray<>();
        void setDarkIntensity(float intensity) {
            if (intensity == 0f) {
                if (customKey != null) customKey.clearColorFilter();
                if (dpadLeft != null) dpadLeft.clearColorFilter();
                if (dpadRight != null) dpadRight.clearColorFilter();
            } else {
                if (customKey != null) customKey.setColorFilter(0xff707070, PorterDuff.Mode.SRC_ATOP);
                if (dpadLeft != null) dpadLeft.setColorFilter(0xff707070, PorterDuff.Mode.SRC_ATOP);
                if (dpadRight != null) dpadRight.setColorFilter(0xff707070, PorterDuff.Mode.SRC_ATOP);
            }
        }
    }

    private static BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (DEBUG) log("Broadcast received: " + intent.toString());
            if (intent.getAction().equals(GravityBoxSettings.ACTION_PREF_NAVBAR_CHANGED)) {
                if (intent.hasExtra(GravityBoxSettings.EXTRA_NAVBAR_MENUKEY)) {
                    mAlwaysShowMenukey = intent.getBooleanExtra(
                            GravityBoxSettings.EXTRA_NAVBAR_MENUKEY, false);
                    if (DEBUG) log("mAlwaysShowMenukey = " + mAlwaysShowMenukey);
                    setMenuKeyVisibility();
                }
                if (intent.hasExtra(GravityBoxSettings.EXTRA_NAVBAR_CUSTOM_KEY_ENABLE)) {
                    mCustomKeyEnabled = intent.getBooleanExtra(
                            GravityBoxSettings.EXTRA_NAVBAR_CUSTOM_KEY_ENABLE, false);
                    setCustomKeyVisibility();
                }
                if (intent.hasExtra(GravityBoxSettings.EXTRA_NAVBAR_KEY_COLOR)) {
                    mKeyColor = intent.getIntExtra(
                            GravityBoxSettings.EXTRA_NAVBAR_KEY_COLOR, mKeyDefaultColor);
                    setKeyColor();
                }
                if (intent.hasExtra(GravityBoxSettings.EXTRA_NAVBAR_KEY_GLOW_COLOR)) {
                    mKeyGlowColor = intent.getIntExtra(
                            GravityBoxSettings.EXTRA_NAVBAR_KEY_GLOW_COLOR, mKeyDefaultGlowColor);
                    setKeyColor();
                }
                if (intent.hasExtra(GravityBoxSettings.EXTRA_NAVBAR_COLOR_ENABLE)) {
                    mNavbarColorsEnabled = intent.getBooleanExtra(
                            GravityBoxSettings.EXTRA_NAVBAR_COLOR_ENABLE, false);
                    setKeyColor();
                }
                if (intent.hasExtra(GravityBoxSettings.EXTRA_NAVBAR_CURSOR_CONTROL)) {
                    mCursorControlEnabled = intent.getBooleanExtra(
                            GravityBoxSettings.EXTRA_NAVBAR_CURSOR_CONTROL, false);
                }
                if (intent.hasExtra(GravityBoxSettings.EXTRA_NAVBAR_CUSTOM_KEY_SWAP)) {
                    mCustomKeySwapEnabled = intent.getBooleanExtra(
                            GravityBoxSettings.EXTRA_NAVBAR_CUSTOM_KEY_SWAP, false);
                    setCustomKeyVisibility();
                }
                if (intent.hasExtra(GravityBoxSettings.EXTRA_NAVBAR_CUSTOM_KEY_ICON_STYLE)) {
                    mCustomKeyIconStyle = CustomKeyIconStyle.valueOf(intent.getStringExtra(
                            GravityBoxSettings.EXTRA_NAVBAR_CUSTOM_KEY_ICON_STYLE));
                    updateCustomKeyIcon();
                }
                if (intent.hasExtra(GravityBoxSettings.EXTRA_NAVBAR_HIDE_IME)) {
                    mHideImeSwitcher = intent.getBooleanExtra(
                            GravityBoxSettings.EXTRA_NAVBAR_HIDE_IME, false);
                }
                if (intent.hasExtra(GravityBoxSettings.EXTRA_NAVBAR_HEIGHT)) {
                    mNavbarHeight = intent.getIntExtra(GravityBoxSettings.EXTRA_NAVBAR_HEIGHT, 100);
                    updateIconScaleType();
                }
                if (intent.hasExtra(GravityBoxSettings.EXTRA_NAVBAR_WIDTH)) {
                    mNavbarWidth = intent.getIntExtra(GravityBoxSettings.EXTRA_NAVBAR_WIDTH, 100);
                    updateIconScaleType();
                }
                if (intent.hasExtra(GravityBoxSettings.EXTRA_NAVBAR_AUTOFADE_KEYS)) {
                    mAutofadeTimeoutMs = intent.getIntExtra(GravityBoxSettings.EXTRA_NAVBAR_AUTOFADE_KEYS, 0) * 1000;
                    mBarModeHandler.removeMessages(MSG_LIGHTS_OUT);
                    if (mAutofadeTimeoutMs == 0) {
                        setBarMode(mBarModeOriginal);
                    } else {
                        mBarModeHandler.sendEmptyMessageDelayed(MSG_LIGHTS_OUT, mAutofadeTimeoutMs);
                    }
                }
                if (intent.hasExtra(GravityBoxSettings.EXTRA_NAVBAR_AUTOFADE_SHOW_KEYS)) {
                    mAutofadeShowKeysPolicy = intent.getStringExtra(
                            GravityBoxSettings.EXTRA_NAVBAR_AUTOFADE_SHOW_KEYS);
                }
            } else if (intent.getAction().equals(
                    GravityBoxSettings.ACTION_PREF_HWKEY_CHANGED) && 
                    GravityBoxSettings.PREF_KEY_HWKEY_RECENTS_SINGLETAP.equals(intent.getStringExtra(
                            GravityBoxSettings.EXTRA_HWKEY_KEY))) {
                mRecentsSingletapAction.actionId = intent.getIntExtra(GravityBoxSettings.EXTRA_HWKEY_VALUE, 0);
                mRecentsSingletapAction.customApp = intent.getStringExtra(GravityBoxSettings.EXTRA_HWKEY_CUSTOM_APP);
                updateRecentsKeyCode();
            } else if (intent.getAction().equals(
                    GravityBoxSettings.ACTION_PREF_HWKEY_CHANGED) &&
                    GravityBoxSettings.PREF_KEY_HWKEY_RECENTS_LONGPRESS.equals(intent.getStringExtra(
                            GravityBoxSettings.EXTRA_HWKEY_KEY))) {
                mRecentsLongpressAction.actionId = intent.getIntExtra(GravityBoxSettings.EXTRA_HWKEY_VALUE, 0);
                mRecentsLongpressAction.customApp = intent.getStringExtra(GravityBoxSettings.EXTRA_HWKEY_CUSTOM_APP);
                updateRecentsKeyCode();
            } else if (intent.getAction().equals(
                    GravityBoxSettings.ACTION_PREF_HWKEY_CHANGED) &&
                    GravityBoxSettings.PREF_KEY_HWKEY_RECENTS_DOUBLETAP.equals(intent.getStringExtra(
                            GravityBoxSettings.EXTRA_HWKEY_KEY))) {
                mRecentsDoubletapAction.actionId = intent.getIntExtra(GravityBoxSettings.EXTRA_HWKEY_VALUE, 0);
                mRecentsDoubletapAction.customApp = intent.getStringExtra(GravityBoxSettings.EXTRA_HWKEY_CUSTOM_APP);
                updateRecentsKeyCode();
            } else if (intent.getAction().equals(GravityBoxSettings.ACTION_PREF_PIE_CHANGED) &&
                    intent.hasExtra(GravityBoxSettings.EXTRA_PIE_HWKEYS_DISABLE)) {
                mHwKeysEnabled = !intent.getBooleanExtra(GravityBoxSettings.EXTRA_PIE_HWKEYS_DISABLE, false);
                updateRecentsKeyCode();
            } else if (intent.getAction().equals(GravityBoxSettings.ACTION_PREF_NAVBAR_SWAP_KEYS)) {
                swapBackAndRecents();
            }
        }
    };

    public static void init(final XSharedPreferences prefs, final ClassLoader classLoader) {
        mPreferenceDir = prefs.getFile().getParent();
        mAlwaysShowMenukey = prefs.getBoolean(GravityBoxSettings.PREF_KEY_NAVBAR_MENUKEY, false);

        try {
            mRecentsSingletapAction = new ModHwKeys.HwKeyAction(Integer.valueOf(
                    prefs.getString(GravityBoxSettings.PREF_KEY_HWKEY_RECENTS_SINGLETAP, "0")),
                    prefs.getString(GravityBoxSettings.PREF_KEY_HWKEY_RECENTS_SINGLETAP+"_custom", null));
        } catch (NumberFormatException nfe) {
            GravityBox.log(TAG, "Invalid value for mRecentsSingletapAction");
        }
        try {
            mRecentsLongpressAction = new ModHwKeys.HwKeyAction(Integer.valueOf(
                    prefs.getString(GravityBoxSettings.PREF_KEY_HWKEY_RECENTS_LONGPRESS, "0")),
                    prefs.getString(GravityBoxSettings.PREF_KEY_HWKEY_RECENTS_LONGPRESS+"_custom", null));
        } catch (NumberFormatException nfe) {
            GravityBox.log(TAG, "Invalid value for mRecentsLongpressAction");
        }
        try {
            mRecentsDoubletapAction = new ModHwKeys.HwKeyAction(Integer.valueOf(
                    prefs.getString(GravityBoxSettings.PREF_KEY_HWKEY_RECENTS_DOUBLETAP, "0")),
                    prefs.getString(GravityBoxSettings.PREF_KEY_HWKEY_RECENTS_DOUBLETAP+"_custom", null));
        } catch (NumberFormatException nfe) {
            GravityBox.log(TAG, "Invalid value for mRecentsDoubletapAction");
        }

        mCustomKeyEnabled = prefs.getBoolean(
                GravityBoxSettings.PREF_KEY_NAVBAR_CUSTOM_KEY_ENABLE, false);
        mHwKeysEnabled = !prefs.getBoolean(GravityBoxSettings.PREF_KEY_HWKEYS_DISABLE, false);
        mCursorControlEnabled = prefs.getBoolean(
                GravityBoxSettings.PREF_KEY_NAVBAR_CURSOR_CONTROL, false);
        mCustomKeySwapEnabled = prefs.getBoolean(
                GravityBoxSettings.PREF_KEY_NAVBAR_CUSTOM_KEY_SWAP, false);
        mHideImeSwitcher = prefs.getBoolean(GravityBoxSettings.PREF_KEY_NAVBAR_HIDE_IME, false);

        mNavbarHeight = prefs.getInt(GravityBoxSettings.PREF_KEY_NAVBAR_HEIGHT, 100);
        mNavbarWidth = prefs.getInt(GravityBoxSettings.PREF_KEY_NAVBAR_WIDTH, 100);
        mAutofadeTimeoutMs = Build.VERSION.SDK_INT >= 27 ? 0 :
                             prefs.getInt(GravityBoxSettings.PREF_KEY_NAVBAR_AUTOFADE_KEYS, 0) * 1000;
        mAutofadeShowKeysPolicy = "NAVBAR"; //prefs.getString(GravityBoxSettings.PREF_KEY_NAVBAR_AUTOFADE_SHOW_KEYS, "NAVBAR");

        final Class<?> navbarViewClass;
        try {
            navbarViewClass = XposedHelpers.findClass(CLASS_NAVBAR_VIEW, classLoader);
            XposedBridge.hookAllConstructors(navbarViewClass, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Context context = (Context) param.args[0];
                    if (context == null) return;

                    mResources = context.getResources();

                    mGbContext = Utils.getGbContext(context);
                    mNavbarColorsEnabled = prefs.getBoolean(GravityBoxSettings.PREF_KEY_NAVBAR_COLOR_ENABLE, false);
                    mKeyDefaultColor = mGbContext.getColor(R.color.navbar_key_color);
                    mKeyColor = prefs.getInt(GravityBoxSettings.PREF_KEY_NAVBAR_KEY_COLOR, mKeyDefaultColor);
                    mKeyDefaultGlowColor = mGbContext.getColor(R.color.navbar_key_glow_color);
                    mKeyGlowColor = prefs.getInt(
                            GravityBoxSettings.PREF_KEY_NAVBAR_KEY_GLOW_COLOR, mKeyDefaultGlowColor);
                    mCustomKeyIconStyle = CustomKeyIconStyle.valueOf(prefs.getString(
                            GravityBoxSettings.PREF_KEY_NAVBAR_CUSTOM_KEY_ICON_STYLE, "SIX_DOT"));

                    mNavigationBarView = (View) param.thisObject;
                    IntentFilter intentFilter = new IntentFilter();
                    intentFilter.addAction(GravityBoxSettings.ACTION_PREF_NAVBAR_CHANGED);
                    intentFilter.addAction(GravityBoxSettings.ACTION_PREF_HWKEY_CHANGED);
                    intentFilter.addAction(GravityBoxSettings.ACTION_PREF_PIE_CHANGED);
                    intentFilter.addAction(GravityBoxSettings.ACTION_PREF_NAVBAR_SWAP_KEYS);
                    context.registerReceiver(mBroadcastReceiver, intentFilter);
                    if (DEBUG) log("NavigationBarView constructed; Broadcast receiver registered");
                }
            });
        } catch (Throwable t) {
            GravityBox.log("Error hooking navbar constructor, navbar tweaks disabled:", t);
            return;
        }

        try {
            XposedHelpers.findAndHookMethod(navbarViewClass, "setMenuVisibility",
                    boolean.class, boolean.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    setMenuKeyVisibility();
                }
            });
        } catch (Throwable t) {
            GravityBox.log("Error hooking setMenuVisibility:", t);
        }

        try {
            XposedHelpers.findAndHookMethod(CLASS_NAVBAR_INFLATER_VIEW, classLoader, "inflateLayout",
                    String.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    final Context context = ((View) param.thisObject).getContext();

                    // prepare app, dpad left, dpad right keys
                    ViewGroup vRot, navButtons;

                    // prepare keys for rot0 view
                    vRot = (ViewGroup) XposedHelpers.getObjectField(param.thisObject, "mRot0");
                    if (vRot != null) {
                        ScaleType scaleType = Build.VERSION.SDK_INT >= 27 ?
                                ScaleType.FIT_CENTER : getIconScaleType(0, View.NO_ID);
                        int[] padding = getIconPaddingPx(0);
                        KeyButtonView appKey = new KeyButtonView(context);
                        appKey.setScaleType(scaleType);
                        appKey.setPadding(padding[0], padding[1], padding[2], padding[3]);
                        appKey.setClickable(true);
                        appKey.setImageDrawable(getCustomKeyIconDrawable());
                        appKey.setKeyCode(KeyEvent.KEYCODE_SOFT_LEFT);

                        KeyButtonView dpadLeft = new KeyButtonView(context);
                        dpadLeft.setScaleType(scaleType);
                        dpadLeft.setPadding(padding[0], padding[1], padding[2], padding[3]);
                        dpadLeft.setClickable(true);
                        dpadLeft.setImageDrawable(mGbContext.getDrawable(R.drawable.ic_sysbar_ime_left));
                        dpadLeft.setVisibility(View.GONE);
                        dpadLeft.setKeyCode(KeyEvent.KEYCODE_DPAD_LEFT);

                        KeyButtonView dpadRight = new KeyButtonView(context);
                        dpadRight.setScaleType(scaleType);
                        dpadRight.setPadding(padding[0], padding[1], padding[2], padding[3]);
                        dpadRight.setClickable(true);
                        dpadRight.setImageDrawable(mGbContext.getDrawable(R.drawable.ic_sysbar_ime_right));
                        dpadRight.setVisibility(View.GONE);
                        dpadRight.setKeyCode(KeyEvent.KEYCODE_DPAD_RIGHT);

                        navButtons = vRot.findViewById(
                                mResources.getIdentifier("nav_buttons", "id", PACKAGE_NAME));
                        prepareNavbarViewInfo(navButtons, 0, appKey, dpadLeft, dpadRight);
                    }

                    // prepare keys for rot90 view
                    vRot = (ViewGroup) XposedHelpers.getObjectField(param.thisObject, "mRot90");
                    if (vRot != null) {
                        ScaleType scaleType = Build.VERSION.SDK_INT >= 27 ?
                                ScaleType.FIT_CENTER : getIconScaleType(1, View.NO_ID);
                        int[] padding = getIconPaddingPx(1);
                        KeyButtonView appKey = new KeyButtonView(context);
                        appKey.setScaleType(scaleType);
                        appKey.setPadding(padding[0], padding[1], padding[2], padding[3]);
                        appKey.setClickable(true);
                        appKey.setImageDrawable(getCustomKeyIconDrawable());
                        appKey.setKeyCode(KeyEvent.KEYCODE_SOFT_LEFT);

                        KeyButtonView dpadLeft = new KeyButtonView(context);
                        dpadLeft.setScaleType(scaleType);
                        dpadLeft.setPadding(padding[0], padding[1], padding[2], padding[3]);
                        dpadLeft.setClickable(true);
                        dpadLeft.setImageDrawable(mGbContext.getDrawable(R.drawable.ic_sysbar_ime_left));
                        dpadLeft.setVisibility(View.GONE);
                        dpadLeft.setKeyCode(KeyEvent.KEYCODE_DPAD_LEFT);

                        KeyButtonView dpadRight = new KeyButtonView(context);
                        dpadRight.setScaleType(scaleType);
                        dpadRight.setPadding(padding[0], padding[1], padding[2], padding[3]);
                        dpadRight.setClickable(true);
                        dpadRight.setImageDrawable(mGbContext.getDrawable(R.drawable.ic_sysbar_ime_right));
                        dpadRight.setVisibility(View.GONE);
                        dpadRight.setKeyCode(KeyEvent.KEYCODE_DPAD_RIGHT);

                        navButtons = vRot.findViewById(
                                mResources.getIdentifier("nav_buttons", "id", PACKAGE_NAME));
                        prepareNavbarViewInfo(navButtons, 1, appKey, dpadLeft, dpadRight);
                    }

                    updateRecentsKeyCode();

                    if (prefs.getBoolean(GravityBoxSettings.PREF_KEY_NAVBAR_SWAP_KEYS, false)) {
                        swapBackAndRecents();
                    }

                    updateIconScaleType();
                    setCustomKeyVisibility();
                }
            });
        } catch (Throwable t) {
            GravityBox.log(TAG, "Error hooking inflateLayout:", t);
        }

        try {
            XposedHelpers.findAndHookMethod(navbarViewClass, "setDisabledFlags",
                    int.class, boolean.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    mUpdateDisabledFlags = (boolean)param.args[1] ||
                            XposedHelpers.getIntField(param.thisObject, "mDisabledFlags") !=
                                (int)param.args[0];
                }
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (mUpdateDisabledFlags) {
                        mUpdateDisabledFlags = false;
                        setDpadKeyVisibility();
                        setCustomKeyVisibility();
                        setMenuKeyVisibility();
                        if (mNavbarColorsEnabled) {
                            setKeyColor();
                        }
                    }
                }
            });
        } catch (Throwable t) {
            GravityBox.log(TAG, "Error hooking setDisabledFlags:", t);
        }

        try {
            XposedHelpers.findAndHookMethod(navbarViewClass, "setNavigationIconHints",
                    int.class, boolean.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    mUpdateIconHints = (boolean)param.args[1] ||
                            XposedHelpers.getIntField(param.thisObject, "mNavigationIconHints") !=
                            (int)param.args[0];
                }
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (mUpdateIconHints) {
                        mUpdateIconHints = false;
                        if (mHideImeSwitcher) {
                            hideImeSwitcher();
                        }
                        setDpadKeyVisibility();
                    }
                }
            });
        } catch (Throwable t) {
            GravityBox.log(TAG, "Error hooking setNavigationIconHints:", t);
        }

        if (Build.VERSION.SDK_INT < 27) {
            try {
                XposedHelpers.findAndHookMethod(CLASS_NAVBAR_TRANSITIONS, classLoader, "applyMode",
                        int.class, boolean.class, boolean.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        int barMode = (int)param.args[0];
                        if (barMode != MODE_LIGHTS_OUT_TRANSPARENT) {
                            mBarModeOriginal = barMode;
                        }
                        if (mAutofadeTimeoutMs > 0 &&
                                SystemClock.uptimeMillis() - mLastTouchMs >= mAutofadeTimeoutMs &&
                                    barMode != MODE_LIGHTS_OUT &&
                                    barMode != MODE_LIGHTS_OUT_TRANSPARENT) {
                            param.args[0] = MODE_LIGHTS_OUT_TRANSPARENT;
                        }
                    }
                });
            } catch (Throwable t) {
                GravityBox.log(TAG, "Error hooking applyMode:", t);
            }
        }

        try {
            XposedHelpers.findAndHookMethod(CLASS_KEY_BUTTON_RIPPLE, classLoader,
                    "getRipplePaint", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (mNavbarColorsEnabled) {
                        ((Paint)param.getResult()).setColor(mKeyGlowColor);
                    }
                }
            });
        } catch (Throwable t) {
            GravityBox.log(TAG, "Error hooking getRipplePaint:", t);
        }

        try {
            XposedHelpers.findAndHookMethod(CLASS_KEY_BUTTON_VIEW, classLoader,
                    "sendEvent", int.class, int.class, long.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (mPm == null) {
                        mPm = (PowerManager) ((View) param.thisObject).getContext()
                            .getSystemService(Context.POWER_SERVICE);
                    }
                    if (mPm != null && !mPm.isInteractive()) {
                        int keyCode = XposedHelpers.getIntField(param.thisObject, "mCode");
                        if (keyCode != KeyEvent.KEYCODE_HOME) {
                            if (DEBUG) log("key button sendEvent: ignoring since not interactive");
                            param.setResult(null);
                        }
                    }
                }
            });
        } catch (Throwable t) {
            GravityBox.log(TAG, "Error hooking sendEvent:", t);
        }

        if (Build.VERSION.SDK_INT < 27) {
            XC_MethodHook touchEventHook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (mAutofadeTimeoutMs == 0) return;

                    int action = ((MotionEvent)param.args[0]).getAction();
                    if (action == MotionEvent.ACTION_DOWN ||
                            (action == MotionEvent.ACTION_OUTSIDE &&
                                 "SCREEN".equals(mAutofadeShowKeysPolicy))) {
                        mLastTouchMs = SystemClock.uptimeMillis();
                        if (mBarModeHandler.hasMessages(MSG_LIGHTS_OUT)) {
                            mBarModeHandler.removeMessages(MSG_LIGHTS_OUT);
                        } else {
                            setBarMode(mBarModeOriginal);
                        }
                        mBarModeHandler.sendEmptyMessageDelayed(MSG_LIGHTS_OUT, mAutofadeTimeoutMs);
                    }
                }
            };
            try {
                XposedHelpers.findAndHookMethod(CLASS_NAVBAR_VIEW, classLoader,
                        "onInterceptTouchEvent", MotionEvent.class, touchEventHook);
            } catch (Throwable t) {
                GravityBox.log(TAG, "Error hooking onInterceptTouchEvent:", t);
            }
            try {
                XposedHelpers.findAndHookMethod(CLASS_NAVBAR_VIEW, classLoader,
                        "onTouchEvent", MotionEvent.class, touchEventHook);
            } catch (Throwable t) {
                GravityBox.log(TAG, "Error hooking onTouchEvent:", t);
            }
        }

        try {
            XposedHelpers.findAndHookMethod(CLASS_STATUSBAR, classLoader,
                    "toggleSplitScreenMode", int.class, int.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (mRecentsLongpressAction.actionId != 0 &&
                            (int)param.args[0] != -1 && (int)param.args[1] != -1) {
                        param.setResult(false);
                    }
                }
            });
        } catch (Throwable t) {
            GravityBox.log(TAG, "Error hooking toggleSplitScreenMode:", t);
        }

        try {
            XposedHelpers.findAndHookMethod(CLASS_LIGHT_BAR_CTRL, classLoader,
                    "setIconTintInternal", float.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (mNavbarColorsEnabled)
                        return;

                    float intensity = (float)param.args[0];
                    for (NavbarViewInfo navbarViewInfo : mNavbarViewInfo) {
                        if (navbarViewInfo != null) {
                            navbarViewInfo.setDarkIntensity(intensity);
                        }
                    }
                }
            });
        } catch (Throwable t) {
            GravityBox.log(TAG, "Error hooking setIconTintInternal:", t);
        }
    }

    private static Handler mBarModeHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            if (msg.what == MSG_LIGHTS_OUT) {
                setBarMode(MODE_LIGHTS_OUT_TRANSPARENT);
            }
        }
    };

    private static void setBarMode(int mode) {
        try {
            Object bt = XposedHelpers.callMethod(mNavigationBarView, "getBarTransitions");
            XposedHelpers.callMethod(bt, "applyMode", mode, true, true);
        } catch (Throwable t) {
            GravityBox.log(TAG, t);
        }
    }

    private static void prepareNavbarViewInfo(ViewGroup navButtons, int index, 
            KeyButtonView appView, KeyButtonView dpadLeft, KeyButtonView dpadRight) {
        try {
            mNavbarViewInfo[index] = new NavbarViewInfo();
            mNavbarViewInfo[index].navButtons = navButtons;
            mNavbarViewInfo[index].customKey = appView;
            mNavbarViewInfo[index].dpadLeft = dpadLeft;
            mNavbarViewInfo[index].dpadRight = dpadRight;

            // ends group
            int resId = mResources.getIdentifier("ends_group", "id", PACKAGE_NAME);
            ViewGroup endsGroup = mNavbarViewInfo[index]
                    .navButtons.findViewById(resId);
            mNavbarViewInfo[index].endsGroup = endsGroup;

            if (DEBUG) {
                log("List of children for mNavbarViewInfo[" + index + "].endsGroup:");
                for (int i = 0; i < mNavbarViewInfo[index].endsGroup.getChildCount(); i++) {
                    log(mNavbarViewInfo[index].endsGroup.getChildAt(i).toString());
                }
            }

            // center group
            resId = mResources.getIdentifier("center_group", "id", PACKAGE_NAME);
            mNavbarViewInfo[index].centerGroup = mNavbarViewInfo[index].navButtons.findViewById(resId);

            // find menu key
            resId = mResources.getIdentifier("menu", "id", PACKAGE_NAME);
            if (resId != 0) {
                mNavbarViewInfo[index].menuKey = endsGroup.findViewById(resId);
            }

            // find back key
            resId = mResources.getIdentifier("back", "id", PACKAGE_NAME);
            if (resId != 0) {
                mNavbarViewInfo[index].backKey = endsGroup.findViewById(resId);
                if (mNavbarViewInfo[index].backKey != null) {
                    mNavbarViewInfo[index].backView =
                            mNavbarViewInfo[index].backKey.getParent() == endsGroup ?
                                    mNavbarViewInfo[index].backKey :
                                    (View) mNavbarViewInfo[index].backKey.getParent();
                }
            }

            // find recent apps key
            resId = mResources.getIdentifier("recent_apps", "id", PACKAGE_NAME);
            if (resId != 0) {
                mNavbarViewInfo[index].recentsKey = endsGroup.findViewById(resId);
                if (mNavbarViewInfo[index].recentsKey != null) {
                    mNavbarViewInfo[index].recentsView =
                            mNavbarViewInfo[index].recentsKey.getParent() == endsGroup ?
                                    mNavbarViewInfo[index].recentsKey :
                                    (ViewGroup)mNavbarViewInfo[index].recentsKey.getParent();
                }
            }

            // find ime switcher, menu group
            resId = mResources.getIdentifier("ime_switcher", "id", PACKAGE_NAME);
            if (resId != 0) {
                View v = endsGroup.findViewById(resId);
                if (v != null) {
                    mNavbarViewInfo[index].imeSwitcher = v;
                    mNavbarViewInfo[index].menuImeGroup = (ViewGroup) v.getParent();
                    mNavbarViewInfo[index].menuImeView =
                            mNavbarViewInfo[index].menuImeGroup.getParent() == endsGroup ?
                                    mNavbarViewInfo[index].menuImeGroup :
                                    (View) mNavbarViewInfo[index].menuImeGroup.getParent();
                }
            }

            // determine key size
            Resources res = navButtons.getResources();
            boolean hasVerticalNavbar = mGbContext.getResources().getBoolean(R.bool.hasVerticalNavbar);
            final int sizeResId = res.getIdentifier(hasVerticalNavbar ?
                    "navigation_side_padding" : "navigation_extra_key_width", "dimen", PACKAGE_NAME);
            final int size = sizeResId == 0 ?
                    (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                            50, res.getDisplayMetrics()) :
                    res.getDimensionPixelSize(sizeResId);
            if (DEBUG) log("App key view minimum size=" + size);
            int w = (index == 1 && hasVerticalNavbar) ? ViewGroup.LayoutParams.MATCH_PARENT : size;
            int h = (index == 1 && hasVerticalNavbar) ? size : ViewGroup.LayoutParams.MATCH_PARENT;

            // find potential placeholder for custom key
            int oosNavResId = mResources.getIdentifier("nav", "id", PACKAGE_NAME);
            mNavbarViewInfo[index].customKeyParent = endsGroup;
            int pos1 = 0;
            int pos2 = endsGroup.getChildCount()-1;
            if (endsGroup.getChildAt(0).getClass().getName().equals(CLASS_KEY_BUTTON_VIEW) &&
                    endsGroup.getChildAt(0).getId() == oosNavResId) {
                mNavbarViewInfo[index].customKeyPlaceHolder = endsGroup.getChildAt(0);
            } else if (endsGroup.getChildAt(pos1) instanceof Space) {
                mNavbarViewInfo[index].customKeyPlaceHolder = endsGroup.getChildAt(pos1);
            } else if (endsGroup.getChildAt(pos2) instanceof Space) {
                mNavbarViewInfo[index].customKeyPlaceHolder = endsGroup.getChildAt(pos2);
            } else if (endsGroup.getChildAt(pos1) instanceof ViewGroup &&
                    endsGroup.getChildAt(pos1) != mNavbarViewInfo[index].menuImeView &&
                    endsGroup.getChildAt(pos1) != mNavbarViewInfo[index].backView) {
                mNavbarViewInfo[index].customKeyParent = (ViewGroup) endsGroup.getChildAt(pos1);
            } else if (endsGroup.getChildAt(pos2) instanceof ViewGroup &&
                    endsGroup.getChildAt(pos2) != mNavbarViewInfo[index].menuImeView &&
                    endsGroup.getChildAt(pos2) != mNavbarViewInfo[index].backView) {
                mNavbarViewInfo[index].customKeyParent = (ViewGroup) endsGroup.getChildAt(pos2);
            }
            if (DEBUG) log("customKeyPlaceHolder=" + mNavbarViewInfo[index].customKeyPlaceHolder);
            if (DEBUG) log("customKeyParent=" + mNavbarViewInfo[index].customKeyParent);

            ViewGroup.LayoutParams lp;

            // determine custom key layout
            if (mNavbarViewInfo[index].customKeyParent instanceof RelativeLayout)
                lp = new RelativeLayout.LayoutParams(w, h);
            else if (mNavbarViewInfo[index].customKeyParent instanceof FrameLayout)
                lp = new FrameLayout.LayoutParams(w, h);
            else
                lp = new LinearLayout.LayoutParams(w, h, 0);
            if (DEBUG) log("appView layout: " + lp);
            mNavbarViewInfo[index].customKey.setLayoutParams(lp);

            // Add cursor control keys
            endsGroup.addView(dpadLeft, 0);
            endsGroup.addView(dpadRight, endsGroup.getChildCount());
            if (endsGroup instanceof RelativeLayout)
                lp = new RelativeLayout.LayoutParams(w, h);
            else if (endsGroup instanceof FrameLayout)
                lp = new FrameLayout.LayoutParams(w, h);
            else
                lp = new LinearLayout.LayoutParams(w, h, 0);
            if (DEBUG) log("DPAD key layout: " + lp);
            mNavbarViewInfo[index].dpadLeft.setLayoutParams(lp);
            mNavbarViewInfo[index].dpadRight.setLayoutParams(lp);
        } catch (Throwable t) {
            GravityBox.log(TAG, "Error preparing NavbarViewInfo: ", t);
        }
    }

    private static void setCustomKeyVisibility() {
        try {
            final int disabledFlags = XposedHelpers.getIntField(mNavigationBarView, "mDisabledFlags");
            final boolean visible = mCustomKeyEnabled &&
                    (disabledFlags & STATUS_BAR_DISABLE_RECENT) == 0;
            for (NavbarViewInfo navbarViewInfo : mNavbarViewInfo) {
                if (navbarViewInfo == null) continue;

                if (navbarViewInfo.customKeyVisible != visible) {
                    if (navbarViewInfo.customKeyPlaceHolder != null) {
                        int position = navbarViewInfo.customKeyParent.indexOfChild(
                                visible ? navbarViewInfo.customKeyPlaceHolder :
                                        navbarViewInfo.customKey);
                        navbarViewInfo.customKeyParent.removeViewAt(position);
                        navbarViewInfo.customKeyParent.addView(visible ?
                                        navbarViewInfo.customKey : navbarViewInfo.customKeyPlaceHolder,
                                position);
                    } else {
                        if (visible) {
                            navbarViewInfo.customKeyParent.addView(navbarViewInfo.customKey, 0);
                        } else {
                            navbarViewInfo.customKeyParent.removeView(navbarViewInfo.customKey);
                        }
                    }
                    navbarViewInfo.customKeyVisible = visible;
                    if (DEBUG) log("setAppKeyVisibility: visible=" + visible);
                }

                // swap / unswap with menu key if necessary
                if ((!mCustomKeyEnabled || !mCustomKeySwapEnabled) &&
                        navbarViewInfo.menuCustomSwapped) {
                    swapMenuAndCustom(navbarViewInfo);
                } else if (mCustomKeyEnabled && mCustomKeySwapEnabled &&
                        !navbarViewInfo.menuCustomSwapped) {
                    swapMenuAndCustom(navbarViewInfo);
                }
            }
        } catch (Throwable t) {
            GravityBox.log(TAG, "Error setting app key visibility: ", t);
        }
    }

    private static void setMenuKeyVisibility() {
        try {
            final boolean showMenu = XposedHelpers.getBooleanField(mNavigationBarView, "mShowMenu");
            final int disabledFlags = XposedHelpers.getIntField(mNavigationBarView, "mDisabledFlags");
            final boolean visible = (showMenu || mAlwaysShowMenukey) &&
                    (disabledFlags & STATUS_BAR_DISABLE_RECENT) == 0;
            for (NavbarViewInfo navbarViewInfo : mNavbarViewInfo) {
                if (navbarViewInfo == null || navbarViewInfo.menuKey == null) continue;

                boolean isImeSwitcherVisible = navbarViewInfo.imeSwitcher != null &&
                        navbarViewInfo.imeSwitcher.getVisibility() == View.VISIBLE;
                navbarViewInfo.menuKey.setVisibility(
                        mDpadKeysVisible || isImeSwitcherVisible ? View.GONE :
                                visible ? View.VISIBLE : View.INVISIBLE);
            }
        } catch (Throwable t) {
            GravityBox.log(TAG, "Error setting menu key visibility:", t);
        }
    }

    private static void hideImeSwitcher() {
        for (NavbarViewInfo navbarViewInfo : mNavbarViewInfo) {
            if (navbarViewInfo != null && navbarViewInfo.imeSwitcher != null) {
                navbarViewInfo.imeSwitcher.setVisibility(View.GONE);
            }
        }
    }

    private static void setDpadKeyVisibility() {
        if (!mCursorControlEnabled) return;
        try {
            final int iconHints = XposedHelpers.getIntField(mNavigationBarView, "mNavigationIconHints");
            final int disabledFlags = XposedHelpers.getIntField(mNavigationBarView, "mDisabledFlags");
            final boolean visible = (disabledFlags & STATUS_BAR_DISABLE_RECENT) == 0 &&
                    (iconHints & NAVIGATION_HINT_BACK_ALT) != 0;
            if (visible == mDpadKeysVisible)
                return;
            mDpadKeysVisible = visible;

            for (NavbarViewInfo navbarViewInfo : mNavbarViewInfo) {
                if (navbarViewInfo == null)
                    continue;
                // hide/unhide app key or whatever view at that position
                if (navbarViewInfo.customKeyParent != navbarViewInfo.endsGroup) {
                    navbarViewInfo.customKeyParent.setVisibility(
                            mDpadKeysVisible ? View.GONE : View.VISIBLE);
                } else {
                    int position = navbarViewInfo.customKeyParent.indexOfChild(
                            navbarViewInfo.customKey);
                    if (position == -1 && navbarViewInfo.customKeyPlaceHolder != null) {
                        position = navbarViewInfo.customKeyParent.indexOfChild(
                                navbarViewInfo.customKeyPlaceHolder);
                    }
                    if (position != -1) {
                        navbarViewInfo.customKeyParent.getChildAt(position).setVisibility(
                                mDpadKeysVisible ? View.GONE : View.VISIBLE);
                    }
                }
                // hide/unhide menu key
                if (navbarViewInfo.menuKey != null) {
                    if (mDpadKeysVisible) {
                        navbarViewInfo.menuKey.setVisibility(View.GONE);
                    } else {
                        setMenuKeyVisibility();
                    }
                }
                // Hide view group holding menu/customkey and ime switcher
                if (navbarViewInfo.menuImeView != null) {
                    navbarViewInfo.menuImeView.setVisibility(
                            mDpadKeysVisible ? View.GONE : View.VISIBLE);
                }
                navbarViewInfo.dpadLeft.setVisibility(mDpadKeysVisible ? View.VISIBLE : View.GONE);
                navbarViewInfo.dpadRight.setVisibility(mDpadKeysVisible ? View.VISIBLE : View.GONE);
                if (DEBUG) log("setDpadKeyVisibility: visible=" + mDpadKeysVisible);
            }
        } catch (Throwable t) {
            GravityBox.log(TAG, "Error setting dpad key visibility: ", t);
        }
    }

    private static void updateRecentsKeyCode() {
        if (mNavbarViewInfo == null || Utils.isParanoidRom()) return;

        try {
            final boolean hasAction = recentsKeyHasAction();
            for (NavbarViewInfo navbarViewInfo : mNavbarViewInfo) {
                if (navbarViewInfo != null && navbarViewInfo.recentsKey != null) {
                    XposedHelpers.setIntField(navbarViewInfo.recentsKey,
                            "mCode", hasAction ? KeyEvent.KEYCODE_APP_SWITCH : 0);
                }
            }
        } catch (Throwable t) {
            GravityBox.log(TAG, t);
        }
    }

    private static boolean recentsKeyHasAction() {
        return (mRecentsSingletapAction.actionId != 0 ||
                mRecentsLongpressAction.actionId != 0 ||
                mRecentsDoubletapAction.actionId != 0 ||
                !mHwKeysEnabled);
    }

    private static void setKeyColor() {
        try {
            View v = (View) XposedHelpers.getObjectField(mNavigationBarView, "mCurrentView");
            ViewGroup navButtons = v.findViewById(
                    mResources.getIdentifier("nav_buttons", "id", PACKAGE_NAME));
            setKeyColorRecursive(navButtons);
        } catch (Throwable t) {
            GravityBox.log(TAG, t);
        }
    }

    private static void setKeyColorRecursive(ViewGroup vg) {
        if (vg == null) return;
        final int childCount = vg.getChildCount();
        for (int i = 0; i < childCount; i++) {
            View child = vg.getChildAt(i);
            if (child instanceof ViewGroup) {
                setKeyColorRecursive((ViewGroup) child);
            } else if (child instanceof ImageView) {
                ImageView imgv = (ImageView)vg.getChildAt(i);
                if (mNavbarColorsEnabled) {
                    imgv.setColorFilter(mKeyColor, PorterDuff.Mode.SRC_ATOP);
                } else {
                    imgv.clearColorFilter();
                }
                if (imgv.getClass().getName().equals(CLASS_KEY_BUTTON_VIEW) &&
                    !mNavbarColorsEnabled) {
                    Drawable ripple = imgv.getBackground();
                    if (ripple != null && 
                            ripple.getClass().getName().equals(CLASS_KEY_BUTTON_RIPPLE)) {
                        Paint paint = (Paint)XposedHelpers.getObjectField(ripple, "mRipplePaint");
                        if (paint != null) {
                            paint.setColor(0xffffffff);
                        }
                    }
                } else if (imgv instanceof KeyButtonView) {
                    ((KeyButtonView) imgv).setGlowColor(mNavbarColorsEnabled ?
                            mKeyGlowColor : mKeyDefaultGlowColor);
                }
            }
        }
    }

    private static void swapBackAndRecents() {
        try {
            for (NavbarViewInfo navbarViewInfo : mNavbarViewInfo) {
                if (navbarViewInfo == null ||
                        navbarViewInfo.endsGroup == null ||
                        navbarViewInfo.recentsView == null ||
                        navbarViewInfo.backView == null) continue;

                View backView = navbarViewInfo.backView;
                View recentsView = navbarViewInfo.recentsView;
                int backPos = navbarViewInfo.endsGroup.indexOfChild(backView);
                int recentsPos = navbarViewInfo.endsGroup.indexOfChild(recentsView);
                navbarViewInfo.endsGroup.removeView(backView);
                navbarViewInfo.endsGroup.removeView(recentsView);
                if (backPos < recentsPos) {
                    navbarViewInfo.endsGroup.addView(recentsView, backPos);
                    navbarViewInfo.endsGroup.addView(backView, recentsPos);
                } else {
                    navbarViewInfo.endsGroup.addView(backView, recentsPos);
                    navbarViewInfo.endsGroup.addView(recentsView, backPos);
                }
            }
        }
        catch (Throwable t) {
            GravityBox.log(TAG, "Error swapping back and recents key: ", t);
        }
    }

    private static void swapMenuAndCustom(NavbarViewInfo nvi) {
        if (!nvi.customKey.isAttachedToWindow() || nvi.menuImeView == null) return;

        try {
            View menuImeView = nvi.menuImeView;
            View customKey = (nvi.endsGroup != nvi.customKeyParent) ? nvi.customKeyParent : nvi.customKey;
            int menuImePos = nvi.endsGroup.indexOfChild(menuImeView);
            int customKeyPos = nvi.endsGroup.indexOfChild(customKey);
            nvi.endsGroup.removeView(menuImeView);
            nvi.endsGroup.removeView(customKey);
            if (menuImePos < customKeyPos) {
                nvi.endsGroup.addView(customKey, menuImePos);
                nvi.endsGroup.addView(menuImeView, customKeyPos);
            } else {
                nvi.endsGroup.addView(menuImeView, customKeyPos);
                nvi.endsGroup.addView(customKey, menuImePos);
            }
            nvi.menuCustomSwapped = !nvi.menuCustomSwapped;
            if (DEBUG) log("swapMenuAndCustom: swapped=" + nvi.menuCustomSwapped);
        }
        catch (Throwable t) {
            GravityBox.log(TAG, "Error swapping menu and custom key: ", t);
        }
    }

    private static void updateCustomKeyIcon() {
        try {
            for (NavbarViewInfo nvi : mNavbarViewInfo) {
                nvi.customKey.setImageDrawable(getCustomKeyIconDrawable());
            }
        } catch (Throwable t) {
            GravityBox.log(TAG, t);
        }
    }

    private static Drawable getCustomKeyIconDrawable() {
        switch (mCustomKeyIconStyle) {
            case CUSTOM:
                File f = new File(mPreferenceDir, "navbar_custom_key_image");
                if (f.exists() && f.canRead()) {
                    Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath());
                    if (b != null) {
                        return new BitmapDrawable(mResources, b);
                    }
                }
                // fall through to transparent if custom not available
            case TRANSPARENT:
                Drawable d = mGbContext.getDrawable(R.drawable.ic_sysbar_apps);
                Drawable transD = new ColorDrawable(Color.TRANSPARENT);
                transD.setBounds(0, 0, d.getMinimumWidth(), d.getMinimumHeight());
                return transD;
            case THREE_DOT: 
                return mGbContext.getDrawable(R.drawable.ic_sysbar_apps2);
            case SIX_DOT:
            default:
                return mGbContext.getDrawable(R.drawable.ic_sysbar_apps);
        }
    }

    private static ScaleType getIconScaleType(int index, int keyId) {
        ScaleType origScaleType = mNavbarViewInfo[index] == null ? ScaleType.CENTER :
                mNavbarViewInfo[index].originalScaleType.get(keyId, ScaleType.CENTER);
        if (index == 0) {
            return (mNavbarHeight < 75 ? ScaleType.CENTER_INSIDE : origScaleType);
        } else {
            boolean hasVerticalNavbar = mGbContext.getResources().getBoolean(R.bool.hasVerticalNavbar);
            return (mNavbarWidth < 75 && hasVerticalNavbar ? ScaleType.CENTER_INSIDE :
                origScaleType);
        }
    }

    private static int[] getIconPaddingPx(int index) {
        int[] p = new int[] { 0, 0, 0, 0 };
        int paddingPx = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 5,
                mResources.getDisplayMetrics()));
        int threshold = Build.VERSION.SDK_INT >= 27 ? 0 : 75;
        boolean hasVerticalNavbar = mGbContext.getResources().getBoolean(R.bool.hasVerticalNavbar);
        if (index == 0) {
            int paddingPxSdk27 = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 15,
                    mResources.getDisplayMetrics()) * (mNavbarHeight / (100f+Math.abs(100f-mNavbarHeight))));
            p[1] = p[3] = mNavbarHeight < threshold ? paddingPx :
                            Build.VERSION.SDK_INT >= 27 ? paddingPxSdk27 : 0;
        }
        if (index == 1 && hasVerticalNavbar) {
            int paddingPxSdk27 = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 15,
                    mResources.getDisplayMetrics()) * (mNavbarWidth / (100f+Math.abs(100f-mNavbarWidth))));
            p[0] = p[2] = mNavbarWidth < threshold ? paddingPx :
                            Build.VERSION.SDK_INT >= 27 ? paddingPxSdk27 : 0;
        }
        return p;
    }

    private static void updateIconScaleType() {
        for (int i = 0; i < mNavbarViewInfo.length; i++) {
            if (mNavbarViewInfo[i] != null && mNavbarViewInfo[i].navButtons != null) {
                updateIconScaleType(i, mNavbarViewInfo[i].navButtons, getIconPaddingPx(i));
            }
        }
    }

    private static void updateIconScaleType(int viewInfoIdx, ViewGroup group, int[] paddingPx) {
        try {
            if (viewInfoIdx == 1 && group == mNavbarViewInfo[viewInfoIdx].menuImeGroup &&
                    Build.VERSION.SDK_INT >=27)
                return;
            int childCount = group.getChildCount();
            for (int j = 0; j < childCount; j++) {
                View child = group.getChildAt(j);
                if (child instanceof ViewGroup) {
                    updateIconScaleType(viewInfoIdx, (ViewGroup)child, paddingPx);
                } else if (child.getClass().getName().equals(CLASS_KEY_BUTTON_VIEW) ||
                        child instanceof KeyButtonView) {
                    ImageView iv = (ImageView) child;
                    if (iv.getId() != View.NO_ID &&
                            mNavbarViewInfo[viewInfoIdx].originalScaleType.get(iv.getId()) == null) {
                        mNavbarViewInfo[viewInfoIdx].originalScaleType.put(iv.getId(),
                                iv.getScaleType());
                    }
                    if (Build.VERSION.SDK_INT < 27) {
                        iv.setScaleType(getIconScaleType(viewInfoIdx, iv.getId()));
                    }
                    if (!Utils.isXperiaDevice()) {
                        iv.setPadding(paddingPx[0], paddingPx[1], paddingPx[2], paddingPx[3]);
                    }
                }
            }
        } catch (Throwable t) {
            GravityBox.log(TAG, "updateIconScaleType: ", t);
        }
    }
}
