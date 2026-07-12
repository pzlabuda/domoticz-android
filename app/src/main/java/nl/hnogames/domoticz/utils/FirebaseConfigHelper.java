package nl.hnogames.domoticz.utils;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.annotation.NonNull;

import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;

import nl.hnogames.domoticz.MainActivity;
import nl.hnogames.domoticz.app.AppController;

public class FirebaseConfigHelper {
    private static final String TAG = "FirebaseConfigHelper";
    private static final Object FIREBASE_INIT_LOCK = new Object();

    public interface TestCallback {
        void onSuccess(String token);
        void onError(String error);
    }

    /**
     * Initialize Firebase with user-provided configuration
     */
    public static boolean initializeFirebase(Context context, SharedPrefUtil prefs) {
        if (!prefs.hasFirebaseConfig()) {
            Log.d(TAG, "No Firebase configuration found");
            return false;
        }

        synchronized (FIREBASE_INIT_LOCK) {
            try {
                // Default app already initialized in this process.
                // Keep it alive to avoid repeated teardown/re-init races.
                FirebaseApp defaultApp = FirebaseApp.getInstance();
                if (defaultApp != null) {
                    Log.d(TAG, "Firebase already initialized");
                    return true;
                }
            } catch (IllegalStateException e) {
                Log.d(TAG, "No existing Firebase app, creating one");
            }

            try {
                FirebaseOptions options = new FirebaseOptions.Builder()
                        .setProjectId(prefs.getFcmProjectId())
                        .setApplicationId(prefs.getFcmAppId())
                        .setApiKey(prefs.getFcmApiKey())
                        .setGcmSenderId(prefs.getFcmSenderId())
                        .build();

                FirebaseApp app = FirebaseApp.initializeApp(context.getApplicationContext(), options);
                if (app == null) {
                    Log.e(TAG, "Firebase initialization returned null app");
                    return false;
                }

                Log.d(TAG, "Firebase initialized successfully with user configuration");
                getFirebaseToken(context.getApplicationContext(), new TestCallback() {
                    @Override
                    public void onSuccess(String token) {
                        GCMUtils.sendRegistrationIdToBackend(context.getApplicationContext(), token);
                    }

                    @Override
                    public void onError(String error) {
                        Log.e(TAG, "Failed to retrieve FCM token after initialization: " + error);
                    }
                });
                return true;
            } catch (Exception e) {
                Log.e(TAG, "Firebase initialization failed", e);
                return false;
            }
        }
    }

    /**
     * Get Firebase messaging instance
     */
    public static FirebaseMessaging getFirebaseMessaging() {
        try {
            // After initializing FirebaseApp, the default instance is used
            return FirebaseMessaging.getInstance();
        } catch (IllegalStateException e) {
            Log.e(TAG, "Firebase not initialized", e);
            return null;
        }
    }

    /**
     * Get FCM token
     */
    public static void getFirebaseToken(Context context, TestCallback callback) {
        try {
            FirebaseMessaging messaging = getFirebaseMessaging();
            if (messaging == null) {
                if (callback != null) {
                    callback.onError("Firebase Messaging not initialized");
                }
                return;
            }

            messaging.getToken().addOnCompleteListener(new OnCompleteListener<String>() {
                @Override
                public void onComplete(@NonNull Task<String> task) {
                    if (task.isSuccessful() && task.getResult() != null) {
                        String token = task.getResult();
                        if (callback != null) {
                            callback.onSuccess(token);
                        }
                    } else {
                        String error = task.getException() != null ?
                                task.getException().getMessage() : "Unknown error";
                        if (callback != null) {
                            callback.onError(error);
                        }
                    }
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Error getting Firebase token", e);
            if (callback != null) {
                callback.onError(e.getMessage());
            }
        }
    }

    /**
     * Test the Firebase configuration
     */
    public static void testConfiguration(Context context, TestCallback callback) {
        SharedPrefUtil prefs = new SharedPrefUtil(context);

        if (!prefs.hasFirebaseConfig()) {
            if (callback != null) {
                callback.onError("No Firebase configuration found");
            }
            return;
        }

        boolean initialized = initializeFirebase(context, prefs);
        if (!initialized) {
            if (callback != null) {
                callback.onError("Failed to initialize Firebase");
            }
            return;
        }

        getFirebaseToken(context, callback);
    }

    /**
     * Check if Firebase is configured
     */
    public static boolean isFirebaseConfigured(Context context) {
        SharedPrefUtil prefs = new SharedPrefUtil(context);
        return prefs.hasFirebaseConfig();
    }
}

