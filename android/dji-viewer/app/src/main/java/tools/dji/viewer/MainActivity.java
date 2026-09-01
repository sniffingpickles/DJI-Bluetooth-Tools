package tools.dji.viewer;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class MainActivity extends Activity implements SurfaceHolder.Callback {
    private static final String PREFS = "viewer";
    private static final String PREF_CAMERA_IP = "camera_ip";
    private static final int REQUEST_RADIO_PERMISSIONS = 42;

    private SurfaceView videoView;
    private TextView targetView;
    private Button connectButton;
    private TextView statusView;
    private TextView statsView;
    private CredentialStore credentialStore;
    private CredentialStore.Credentials pendingCredentials;
    private volatile BleStationConnector bleConnector;
    private volatile DjiUdpSession session;
    private volatile LiveViewDepacketizer depacketizer;
    private volatile HevcDecoder decoder;
    private WifiManager.WifiLock wifiLock;
    private boolean surfaceReady;
    private volatile boolean connecting;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        credentialStore = new CredentialStore(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(Color.rgb(5, 7, 8));
        getWindow().setNavigationBarColor(Color.rgb(5, 7, 8));
        buildUi();
    }

    private void buildUi() {
        int padding = dp(14);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(padding, dp(8), padding, dp(10));
        root.setBackgroundColor(Color.rgb(5, 7, 8));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int left;
            int top;
            int right;
            int bottom;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                left = bars.left;
                top = bars.top;
                right = bars.right;
                bottom = bars.bottom;
            } else {
                left = insets.getSystemWindowInsetLeft();
                top = insets.getSystemWindowInsetTop();
                right = insets.getSystemWindowInsetRight();
                bottom = insets.getSystemWindowInsetBottom();
            }
            view.setPadding(padding + left, dp(8) + top, padding + right, dp(10) + bottom);
            return insets;
        });

        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        controls.setOrientation(LinearLayout.HORIZONTAL);

        TextView title = new TextView(this);
        title.setText("DJI VIEWER");
        title.setTextColor(Color.WHITE);
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        controls.addView(title, new LinearLayout.LayoutParams(dp(122), dp(48)));

        targetView = new TextView(this);
        targetView.setText("Automatic BLE → Wi-Fi → UDP");
        targetView.setTextColor(Color.LTGRAY);
        targetView.setTextSize(15);
        targetView.setGravity(Gravity.CENTER_VERTICAL);
        targetView.setPadding(dp(8), 0, dp(8), 0);
        LinearLayout.LayoutParams targetParams = new LinearLayout.LayoutParams(0, dp(48), 1);
        targetParams.setMarginEnd(dp(10));
        controls.addView(targetView, targetParams);

        connectButton = new Button(this);
        connectButton.setText("AUTO CONNECT");
        connectButton.setTextColor(Color.BLACK);
        connectButton.setTextSize(12);
        connectButton.setBackgroundColor(Color.rgb(84, 214, 163));
        connectButton.setOnClickListener(view -> {
            if (session != null || bleConnector != null || connecting) disconnect();
            else prepareAutoConnect(false);
        });
        connectButton.setOnLongClickListener(view -> {
            if (!connecting && session == null) showCredentialDialog(true);
            return true;
        });
        controls.addView(connectButton, new LinearLayout.LayoutParams(dp(150), dp(48)));
        root.addView(controls, new LinearLayout.LayoutParams(-1, dp(52)));

        FrameLayout videoArea = new FrameLayout(this);
        videoArea.setBackgroundColor(Color.BLACK);
        AspectRatioFrameLayout videoFrame = new AspectRatioFrameLayout(this);
        videoFrame.setBackgroundColor(Color.BLACK);
        videoView = new SurfaceView(this);
        videoView.getHolder().addCallback(this);
        videoFrame.addView(videoView, new FrameLayout.LayoutParams(-1, -1));

        TextView badge = new TextView(this);
        badge.setText("DIRECT OA4 HEVC  •  16:9  •  VIDEO ONLY / NO AUDIO");
        badge.setTextColor(Color.WHITE);
        badge.setTextSize(12);
        badge.setPadding(dp(10), dp(6), dp(10), dp(6));
        badge.setBackgroundColor(Color.argb(185, 8, 13, 15));
        FrameLayout.LayoutParams badgeParams = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        badgeParams.setMargins(dp(10), dp(10), 0, 0);
        videoFrame.addView(badge, badgeParams);
        videoArea.addView(videoFrame, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        root.addView(videoArea, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        statusView = new TextView(this);
        statusView.setText("Ready — tap Auto Connect; long-press to change Wi-Fi");
        statusView.setTextColor(Color.LTGRAY);
        statusView.setTextSize(13);
        footer.addView(statusView, new LinearLayout.LayoutParams(0, dp(38), 1));
        statsView = new TextView(this);
        statsView.setText("1280×720 • ~30 fps • silent");
        statsView.setTextColor(Color.rgb(84, 214, 163));
        statsView.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        statsView.setTextSize(13);
        footer.addView(statsView, new LinearLayout.LayoutParams(dp(330), dp(38)));
        root.addView(footer, new LinearLayout.LayoutParams(-1, dp(40)));
        setContentView(root);
    }

    private void prepareAutoConnect(boolean forceSettings) {
        CredentialStore.Credentials credentials = credentialStore.load();
        if (forceSettings || credentials == null) showCredentialDialog(false);
        else ensurePermissionsAndConnect(credentials);
    }

    private void showCredentialDialog(boolean settingsOnly) {
        CredentialStore.Credentials existing = credentialStore.load();
        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(20);
        fields.setPadding(padding, dp(6), padding, 0);

        EditText ssid = new EditText(this);
        ssid.setHint("Wi-Fi SSID (case-sensitive)");
        ssid.setSingleLine(true);
        ssid.setInputType(InputType.TYPE_CLASS_TEXT);
        if (existing != null) ssid.setText(existing.ssid);
        fields.addView(ssid, new LinearLayout.LayoutParams(-1, dp(56)));

        EditText password = new EditText(this);
        password.setHint("Wi-Fi password");
        password.setSingleLine(true);
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        if (existing != null) password.setText(existing.password);
        fields.addView(password, new LinearLayout.LayoutParams(-1, dp(56)));

        new AlertDialog.Builder(this)
                .setTitle("Camera station Wi-Fi")
                .setMessage("The OA4 will join this same LAN. Credentials are encrypted with Android Keystore.")
                .setView(fields)
                .setNegativeButton("Cancel", null)
                .setPositiveButton(settingsOnly ? "Save" : "Save & connect", (dialog, which) -> {
                    String ssidText = ssid.getText().toString().trim();
                    String passwordText = password.getText().toString();
                    if (ssidText.isEmpty() || passwordText.isEmpty()) {
                        setStatus("SSID and password are required");
                        return;
                    }
                    try {
                        credentialStore.save(ssidText, passwordText);
                        targetView.setText(ssidText + " • automatic camera discovery");
                        if (!settingsOnly) ensurePermissionsAndConnect(new CredentialStore.Credentials(ssidText, passwordText));
                    } catch (Exception error) {
                        setStatus("Could not securely save Wi-Fi credentials: " + error.getMessage());
                    }
                })
                .show();
    }

    private void ensurePermissionsAndConnect(CredentialStore.Credentials credentials) {
        List<String> missing = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            addIfMissing(missing, Manifest.permission.BLUETOOTH_SCAN);
            addIfMissing(missing, Manifest.permission.BLUETOOTH_CONNECT);
        } else {
            addIfMissing(missing, Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            addIfMissing(missing, Manifest.permission.NEARBY_WIFI_DEVICES);
        }
        if (!missing.isEmpty()) {
            pendingCredentials = credentials;
            requestPermissions(missing.toArray(new String[0]), REQUEST_RADIO_PERMISSIONS);
        } else {
            startAutoConnect(credentials);
        }
    }

    private void addIfMissing(List<String> missing, String permission) {
        if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) missing.add(permission);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_RADIO_PERMISSIONS) return;
        for (int result : grantResults) {
            if (result != PackageManager.PERMISSION_GRANTED) {
                pendingCredentials = null;
                setStatus("Nearby-device permission is required for automatic BLE connection");
                return;
            }
        }
        CredentialStore.Credentials credentials = pendingCredentials;
        pendingCredentials = null;
        if (credentials != null) startAutoConnect(credentials);
    }

    private void startAutoConnect(CredentialStore.Credentials credentials) {
        if (!surfaceReady) {
            setStatus("Video surface is not ready yet");
            return;
        }
        Network wifi = findWifiNetwork();
        if (wifi == null) {
            setStatus("Connect the phone to “" + credentials.ssid + "” first");
            return;
        }
        connecting = true;
        connectButton.setText("CANCEL");
        targetView.setText(credentials.ssid + " • locating OA4 over BLE…");
        acquireWifiLock();
        BleStationConnector connector = new BleStationConnector(this, credentials.ssid, credentials.password, this::setStatusFromWorker);
        bleConnector = connector;

        new Thread(() -> {
            try {
                if (!connector.connectAndJoin(45)) throw new IOException(connector.getError());
                if (!connecting || bleConnector != connector) return;
                Thread.sleep(1_200);
                String cachedIp = getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_CAMERA_IP, "");
                DjiLanDiscovery.Result discovered = DjiLanDiscovery.discover(this, wifi, cachedIp, 5_000);
                if (!connecting || bleConnector != connector) {
                    discovered.close();
                    return;
                }
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(PREF_CAMERA_IP, discovered.cameraIp).apply();
                setTargetFromWorker(credentials.ssid + " • OA4 " + discovered.cameraIp);
                startVideoSession(discovered, wifi);
            } catch (Exception error) {
                failConnection("Automatic connection failed: " + error.getMessage());
            }
        }, "oa4-auto-connect").start();
    }

    private void startVideoSession(DjiLanDiscovery.Result discovered, Network wifi) throws IOException {
        boolean sessionOwnsDiscovery = false;
        try {
            decoder = new HevcDecoder(videoView.getHolder().getSurface(), this::setStatusFromWorker);
            depacketizer = new LiveViewDepacketizer(new LiveViewDepacketizer.Listener() {
            @Override public void onAccessUnit(byte[] accessUnit) {
                HevcDecoder activeDecoder = decoder;
                if (activeDecoder != null) activeDecoder.queue(accessUnit);
            }

            @Override public void onNeedsRandomAccess() {
                DjiUdpSession activeSession = session;
                if (activeSession != null) activeSession.requestIFrame();
                setStatusFromWorker("Packet loss — holding last frame, requesting clean IDR…");
            }

            @Override public void onStats(LiveViewDepacketizer.Stats stats) {
                runOnUiThread(() -> statsView.setText(String.format(
                        Locale.US, "%.1f fps  •  %,d AUs  •  %,d dropped  •  silent",
                        stats.fps, stats.emitted, stats.dropped)));
            }
            });

            DjiUdpSession candidate = new DjiUdpSession(discovered, wifi, new DjiUdpSession.Listener() {
                @Override public void onStatus(String status) { setStatusFromWorker(status); }
                @Override public void onVideoPacket(byte[] payload, int length) {
                    LiveViewDepacketizer active = depacketizer;
                    if (active != null) active.feed(payload, length);
                }
                @Override public void onError(String error) { setStatusFromWorker(error); }
            });
            session = candidate;
            sessionOwnsDiscovery = true;
            candidate.connectAndStart();
            runOnUiThread(() -> {
                connecting = false;
                connectButton.setText("DISCONNECT");
                setStatus("Live direct-Wi-Fi session active • BLE hold active");
            });
        } finally {
            if (!sessionOwnsDiscovery) discovered.close();
        }
    }

    private void failConnection(String message) {
        DjiUdpSession activeSession = session;
        session = null;
        if (activeSession != null) activeSession.close();
        BleStationConnector activeBle = bleConnector;
        bleConnector = null;
        if (activeBle != null) activeBle.close();
        depacketizer = null;
        runOnUiThread(() -> {
            stopDecoderAndResetUi();
            setStatus(message);
        });
    }

    private void disconnect() {
        connecting = false;
        DjiUdpSession activeSession = session;
        session = null;
        if (activeSession != null) new Thread(activeSession::close, "oa4-disconnect").start();
        BleStationConnector activeBle = bleConnector;
        bleConnector = null;
        if (activeBle != null) new Thread(activeBle::close, "oa4-ble-disconnect").start();
        depacketizer = null;
        stopDecoderAndResetUi();
        setStatus("Disconnected");
    }

    private void stopDecoderAndResetUi() {
        HevcDecoder activeDecoder = decoder;
        decoder = null;
        if (activeDecoder != null) activeDecoder.close();
        releaseWifiLock();
        connecting = false;
        connectButton.setText("AUTO CONNECT");
        statsView.setText("1280×720 • ~30 fps • silent");
    }

    private Network findWifiNetwork() {
        ConnectivityManager manager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        for (Network network : manager.getAllNetworks()) {
            NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
            if (capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return network;
        }
        return null;
    }

    private void acquireWifiLock() {
        WifiManager manager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        wifiLock = manager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "dji-viewer:oa4");
        wifiLock.acquire();
    }

    private void releaseWifiLock() {
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        wifiLock = null;
    }

    private void setStatusFromWorker(String status) { runOnUiThread(() -> setStatus(status)); }
    private void setTargetFromWorker(String target) { runOnUiThread(() -> targetView.setText(target)); }
    private void setStatus(String status) { statusView.setText(status); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    @Override public void surfaceCreated(SurfaceHolder holder) { surfaceReady = true; }
    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}
    @Override public void surfaceDestroyed(SurfaceHolder holder) {
        surfaceReady = false;
        disconnect();
    }

    @Override protected void onDestroy() {
        disconnect();
        super.onDestroy();
    }
}
