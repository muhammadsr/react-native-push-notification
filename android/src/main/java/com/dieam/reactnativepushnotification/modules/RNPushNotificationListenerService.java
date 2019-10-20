package com.dieam.reactnativepushnotification.modules;

import android.app.ActivityManager;
import android.app.ActivityManager.RunningAppProcessInfo;
import android.app.Application;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.widget.RelativeLayout;
import com.dieam.reactnativepushnotification.R;
import com.dieam.reactnativepushnotification.helpers.ApplicationBadgeHelper;
import com.facebook.react.ReactApplication;
import com.facebook.react.ReactInstanceManager;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContext;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import org.json.JSONObject;

import java.util.List;
import java.util.Map;
import java.util.Random;

import static com.dieam.reactnativepushnotification.modules.RNPushNotification.LOG_TAG;

public class RNPushNotificationListenerService extends FirebaseMessagingService {
    private WindowManager mWindowManager;
    private View mOverlayView;
    private MediaPlayer mp;

    @Override
    public void onMessageReceived(RemoteMessage message) {
        Log.i("PN", "onMessageReceived");
        if ((Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || Settings.canDrawOverlays(this)) && !isApplicationInForeground() ) {
            showOverlay();
            playSound();
        }
        RemoteMessage.Notification remoteNotification = message.getNotification();

        final Bundle bundle = new Bundle();
        // Putting it from remoteNotification first so it can be overriden if message
        // data has it
        if (remoteNotification != null) {
            // ^ It's null when message is from GCM
            bundle.putString("title", remoteNotification.getTitle());
            bundle.putString("message", remoteNotification.getBody());
        }

        for(Map.Entry<String, String> entry : message.getData().entrySet()) {
            bundle.putString(entry.getKey(), entry.getValue());
        }
        JSONObject data = getPushData(bundle.getString("data"));
        // Copy `twi_body` to `message` to support Twilio
        if (bundle.containsKey("twi_body")) {
            bundle.putString("message", bundle.getString("twi_body"));
        }

        if (data != null) {
            if (!bundle.containsKey("message")) {
                bundle.putString("message", data.optString("alert", null));
            }
            if (!bundle.containsKey("title")) {
                bundle.putString("title", data.optString("title", null));
            }
            if (!bundle.containsKey("sound")) {
                bundle.putString("soundName", data.optString("sound", null));
            }
            if (!bundle.containsKey("color")) {
                bundle.putString("color", data.optString("color", null));
            }

            final int badge = data.optInt("badge", -1);
            if (badge >= 0) {
                ApplicationBadgeHelper.INSTANCE.setApplicationIconBadgeNumber(this, badge);
            }
        }

        Log.v(LOG_TAG, "onMessageReceived: " + bundle);

        // We need to run this on the main thread, as the React code assumes that is true.
        // Namely, DevServerHelper constructs a Handler() without a Looper, which triggers:
        // "Can't create handler inside thread that has not called Looper.prepare()"
        Handler handler = new Handler(Looper.getMainLooper());
        handler.post(new Runnable() {
            public void run() {
                // Construct and load our normal React JS code bundle
                ReactInstanceManager mReactInstanceManager = ((ReactApplication) getApplication()).getReactNativeHost().getReactInstanceManager();
                ReactContext context = mReactInstanceManager.getCurrentReactContext();
                // If it's constructed, send a notification
                if (context != null) {
                    handleRemotePushNotification((ReactApplicationContext) context, bundle);
                } else {
                    // Otherwise wait for construction, then send the notification
                    mReactInstanceManager.addReactInstanceEventListener(new ReactInstanceManager.ReactInstanceEventListener() {
                        public void onReactContextInitialized(ReactContext context) {
                            handleRemotePushNotification((ReactApplicationContext) context, bundle);
                        }
                    });
                    if (!mReactInstanceManager.hasStartedCreatingInitialContext()) {
                        // Construct it in the background
                        mReactInstanceManager.createReactContextInBackground();
                    }
                }
            }
        });
    }

    public void showOverlay() {
        mOverlayView = LayoutInflater.from(this).inflate(R.layout.alert_layout, null);

        WindowManager.LayoutParams temp = null;

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            temp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_PHONE,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT);
        }else{
            temp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT);
        }

        final WindowManager.LayoutParams params = temp;

        //Specify the view position
        params.gravity = Gravity.TOP | Gravity.LEFT;        //Initially view will be added to top-left corner
        params.x = 0;
        params.y = 100;


        mWindowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                mWindowManager.addView(mOverlayView, params);
            }
        });

        Display display = mWindowManager.getDefaultDisplay();
        final Point size = new Point();
        display.getSize(size);

        mOverlayView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                String activityToStart = "com.safary.MainActivity";
                try {
                    Class<?> c = Class.forName(activityToStart);
                    Intent intent = new Intent(getApplicationContext(), c);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                    mWindowManager.removeView(mOverlayView);
                    stopPlaySound();
                } catch (ClassNotFoundException ignored) {
                }
            }
        });

    }

    private void playSound() {
        if (mp == null) {
            mp = MediaPlayer.create(this, R.raw.alert1);
        }
        mp.setLooping(true);
        mp.start();
    }

    private void stopPlaySound() {
        if (mp != null) {
            mp.stop();
            mp.release();
            mp = null;
        }
    }

    private JSONObject getPushData(String dataString) {
        try {
            return new JSONObject(dataString);
        } catch (Exception e) {
            return null;
        }
    }

    private void handleRemotePushNotification(ReactApplicationContext context, Bundle bundle) {

        // If notification ID is not provided by the user for push notification, generate one at random
        if (bundle.getString("id") == null) {
            Random randomNumberGenerator = new Random(System.currentTimeMillis());
            bundle.putString("id", String.valueOf(randomNumberGenerator.nextInt()));
        }

        Boolean isForeground = isApplicationInForeground();

        RNPushNotificationJsDelivery jsDelivery = new RNPushNotificationJsDelivery(context);
        bundle.putBoolean("foreground", isForeground);
        bundle.putBoolean("userInteraction", false);
        jsDelivery.notifyNotification(bundle);

        // If contentAvailable is set to true, then send out a remote fetch event
        if (bundle.getString("contentAvailable", "false").equalsIgnoreCase("true")) {
            jsDelivery.notifyRemoteFetch(bundle);
        }

        Log.v(LOG_TAG, "sendNotification: " + bundle);

        Application applicationContext = (Application) context.getApplicationContext();
        RNPushNotificationHelper pushNotificationHelper = new RNPushNotificationHelper(applicationContext);
        pushNotificationHelper.sendToNotificationCentre(bundle);
    }



    private boolean isApplicationInForeground() {
        ActivityManager activityManager = (ActivityManager) this.getSystemService(ACTIVITY_SERVICE);
        List<RunningAppProcessInfo> processInfos = activityManager.getRunningAppProcesses();
        if (processInfos != null) {
            for (RunningAppProcessInfo processInfo : processInfos) {
                if (processInfo.processName.equals(getApplication().getPackageName())) {
                    if (processInfo.importance == RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
                        for (String d : processInfo.pkgList) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }
}
