
package nl.hnogames.domoticzapi.Utils;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import nl.hnogames.domoticzapi.Interfaces.WifiSSIDListener;

public class PhoneConnectionUtil {
    private WifiManager wifiManager;
    private Context mContext;
    private WifiSSIDListener listener;
    private BroadcastReceiver receiver;
    private AtomicBoolean unregistered;

    public PhoneConnectionUtil(Context mContext, final WifiSSIDListener listener) {
        if (mContext == null)
            return;
        this.mContext = mContext;
        wifiManager = (WifiManager) mContext.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        this.listener = listener;
    }

    public PhoneConnectionUtil(Context mContext) {
        if (mContext == null)
            return;
        this.mContext = mContext;
        wifiManager = (WifiManager) mContext.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
    }

    public void stopReceiver() {
        try {
            if (receiver != null) {
                synchronized (unregistered) {
                    if (!unregistered.get()) {
                        mContext.unregisterReceiver(receiver);
                        unregistered.set(true);
                    }
                }
            }
        } catch (Exception ex) {
            receiver = null;
        }
    }

    public void startSsidScan() {
        wifiManager.startScan();
        unregistered = new AtomicBoolean(false);
        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                List<ScanResult> results = wifiManager.getScanResults();
                CharSequence[] entries = new CharSequence[0];

                if (results != null && results.size() > 0) {
                    entries = new CharSequence[results.size()];

                    int i = 0;
                    for (ScanResult result : results) {
                        if (result.SSID != null && result.SSID.length() > 0) {
                            entries[i] = result.SSID;
                            i++;
                        }
                    }
                }
                if (listener != null)
                    listener.ReceiveSSIDs(entries);
            }
        };
        Executors.newSingleThreadScheduledExecutor().schedule(new Runnable() {
            @Override
            public void run() {
                stopReceiver();
            }
        }, 30, TimeUnit.SECONDS);

        mContext.registerReceiver(receiver, new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION));
    }

    @SuppressWarnings("unused")
    public boolean isCellConnected() {
        ConnectivityManager connectivityManager = getConnectivityManager();
        if (connectivityManager == null) {
            return false;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Network activeNetwork = connectivityManager.getActiveNetwork();
            if (activeNetwork == null) {
                return false;
            }

            NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(activeNetwork);
            return capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR);
        }

        NetworkInfo networkCellInfo = connectivityManager.getNetworkInfo(ConnectivityManager.TYPE_MOBILE);
        return networkCellInfo != null && networkCellInfo.isConnected();
    }

    public boolean isWifiConnected() {
        ConnectivityManager connectivityManager = getConnectivityManager();
        if (connectivityManager == null) {
            return false;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Network activeNetwork = connectivityManager.getActiveNetwork();
            if (activeNetwork == null) {
                return false;
            }

            NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(activeNetwork);
            return capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
        }

        NetworkInfo networkWifiInfo = connectivityManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
        return networkWifiInfo != null && networkWifiInfo.isConnected();
    }

    public String getCurrentSsid() {
        String ssid = null;

        if (isWifiConnected()) {
            final WifiInfo connectionInfo = wifiManager.getConnectionInfo();
            if (connectionInfo != null && connectionInfo.getSSID() != null && !connectionInfo.getSSID().isEmpty()) {
                ssid = connectionInfo.getSSID();
            }
        }
        return ssid;
    }

    public boolean isNetworkAvailable() {
        ConnectivityManager connectivityManager = getConnectivityManager();
        if (connectivityManager == null) {
            return false;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Network activeNetwork = connectivityManager.getActiveNetwork();
            if (activeNetwork == null) {
                return false;
            }
            NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(activeNetwork);
            // Use transport-based check instead of NET_CAPABILITY_VALIDATED to avoid false
            // negatives on Android 17+ where IPv6 validation failures can cause VALIDATED
            // to not be set even though the network is fully functional for local (IPv4) access.
            return capabilities != null
                    && (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                    || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                    || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN));
        }

        NetworkInfo activeNetworkInfo = connectivityManager.getActiveNetworkInfo();
        return activeNetworkInfo != null && activeNetworkInfo.isConnected();
    }

    private ConnectivityManager getConnectivityManager() {
        return (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
    }
}
