/*
 * Copyright (C) 2011 4th Line GmbH, Switzerland
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 2 of
 * the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.fourthline.cling.android;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;
import org.fourthline.cling.UpnpServiceConfiguration;
import org.fourthline.cling.model.ModelUtil;
import org.fourthline.cling.protocol.ProtocolFactory;
import org.fourthline.cling.transport.Router;
import org.fourthline.cling.transport.SwitchableRouterImpl;
import org.fourthline.cling.transport.spi.InitializationException;

import java.lang.reflect.Field;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Monitors all network connectivity changes, switching the router accordingly.
 *
 * @author Michael Pujos
 * @author Christian Bauer
 */
public class AndroidSwitchableRouter extends SwitchableRouterImpl {

    final private static Logger log = Logger.getLogger(Router.class.getName());

    final private Context context;

    final private WifiManager wifiManager;
    protected WifiManager.MulticastLock multicastLock;
    protected WifiManager.WifiLock wifiLock;
    protected NetworkInfo networkInfo;
    protected BroadcastReceiver broadcastReceiver;

    public AndroidSwitchableRouter(UpnpServiceConfiguration configuration,
                                   ProtocolFactory protocolFactory,
                                   Context context) throws InitializationException {
        super(configuration, protocolFactory);

        this.context = context;
        this.wifiManager = ((WifiManager) context.getSystemService(Context.WIFI_SERVICE));
        this.networkInfo = NetworkUtils.getConnectedNetworkInfo(context);

        // Start the router!
        onNetworkTypeChange(null, networkInfo);

        // Only register for network connectivity changes if we are not running on emulator
        if (!ModelUtil.ANDROID_EMULATOR) {
            this.broadcastReceiver = new ConnectivityBroadcastReceiver();
            context.registerReceiver(broadcastReceiver, new IntentFilter("android.net.conn.CONNECTIVITY_CHANGE"));
        }
    }

    @Override
    protected int getLockTimeoutMillis() {
        return 15000;
    }

    @Override
    public void shutdown() {
        super.shutdown();
        if (broadcastReceiver != null) {
            context.unregisterReceiver(broadcastReceiver);
        }
    }

    @Override
    public boolean enable() throws RouterLockAcquisitionException {
        lock(writeLock);
        try {
            boolean enabled;
            if ((enabled = super.enable())) {
                // Enable multicast on the WiFi network interface,
                // requires android.permission.CHANGE_WIFI_MULTICAST_STATE
                if (isWifi()) {
                    setWiFiMulticastLock(true);
                    setWifiLock(true);
                }
            }
            return enabled;
        } finally {
            unlock(writeLock);
        }
    }

    @Override
    public boolean disable() throws RouterLockAcquisitionException {
        lock(writeLock);
        try {
            // Disable multicast on WiFi network interface,
            // requires android.permission.CHANGE_WIFI_MULTICAST_STATE
            if (isWifi()) {
                setWiFiMulticastLock(false);
                setWifiLock(false);
            }
            return super.disable();
        } finally {
            unlock(writeLock);
        }
    }

    public NetworkInfo getNetworkInfo() {
        return networkInfo;
    }

    public boolean isMobile() {
        return NetworkUtils.isMobile(networkInfo);
    }

    public boolean isWifi() {
        return NetworkUtils.isWifi(networkInfo);
    }

    public boolean isEthernet() {
        return NetworkUtils.isEthernet(networkInfo);
    }

    public boolean enableWiFi() {
        log.info("Enabling WiFi...");
        try {
            return wifiManager.setWifiEnabled(true);
        } catch (Throwable t) {
            // workaround (HTC One X, 4.0.3)
            //java.lang.SecurityException: Permission Denial: writing com.android.providers.settings.SettingsProvider
            // uri content://settings/system from pid=4691, uid=10226 requires android.permission.WRITE_SETTINGS
            //	at android.os.Parcel.readException(Parcel.java:1332)
            //	at android.os.Parcel.readException(Parcel.java:1286)
            //	at android.net.wifi.IWifiManager$Stub$Proxy.setWifiEnabled(IWifiManager.java:1115)
            //	at android.net.wifi.WifiManager.setWifiEnabled(WifiManager.java:946)
            log.log(Level.WARNING, "SetWifiEnabled failed", t);
            return false;
        }
    }

    protected void setWiFiMulticastLock(boolean enable) {
        if (multicastLock == null) {
            multicastLock = wifiManager.createMulticastLock(getClass().getSimpleName());
        }

        if (enable) {
            if (multicastLock.isHeld()) {
                log.warning("WiFi multicast lock already acquired");
            } else {
                log.info("WiFi multicast lock acquired");
                multicastLock.acquire();
            }
        } else {
            if (multicastLock.isHeld()) {
                log.info("WiFi multicast lock released");
                multicastLock.release();
            } else {
                log.warning("WiFi multicast lock already released");
            }
        }
    }

    protected void setWifiLock(boolean enable) {
        if (wifiLock == null) {
            wifiLock = createWiFiLock();
        }

        if (enable) {
            if (wifiLock.isHeld()) {
                log.warning("WiFi lock already acquired");
            } else {
                log.info("WiFi lock acquired");
                wifiLock.acquire();
            }
        } else {
            if (wifiLock.isHeld()) {
                log.info("WiFi lock released");
                wifiLock.release();
            } else {
                log.warning("WiFi lock already released");
            }
        }
    }

    protected WifiManager.WifiLock createWiFiLock() {
        int wifiMode = WifiManager.WIFI_MODE_FULL;
        try {
            // TODO: What's this?
            Field f = WifiManager.class.getField("WIFI_MODE_FULL_HIGH_PERF");
            wifiMode = f.getInt(null);
        } catch (Exception e) {
            // Ignore
        }
        WifiManager.WifiLock wifiLock =
            wifiManager.createWifiLock(wifiMode, getClass().getSimpleName());
        log.info("Created WiFi lock, mode: " + wifiMode);
        return wifiLock;
    }

    /**
     * Can be overriden by subclasses to do additional work.
     *
     * @param oldNetwork <code>null</code> when first called by constructor.
     */
    protected void onNetworkTypeChange(NetworkInfo oldNetwork, NetworkInfo newNetwork) {
        log.info(String.format("Network type changed %s => %s",
            oldNetwork == null ? "" : oldNetwork.getTypeName(),
            newNetwork == null ? "NONE" : newNetwork.getTypeName()));

        if (disable()) {
            log.info(String.format(
                "Disabled router on network type change (old network: %s)",
                oldNetwork == null ? "NONE" : oldNetwork.getTypeName()
            ));
        }

        networkInfo = newNetwork;
        if (enable()) {
            // Can return false (via earlier InitializationException thrown by NetworkAddressFactory) if
            // no bindable network address found!
            log.info(String.format(
                "Enabled router on network type change (new network: %s)",
                newNetwork == null ? "NONE" : newNetwork.getTypeName()
            ));
        }
    }

    class ConnectivityBroadcastReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {

            if (!intent.getAction().equals(ConnectivityManager.CONNECTIVITY_ACTION))
                return;

            displayIntentInfo(intent);

            NetworkInfo newNetworkInfo = NetworkUtils.getConnectedNetworkInfo(context);

            // When Android switches WiFI => MOBILE, sometimes we may have a short transition
            // with no network: WIFI => NONE, NONE => MOBILE
            // The code below attempts to make it look like a single WIFI => MOBILE
            // transition, retrying up to 3 times getting the current network.
            //
            // Note: this can block the UI thread for up to 3s
            if (networkInfo != null && newNetworkInfo == null) {
                for (int i = 1; i <= 3; i++) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        return;
                    }
                    log.warning(String.format(
                        "%s => NONE network transition, waiting for new network... retry #%d",
                        networkInfo.getTypeName(), i
                    ));
                    newNetworkInfo = NetworkUtils.getConnectedNetworkInfo(context);
                    if (newNetworkInfo != null)
                        break;
                }
            }

            if (isSameNetworkType(networkInfo, newNetworkInfo)) {
                log.info("No actual network change... ignoring event!");
            } else {
                onNetworkTypeChange(networkInfo, newNetworkInfo);
            }
        }

        protected boolean isSameNetworkType(NetworkInfo network1, NetworkInfo network2) {
            if (network1 == null && network2 == null)
                return true;
            if (network1 == null || network2 == null)
                return false;
            return network1.getType() == network2.getType();
        }

        protected void displayIntentInfo(Intent intent) {
            boolean noConnectivity = intent.getBooleanExtra(ConnectivityManager.EXTRA_NO_CONNECTIVITY, false);
            String reason = intent.getStringExtra(ConnectivityManager.EXTRA_REASON);
            boolean isFailover = intent.getBooleanExtra(ConnectivityManager.EXTRA_IS_FAILOVER, false);

            NetworkInfo currentNetworkInfo = (NetworkInfo) intent.getParcelableExtra(ConnectivityManager.EXTRA_NETWORK_INFO);
            NetworkInfo otherNetworkInfo = (NetworkInfo) intent.getParcelableExtra(ConnectivityManager.EXTRA_OTHER_NETWORK_INFO);

            log.info("Connectivity change detected...");
            log.info("EXTRA_NO_CONNECTIVITY: " + noConnectivity);
            log.info("EXTRA_REASON: " + reason);
            log.info("EXTRA_IS_FAILOVER: " + isFailover);
            log.info("EXTRA_NETWORK_INFO: " + (currentNetworkInfo == null ? "none" : currentNetworkInfo));
            log.info("EXTRA_OTHER_NETWORK_INFO: " + (otherNetworkInfo == null ? "none" : otherNetworkInfo));
            log.info("EXTRA_EXTRA_INFO: " + intent.getStringExtra(ConnectivityManager.EXTRA_EXTRA_INFO));
        }
    }
}