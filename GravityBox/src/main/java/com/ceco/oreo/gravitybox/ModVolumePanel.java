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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.os.Build;
import android.view.View;
import android.widget.ImageButton;

import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class ModVolumePanel {
    private static final String TAG = "GB:ModVolumePanel";
    public static final String PACKAGE_NAME = "com.android.systemui";
    private static final String CLASS_VOLUME_PANEL = Utils.isSamsungRom() ?
            "com.android.systemui.volume.SecVolumeDialogImpl" :
            "com.android.systemui.volume.VolumeDialogImpl";
    private static final String CLASS_VOLUME_ROW = CLASS_VOLUME_PANEL + ".VolumeRow";
    private static final String CLASS_VOLUME_PANEL_CTRL = "com.android.systemui.volume.VolumeDialogControllerImpl";
    private static final String CLASS_VOLUME_DIALOG_MOTION = "com.android.systemui.volume.VolumeDialogMotion";
    private static final boolean DEBUG = false;

    private static Object mVolumePanel;
    private static boolean mVolumeAdjustVibrateMuted;
    private static boolean mAutoExpand;
    private static int mTimeout;
    private static boolean mVolumesLinked;

    private static void log(String message) {
        XposedBridge.log(TAG + ": " + message);
    }

    private static BroadcastReceiver mBrodcastReceiver = new BroadcastReceiver() {

        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(GravityBoxSettings.ACTION_PREF_VOLUME_PANEL_MODE_CHANGED)) {
                if (intent.hasExtra(GravityBoxSettings.EXTRA_AUTOEXPAND)) {
                    mAutoExpand = intent.getBooleanExtra(GravityBoxSettings.EXTRA_AUTOEXPAND, false);
                }
                if (intent.hasExtra(GravityBoxSettings.EXTRA_VIBRATE_MUTED)) {
                    mVolumeAdjustVibrateMuted = intent.getBooleanExtra(GravityBoxSettings.EXTRA_VIBRATE_MUTED, false);
                }
                if (intent.hasExtra(GravityBoxSettings.EXTRA_TIMEOUT)) {
                    mTimeout = intent.getIntExtra(GravityBoxSettings.EXTRA_TIMEOUT, 0);
                }
            }
            else if (intent.getAction().equals(GravityBoxSettings.ACTION_PREF_LINK_VOLUMES_CHANGED)) {
                mVolumesLinked = intent.getBooleanExtra(GravityBoxSettings.EXTRA_LINKED, true);
            }
        }
        
    };

    public static void init(final XSharedPreferences prefs, final ClassLoader classLoader) {
        try {
            final Class<?> classVolumePanel = XposedHelpers.findClass(CLASS_VOLUME_PANEL, classLoader);
            final Class<?> classVolumePanelCtrl = XposedHelpers.findClass(CLASS_VOLUME_PANEL_CTRL, classLoader);

            mVolumeAdjustVibrateMuted = prefs.getBoolean(GravityBoxSettings.PREF_KEY_VOLUME_ADJUST_VIBRATE_MUTE, false);
            mAutoExpand = prefs.getBoolean(GravityBoxSettings.PREF_KEY_VOLUME_PANEL_AUTOEXPAND, false);
            mVolumesLinked = prefs.getBoolean(GravityBoxSettings.PREF_KEY_LINK_VOLUMES, true);

            XposedBridge.hookAllConstructors(classVolumePanel, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(final MethodHookParam param) {
                    mVolumePanel = param.thisObject;
                    Context context = (Context) XposedHelpers.getObjectField(mVolumePanel, "mContext");
                    if (DEBUG) log("VolumePanel constructed; mVolumePanel set");

                    mTimeout = prefs.getInt(
                            GravityBoxSettings.PREF_KEY_VOLUME_PANEL_TIMEOUT, 0);

                    IntentFilter intentFilter = new IntentFilter();
                    intentFilter.addAction(GravityBoxSettings.ACTION_PREF_VOLUME_PANEL_MODE_CHANGED);
                    intentFilter.addAction(GravityBoxSettings.ACTION_PREF_LINK_VOLUMES_CHANGED);
                    context.registerReceiver(mBrodcastReceiver, intentFilter);
                }
            });

            XposedHelpers.findAndHookMethod(classVolumePanel, "initDialog", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(final MethodHookParam param) {
                    if (!Utils.isSamsungRom()) {
                        prepareNotificationRow();
                    }
                }
            });

            XposedHelpers.findAndHookMethod(CLASS_VOLUME_DIALOG_MOTION, classLoader,
                    "setShowing", boolean.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(final MethodHookParam param) {
                    if (mAutoExpand && !(boolean)param.args[0] &&
                            !XposedHelpers.getBooleanField(param.thisObject, "mDismissing")) {
                        ImageButton expandBtn = (ImageButton) XposedHelpers.getObjectField(
                                mVolumePanel, "mExpandButton");
                        expandBtn.performClick();
                    }
                }
            });

            XposedHelpers.findAndHookMethod(classVolumePanel, "computeTimeoutH", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(final MethodHookParam param) {
                    if (mTimeout != 0) {
                        param.setResult(mTimeout);
                    }
                }
            });

            if (!Utils.isSamsungRom()) {
                XposedHelpers.findAndHookMethod(classVolumePanelCtrl, "vibrate", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(final MethodHookParam param) {
                        if (mVolumeAdjustVibrateMuted) {
                            param.setResult(null);
                        }
                    }
                });
            }

            XC_MethodHook shouldBeVisibleHook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(final MethodHookParam param) {
                    if (XposedHelpers.getAdditionalInstanceField(
                            param.args[0], "gbNotifSlider") != null) {
                        boolean visible = (boolean) param.getResult();
                        visible &= !mVolumesLinked;
                        param.setResult(visible);
                    }
                }
            };
            if (Build.VERSION.SDK_INT >= 27) {
                XposedHelpers.findAndHookMethod(classVolumePanel, "shouldBeVisibleH",
                        CLASS_VOLUME_ROW, CLASS_VOLUME_ROW, shouldBeVisibleHook);
            } else {
                XposedHelpers.findAndHookMethod(classVolumePanel, "shouldBeVisibleH",
                        CLASS_VOLUME_ROW, boolean.class, shouldBeVisibleHook);
            }

            XposedHelpers.findAndHookMethod(classVolumePanel, "updateVolumeRowSliderH",
                    CLASS_VOLUME_ROW, boolean.class, int.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(final MethodHookParam param) {
                    if (!mVolumesLinked && XposedHelpers.getAdditionalInstanceField(
                            param.args[0], "gbNotifSlider") != null) {
                        View slider = (View) XposedHelpers.getObjectField(param.args[0], "slider");
                        slider.setEnabled(isRingerSliderEnabled());
                        View icon = (View) XposedHelpers.getObjectField(param.args[0], "icon");
                        icon.setEnabled(slider.isEnabled());
                    }
                }
            });
        } catch (Throwable t) {
            GravityBox.log(TAG, t);
        }
    }

    private static void prepareNotificationRow() {
        try {
            XposedHelpers.callMethod(mVolumePanel, "addRow",
                    AudioManager.STREAM_NOTIFICATION,
                    ResourceProxy.getFakeResId("ic_audio_notification"),
                    ResourceProxy.getFakeResId("ic_audio_notification_mute"),
                    true);
            List<?> rows = (List<?>) XposedHelpers.getObjectField(mVolumePanel, "mRows");
            Object row = rows.get(rows.size() - 1);
            XposedHelpers.setAdditionalInstanceField(row, "gbNotifSlider", true);
        } catch (Throwable t) {
            GravityBox.log(TAG, t);
        }
    }

    private static boolean isRingerSliderEnabled() {
        try {
            List<?> rows = (List<?>) XposedHelpers.getObjectField(mVolumePanel, "mRows");
            for (Object row : rows) {
                if (XposedHelpers.getIntField(row, "stream") == AudioManager.STREAM_RING) {
                    return ((View)XposedHelpers.getObjectField(row, "slider")).isEnabled();
                }
            }
            return true;
        } catch (Throwable t) {
            GravityBox.log(TAG, t);
            return true;
        }
    }
}
