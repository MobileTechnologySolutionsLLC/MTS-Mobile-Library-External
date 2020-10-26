package com.mts.MTSExample;

// Copyright © 2020 Mobile Technology Solutions, Inc. All rights reserved.

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import com.karlotoy.perfectune.instance.PerfectTune;
import com.mts.mts.MTSBeacon;
import com.mts.mts.MTSService;

import java.text.SimpleDateFormat;
import java.util.Date;

import static android.text.TextUtils.isEmpty;
import static com.mts.mts.MTSService.BluetoothConnectionState.connected;
import static com.mts.mts.MTSService.BluetoothConnectionState.inactive;
import static com.mts.mts.MTSService.BluetoothConnectionState.notReady;
import static com.mts.mts.MTSService.BluetoothConnectionState.scanning;

public class ExampleActivity extends AppCompatActivity implements ActivityCompat.OnRequestPermissionsResultCallback {

    private static final String TAG = "ExampleActivity";
    private static double kAutoConnectThresholdToneFrequency = 1400.0;
    private static double kAutoDisconnectThresholdToneFrequency = 1100.0;
    private MTSService mtsService;
    private PerfectTune perfectTune = new PerfectTune();
    private Handler autoDisconnectHandler = new Handler();
    private MTSService.BluetoothConnectionState lastConnectionStatus = notReady;

    private TextView versionHeaderTextView;
    private Button connectionStateButton;
    private TextView connectionStatusTextView;
    private EditText autoConnectThresholdEditText;
    private EditText autoDisconnectThresholdEditText;
    private EditText scanDurationTimeoutEditText;
    private EditText cardDataEditText;
    private Button cardDataButton;
    private TextView lastWriteAtTextView;
    private TextView terminalKindTextView;
    private TextView stickyConnectionTextView;
    private TextView connectedRSSITextView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.example);
        Intent serviceIntent = new Intent(this, MTSService.class);
        serviceIntent.fillIn(getIntent(), 0);
        startService(serviceIntent);
        addListeners();
        updateInterface();
        activateDeviceScreenIfDebugging();
    }

    @Override
    public void onResume() {
        super.onResume();
        cardDataEditText.setText(lastCardData());
        Intent serviceIntent = new Intent(this, MTSService.class);
        bindService(serviceIntent , serviceConnection, BIND_AUTO_CREATE);
        registerReceiver(mtsServiceUpdateReceiver, mtsServiceUpdateIntentFilter());
        if (null != mtsService) {
            mtsService.detectedBeacons.clear();
            updateInterface();
        }
        requestPermissionsIfNeeded();
    }

    @Override
    public void onPause() {
        super.onPause();
        perfectTune.stopTune();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        unbindService(serviceConnection);
        Intent serviceIntent = new Intent(this, MTSService.class);
        stopService(serviceIntent);
        mtsService = null;
    }

    private void updateInterface() {
        if (null == mtsService) {
            Log.v(TAG, "null == MTSService in updateInterface()");
            return;
        }

        switch (mtsService.bluetoothConnectionState) {
            case notReady:
                connectionStatusTextView.setText("Not Ready");
                connectionStateButton.setText("Open Bluetooth Settings");
                break;
            case inactive:
                connectionStatusTextView.setText("Inactive");
                connectionStateButton.setText("Start Scanning");
                break;
            case scanning:
                connectionStatusTextView.setText("Scanning");
                connectionStateButton.setText("Stop Scanning");
                break;
            case connected:
                connectionStatusTextView.setText("Connected");
                connectionStateButton.setText("Disconnect");
                break;
            case attemptingToReconnect:
                connectionStatusTextView.setText("Attempting Reconnect");
                connectionStateButton.setText("Disconnect");
        }

        String autoConnectRSSIString = String.format("%d", mtsService.autoConnectRSSIThreshold());
        autoConnectThresholdEditText.setText(autoConnectRSSIString);

        String autoDisconnectRSSIString = String.format("%d", mtsService.autoDisconnectRSSIThreshold());
        autoDisconnectThresholdEditText.setText(autoDisconnectRSSIString);

        String scanDurationTimeoutString = String.format("%d", mtsService.scanTimeoutInterval());
        scanDurationTimeoutEditText.setText(scanDurationTimeoutString);

        String versionString = "MTS Example " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")";
        versionHeaderTextView.setText(versionString);
    }

    private void playConnectedSound() {
        perfectTune.setTuneFreq(kAutoConnectThresholdToneFrequency);
        MTSBeacon beacon = mtsService.selectedBeacon();
        if (null == beacon) {
            perfectTune.playTune();
        }
        autoDisconnectHandler.postDelayed( new Runnable() {
            @Override
            public void run() {
                perfectTune.stopTune();
            }
        }, 500);
    }

    private void playDisconnectSound() {
        perfectTune.setTuneFreq(kAutoDisconnectThresholdToneFrequency);
        perfectTune.playTune();
        autoDisconnectHandler.postDelayed( new Runnable() {
            @Override
            public void run() {
                perfectTune.stopTune();
            }
        }, 500);    }


    private void activateDeviceScreenIfDebugging() {
        if (BuildConfig.DEBUG) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    private void addListeners() {

        versionHeaderTextView = (TextView) findViewById(R.id.version_header);

        connectionStateButton = (Button) findViewById(R.id.connection_status_button);
        connectionStateButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                bluetoothConnectionAction();
            }
        });
        connectionStatusTextView = (TextView) findViewById(R.id.connection_status_text_view);

        autoConnectThresholdEditText = (EditText) findViewById(R.id.autoconnect_threshold_edit_text);
        autoConnectThresholdEditText.addTextChangedListener(autoConnectThresholdEditTextWatcher);

        autoDisconnectThresholdEditText = (EditText) findViewById(R.id.autodisconnect_threshold_edit_text);
        autoDisconnectThresholdEditText.addTextChangedListener(autoDisconnectThresholdEditTextWatcher);

        scanDurationTimeoutEditText = (EditText) findViewById(R.id.scan_duration_timeout_edit_text);
        scanDurationTimeoutEditText.addTextChangedListener(scanDurationTimeoutTextWatcher);

        cardDataEditText = (EditText) findViewById(R.id.player_id_edit_text);
        cardDataEditText.addTextChangedListener(cardDataEditTextWatcher);

        cardDataButton = (Button) findViewById(R.id.cardDataButton);
        cardDataButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                writeCardDataToBluetooth();
            }
        });
        lastWriteAtTextView = (TextView) findViewById(R.id.lastWriteAtTextView);
        terminalKindTextView = (TextView) findViewById(R.id.terminal_id_text_view);
        stickyConnectionTextView = (TextView) findViewById(R.id.sticky_connection_text_view);
        connectedRSSITextView = (TextView) findViewById(R.id.connected_rssi_text_view);
    }

    private TextWatcher autoConnectThresholdEditTextWatcher = new TextWatcher(){
        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {}

        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

        @Override
        public void afterTextChanged(Editable s) {
            int rssi = tryParsingAsInt(s.toString());
            mtsService.setAutoConnectRSSIThreshold(rssi);
        }
    };

    private TextWatcher autoDisconnectThresholdEditTextWatcher = new TextWatcher(){
        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {}

        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

        @Override
        public void afterTextChanged(Editable s) {
            int rssi = tryParsingAsInt(s.toString());
            mtsService.setAutoDisconnectRSSIThreshold(rssi);
        }
    };

    private TextWatcher scanDurationTimeoutTextWatcher = new TextWatcher(){
        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {}

        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

        @Override
        public void afterTextChanged(Editable s) {
            int interval = tryParsingAsInt(s.toString());
            mtsService.setScanTimeoutInterval(interval);
        }
    };

    private TextWatcher cardDataEditTextWatcher = new TextWatcher(){
        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {}

        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

        @Override
        public void afterTextChanged(Editable s) {
            setLastCardData(s.toString());
        }
    };

    public static Integer tryParsingAsInt(String text) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public void bluetoothConnectionAction() {

        if (null == mtsService) {
            Log.v(TAG, "mtsService was null on bluetoothConnectionAction request.");
            return;
        }

        switch (mtsService.bluetoothConnectionState) {
            case notReady:
                openBluetoothSettings();
                break;
            case inactive:
                mtsService.startScanning();
                break;
            case scanning:
                mtsService.stopScanning();
                break;
            case connected:
                mtsService.disconnect();
                break;
        }
    }

    public void openBluetoothSettings() {
        Intent intentOpenBluetoothSettings = new Intent();
        intentOpenBluetoothSettings.setAction(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS);
        startActivity(intentOpenBluetoothSettings);
    }

    private static final int kBluetoothPermissionsFlag = 0;
    private boolean hasPermissions() {
        int granted = PackageManager.PERMISSION_GRANTED;
        return (granted == ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) &&
                granted == ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        );
    }

    private void requestPermissionsIfNeeded() {

        if (hasPermissions()) {
            return;
        }

        String[] allPermissions = new String[]{
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
        };

        if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.ACCESS_COARSE_LOCATION)) {
            //Handle a prior user decline of permissions.
        }
        else {
            ActivityCompat.requestPermissions(this, allPermissions, kBluetoothPermissionsFlag);
        }
    }


    // Service Interaction

    private final ServiceConnection serviceConnection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName componentName, IBinder service) {
            mtsService = ((MTSService.LocalBinder) service).getService();
            if (!mtsService.initialize(getApplicationContext())) {
                Log.e(TAG, "Failed to initialize MTSService");
                finish();
            } else {
                Log.v("","initialized mtsService...");
            }

//            bluetoothEnabler();
            mtsService.setAutoDisconnectInterval(3);
            updateInterface();
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            mtsService = null;
        }
    };

    private void requestTerminalKind() {
        mtsService.requestTerminalKind();
    }

    private void requestStickyConnectState() {
        mtsService.requestStickyConnectState();
    }

    private void writeCardDataToBluetooth() {
        mtsService.writeCardDataToBluetooth(lastCardData());
    }

    private final BroadcastReceiver mtsServiceUpdateReceiver = new BroadcastReceiver()
    {
        @Override
        public void onReceive(Context context, Intent intent)
        {
            if (null == mtsService) {
                return;
            }

            final String action = intent.getAction();
            if (MTSService.BluetoothConnectionStateChanged.equals(action)){
                if (mtsService.bluetoothConnectionState == connected) {
                    playConnectedSound();
                    requestTerminalKind();
                    requestStickyConnectState();
                    writeCardDataToBluetooth();
                    lastWriteAtTextView.setText("Last write: none since connect.");
                }
                else if ( (mtsService.bluetoothConnectionState == scanning || mtsService.bluetoothConnectionState == inactive)
                        && lastConnectionStatus == connected) {
                    playDisconnectSound();
                }
                if (mtsService.bluetoothConnectionState == connected) {
                    lastWriteAtTextView.setText("Ready to write card data.");
                } else {
                    lastWriteAtTextView.setText("Connect BLE or MFi to write card data.");
                }
                lastConnectionStatus = mtsService.bluetoothConnectionState;
            }
            else if (MTSService.DidReceiveTerminalKind.equals(action)){
                String terminalKind = intent.getExtras().getString("terminalKind");
                terminalKindTextView.setText(terminalKind);
            }
            else if (MTSService.DidReceiveStickyConnectionState.equals(action)){
                boolean deviceWantsStickyConnection = intent.getExtras().getBoolean("stickyConnectionState");
                String boolString = deviceWantsStickyConnection ? "Yes" : "No";
                stickyConnectionTextView.setText(boolString);
            }
            else if (MTSService.UpdateOnConnectedRSSIReceipt.equals(action)){
                String connectedRSSIValue = intent.getExtras().getString("connectedRSSIValue");
                connectedRSSITextView.setText(connectedRSSIValue);
            }
            else if (MTSService.DidReceiveCardData.equals(action)){
                String cardData = intent.getExtras().getString("cardData");
                // Don't assign this here when the cardDataEditText is user-editable as the returned
                // value includes the start/end sentinels.
                // cardDataEditText.setText(cardData);
                Log.v(TAG, "DidReceiveCardData");
            }
            else if (MTSService.DidWriteCardDataToBluetooth.equals(action)){
                Boolean success = intent.getExtras().getBoolean("DidWriteCardDataToBluetooth");
                if (success) {
                    Log.v(TAG, "DidWriteCardDataToBluetooth");

                    Date now = new Date();
                    String formattedDate = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").format(now);
                    lastWriteAtTextView.setText("Last write at: " + formattedDate);
                    mtsService.requestCardData();
                } else {
                    Log.v(TAG, "Failed to write cardData to Bluetooth.");
                    lastWriteAtTextView.setText("Failed to write cardData via BLE.");
                }
            }
            updateInterface();
        }
    };

    private static IntentFilter mtsServiceUpdateIntentFilter() {
        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(MTSService.BluetoothConnectionStateChanged);
        intentFilter.addAction(MTSService.DidReceiveTerminalKind);
        intentFilter.addAction(MTSService.DidReceiveStickyConnectionState);
        intentFilter.addAction(MTSService.UpdateOnConnectedRSSIReceipt);
        intentFilter.addAction(MTSService.DidReceiveCardData);
        intentFilter.addAction(MTSService.DidWriteCardDataToBluetooth);
        return intentFilter;
    }

    // PlayerID Persistence
    private static String kSharedPreferenceKey = "com.mts.MTSExample";
    private static String kCardDataPreferenceKey = "kCardDataPreferenceKey";
    private static String kCardDataPreferenceKeyDefault = "TESTDATA";

    String lastCardData() {
        SharedPreferences sharedPreferences = this.getApplicationContext().getSharedPreferences(
                kSharedPreferenceKey, Context.MODE_PRIVATE);
        String value = sharedPreferences.getString(kCardDataPreferenceKey, kCardDataPreferenceKeyDefault);
        return value;
    }

    void setLastCardData(String cardDataString) {
        SharedPreferences sharedPreferences =
                this.getApplicationContext().getSharedPreferences(kSharedPreferenceKey, Context.MODE_PRIVATE);
        if (isEmpty(cardDataString)) {
            cardDataString = kCardDataPreferenceKeyDefault;
        }
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putString(kCardDataPreferenceKey, cardDataString);
        editor.commit();
        Log.v("","setLastPlayerID: " + lastCardData());
    }

}
