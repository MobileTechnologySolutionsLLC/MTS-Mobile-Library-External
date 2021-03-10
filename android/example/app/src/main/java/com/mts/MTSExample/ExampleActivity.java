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
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;

import com.karlotoy.perfectune.instance.PerfectTune;
import com.mts.mts.MTSBeacon;
import com.mts.mts.MTSBeaconEvent;
import com.mts.mts.MTSBluetoothConnectionEvent;
import com.mts.mts.MTSBluetoothDiscoveryStateEvent;
import com.mts.mts.MTSService;

import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.UUID;

import static android.text.TextUtils.isEmpty;
import static com.mts.mts.MTSService.BluetoothConnectionEvent.connect;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

public class ExampleActivity extends AppCompatActivity implements ActivityCompat.OnRequestPermissionsResultCallback {

    private static final String TAG = "ExampleActivity";
    private static double kAutoConnectThresholdToneFrequency = 1400.0;
    private static double kAutoDisconnectThresholdToneFrequency = 1100.0;
    private MTSService mtsService;
    private PerfectTune perfectTune = new PerfectTune();
    private Handler autoDisconnectHandler = new Handler();

    private TextView versionHeaderTextView;
    private Button connectionStateButton1;
    private TextView connectionStatusTextView1;
    private Button connectionStateButton2;
    private TextView connectionStatusTextView2;
    private Spinner txAttnSpinner;
    private EditText autoConnectThresholdEditText;
    private EditText autoDisconnectThresholdEditText;
    private EditText scanDurationTimeoutEditText;
    private EditText cardDataEditText;
    private Button cardDataButton;
    private TextView lastWriteAtTextView;
    private TextView terminalKindTextView;
    private TextView connectedRSSITextView1;
    private TextView connectedRSSITextView2;
    private TextView sasSerialNumberTextView;
    private TextView locationTextView;
    private TextView assetNumberTextView;
    private TextView denominationTextView;
    private TextView gmiLinkActiveTextView;
    private String cardDataString = "";

    private MTSBeacon mtsBeacon1;
    private MTSBeacon mtsBeacon2;

    // This serviceUUID is implementation-specific, i.e. MTS will provide it to you.
    UUID kMTSServiceUUID = UUID.fromString("6289B88C-E219-45AA-868E-92286187DEDF");

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
        System.out.println("ExampleActivity onResume");
        super.onResume();
        Intent serviceIntent = new Intent(this, MTSService.class);
        bindService(serviceIntent , serviceConnection, BIND_AUTO_CREATE);
        registerReceiver(mtsServiceUpdateReceiver, mtsServiceUpdateIntentFilter());
        if (null != mtsService) {
            mtsService.detectedBeacons.clear();
            updateInterface();
        }
        requestPermissionsIfNeeded();
        pruneAssignedBeaconsIfNeeded();
        updateInterface();
    }

    @Override
    public void onStart() {
        System.out.println("ExampleActivity onStart");
        super.onStart();
        EventBus.getDefault().register(this);
    }

    @Override
    public void onStop() {
        System.out.println("ExampleActivity onStop");
        EventBus.getDefault().unregister(this);
        super.onStop();
    }

    @Override
    public void onPause() {
        System.out.println("ExampleActivity onPause");
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

    // This example activity updates the interface based on mtsBeacon1/2 assignments.
    // If there are stale assignments on resume, clear them so the interface reflects this.
    private void pruneAssignedBeaconsIfNeeded() {
        if (null == mtsService) {
            System.out.println("pruneAssignedBeaconsIfNeeded called while null == mtsService");
            return;
        }
        if (0 == mtsService.connectedMTSBeacons.size()) {
            System.out.println("pruneAssignedBeaconsIfNeeded 0 == mtsService.connectedMTSBeacons.size(), clearing beacons...");
            mtsBeacon1 = null;
            mtsBeacon2 = null;
        } else {
            System.out.println("pruneAssignedBeaconsIfNeeded connectedMTSBeacons still has some members, not clearing.");
        }
    }

    private void updateInterface() {
        if (null == mtsService) {
            Log.v(TAG, "null == MTSService in updateInterface()");
            return;
        }

        if (null == mtsBeacon1) {
            updateStatusTextViewForState(connectionStatusTextView1, connectionStateButton1);
        } else {
            connectionStatusTextView1.setText("Connected");
            connectionStateButton1.setText("Disconnect");
        }

        if (null == mtsBeacon2) {
            updateStatusTextViewForState(connectionStatusTextView2, connectionStateButton2);
        } else {
            connectionStatusTextView2.setText("Connected");
            connectionStateButton2.setText("Disconnect");
        }

        if (null == mtsBeacon1 && null == mtsBeacon2) {
            connectionStatusTextView2.setVisibility(View.GONE);
            connectionStateButton2.setVisibility(View.GONE);
        } else {
            connectionStatusTextView2.setVisibility(View.VISIBLE);
            connectionStateButton2.setVisibility(View.VISIBLE);
        }

    }

    private void populateFormValues() {
        if (null == mtsService) {
            Log.v(TAG, "null == MTSService in updateInterface()");
            return;
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

    private void updateStatusTextViewForState(TextView textView, Button button) {
        switch (mtsService.bluetoothDiscoveryState) {
            case notReady:
                textView.setText("Not Ready");
                button.setText("Open Bluetooth Settings");
                break;
            case inactive:
                textView.setText("Inactive");
                button.setText("Start Scanning");
                break;
            case scanning:
                textView.setText("Scanning");
                button.setText("Stop Scanning");
                break;
        }
        autoDisconnectHandler.postDelayed( new Runnable() {
            @Override
            public void run() {
                perfectTune.stopTune();
            }
        }, 500);
    }


    // MARK: Connect/Disconnect Sounds

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

        connectionStateButton1 = (Button) findViewById(R.id.connection_status_button1);
        connectionStateButton1.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                connectionStatusCellTap(mtsBeacon1);
            }
        });
        connectionStatusTextView1 = (TextView) findViewById(R.id.connection_status_text_view1);

        connectionStateButton2 = (Button) findViewById(R.id.connection_status_button2);
        connectionStateButton2.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                connectionStatusCellTap(mtsBeacon2);
            }
        });
        connectionStatusTextView2 = (TextView) findViewById(R.id.connection_status_text_view2);

        txAttnSpinner = (Spinner) findViewById(R.id.tx_attn_spinner);
        String[] txAttenLevelOptions = getNames(MTSService.TxAttenuationLevel.class);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, txAttenLevelOptions);
        txAttnSpinner.setAdapter(adapter);
        txAttnSpinner.setSelection(lastTxAttenuationLevel());
        txAttnSpinner.setOnItemSelectedListener(txAttnSpinnerOnItemSelectedListener);

        autoConnectThresholdEditText = (EditText) findViewById(R.id.autoconnect_threshold_edit_text);
        autoConnectThresholdEditText.addTextChangedListener(autoConnectThresholdEditTextWatcher);

        autoDisconnectThresholdEditText = (EditText) findViewById(R.id.autodisconnect_threshold_edit_text);
        autoDisconnectThresholdEditText.addTextChangedListener(autoDisconnectThresholdEditTextWatcher);

        scanDurationTimeoutEditText = (EditText) findViewById(R.id.scan_duration_timeout_edit_text);
        scanDurationTimeoutEditText.addTextChangedListener(scanDurationTimeoutTextWatcher);

        cardDataEditText = (EditText) findViewById(R.id.player_id_edit_text);
        cardDataEditText.addTextChangedListener(cardDataEditTextWatcher);
        cardDataEditText.setOnFocusChangeListener(new View.OnFocusChangeListener()
        {
            @Override
            public void onFocusChange(View v, boolean hasFocus)
            {
                if (hasFocus==true)
                {
                    cardDataEditText.setText("");
                }
            }
        });

        cardDataButton = (Button) findViewById(R.id.cardDataButton);
        cardDataButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                System.out.println("cardDataButton.setOnClickListener");
                writeCardDataToBluetooth();
            }
        });
        lastWriteAtTextView = (TextView) findViewById(R.id.lastWriteAtTextView);
        terminalKindTextView = (TextView) findViewById(R.id.terminal_id_text_view);
        connectedRSSITextView1 = (TextView) findViewById(R.id.connected_rssi_text_view1);
        connectedRSSITextView2 = (TextView) findViewById(R.id.connected_rssi_text_view2);

        sasSerialNumberTextView = (TextView) findViewById(R.id.sas_serial_number_text_view);
        locationTextView = (TextView) findViewById(R.id.location_text_view);
        assetNumberTextView = (TextView) findViewById(R.id.asset_number_text_view);
        denominationTextView = (TextView) findViewById(R.id.denomination_text_view);
        gmiLinkActiveTextView = (TextView) findViewById(R.id.gmi_link_active_text_view);

        populateFormValues();
    }

    AdapterView.OnItemSelectedListener txAttnSpinnerOnItemSelectedListener = new AdapterView.OnItemSelectedListener() {
        @Override
        public void onItemSelected(AdapterView<?> parentView, View selectedItemView, int position, long id) {
            MTSService.TxAttenuationLevel level = MTSService.TxAttenuationLevel.values()[position];
            setLastTxAttenuationLevel(position);
        }

        @Override
        public void onNothingSelected(AdapterView<?> parentView) {}
    };

    public static String[] getNames(Class<? extends Enum<?>> e) {
        return Arrays.toString(e.getEnumConstants()).replaceAll("^.|.$", "").split(", ");
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
            System.out.println("afterTextChanged s.toString(): " + s.toString());
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
            System.out.println("afterTextChanged cardDataString: " + lastCardData());
        }
    };

    public static Integer tryParsingAsInt(String text) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public void connectionStatusCellTap(MTSBeacon mtsBeacon) {

        if (null == mtsService) {
            Log.v(TAG, "connectionStatusCellTap: mtsService was null on connectionStatusCell1Tap.");
            return;
        }
        else if (null != mtsBeacon) {
            Log.v(TAG, "connectionStatusCellTap: null != mtsBeacon, requesting disconnect...");
            mtsService.disconnect(mtsBeacon);
        }
        else {
            switch (mtsService.bluetoothDiscoveryState) {
                case notReady:
                    Log.v(TAG, "connectionStatusCellTap: notReady");
                    openBluetoothSettings();
                    break;
                case inactive:
                    Log.v(TAG, "connectionStatusCellTap: inactive");
                    mtsService.startScanning();
                    break;
                case scanning:
                    Log.v(TAG, "connectionStatusCellTap: scanning");
                    mtsService.stopScanning();
                    break;
            }
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

    private final BroadcastReceiver mtsServiceUpdateReceiver = new BroadcastReceiver()
    {
        @Override
        public void onReceive(Context context, Intent intent) {}
    };

    private static IntentFilter mtsServiceUpdateIntentFilter() {
        final IntentFilter intentFilter = new IntentFilter();
        return intentFilter;
    }

    private final ServiceConnection serviceConnection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName componentName, IBinder service) {
            mtsService = ((MTSService.LocalBinder) service).getService();
            if (!mtsService.initialize(getApplicationContext(), kMTSServiceUUID)) {
                Log.e(TAG, "Failed to initialize MTSService");
                finish();
            } else {
                Log.v("","initialized mtsService...");
            }

            mtsService.setAutoDisconnectInterval(3);
            updateInterface();
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            mtsService = null;
        }
    };

    private void requestTerminalKind() {
        if (null != mtsBeacon1) {
            mtsService.requestTerminalKind(mtsBeacon1);
        }
    }

    private void writeCardDataToBluetooth() {
        if (null != mtsBeacon1) {
            System.out.println("Example writeCardDataToBluetooth cardDataString: " + lastCardData());
            mtsService.writeCardDataToBluetooth(lastCardData(), mtsBeacon1);
        } else {
            System.out.println("Example writeCardDataToBluetooth called while null == mtsBeacon1.");
        }
    }


    // EventBus call from MTSService

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onMessage(MTSBluetoothDiscoveryStateEvent event) {
        Log.v("","changeBluetoothDiscoveryState event received in Example.");
        updateInterface();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onMessage(MTSBluetoothConnectionEvent event) {

        Log.v("","bluetoothConnectionEvent received in Example " + event.connectionEvent);

        updateDisplayBeacons(event);

        if (event.connectionEvent.equals(connect)) {
            playConnectedSound();
            requestTerminalKind();
            mtsService.writeCardDataToBluetooth(lastCardData(), event.mtsBeacon);
            lastWriteAtTextView.setText("Last write: none since connect.");
            int position = lastTxAttenuationLevel();
            MTSService.TxAttenuationLevel level = MTSService.TxAttenuationLevel.values()[position];
            System.out.println("post-connect write level: " + level);
            mtsService.writeTxAttenuationLevel(level, event.mtsBeacon);
        }
        else if (event.connectionEvent.equals(MTSService.BluetoothConnectionEvent.disconnect)) {
            playDisconnectSound();
            lastWriteAtTextView.setText("Connect BLE to write card data.");
        }
        updateInterface();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onMessage(MTSBeaconEvent event) {

        if (event.eventType.equals(MTSService.MTSEventType.didReceiveTerminalKind)) {
            String terminalKind = (String) event.value;
            terminalKindTextView.setText(terminalKind);
        }
        else if (event.eventType.equals(MTSService.MTSEventType.updateOnConnectedRSSIReceipt)) {
            String connectedRSSIValue = (String) event.value;
            if (event.mtsBeacon.equals(mtsBeacon1)) {
                connectedRSSITextView1.setText(connectedRSSIValue);
            }
            else if (event.mtsBeacon.equals(mtsBeacon2)) {
                connectedRSSITextView2.setText(connectedRSSIValue);
            } else {
                Log.v(TAG, "onMessage RSSI event arrived for an unassigned mtsBeacon.");
            }
        }
        else if (event.eventType.equals(MTSService.MTSEventType.didReceiveCardData)) {
            if (event.mtsBeacon == mtsBeacon1) {
                // Don't assign this here when the cardDataEditText is user-editable as the returned
                // value includes the start/end sentinels.
                // cardDataEditText.setText(cardData);
                Log.v(TAG, "DidReceiveCardData");
            }
        }
        else if (event.eventType.equals(MTSService.MTSEventType.didWriteCardDataToBluetooth)) {
            Boolean success = (Boolean) event.value;
            if (success) {
                Log.v(TAG, "DidWriteCardDataToBluetooth");
                Date now = new Date();
                String formattedDate = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").format(now);
                lastWriteAtTextView.setText("Last write at: " + formattedDate);
                mtsService.requestCardData(event.mtsBeacon);
            } else {
                Log.v(TAG, "Failed to write cardData to Bluetooth.");
                lastWriteAtTextView.setText("Failed to write cardData via BLE.");
            }
        }
        else if (event.eventType.equals(MTSService.MTSEventType.didReceiveSasSerialNumber)) {
            if (null != mtsBeacon1) {
                sasSerialNumberTextView.setText(event.value.toString());
            }
        }
        else if (event.eventType.equals(MTSService.MTSEventType.didReceiveLocation)) {
            if (null != mtsBeacon1) {
                locationTextView.setText(event.value.toString());
            }
        }
        else if (event.eventType.equals(MTSService.MTSEventType.didReceiveAssetNumber)) {
            if (null != mtsBeacon1) {
                assetNumberTextView.setText(event.value.toString());
            }
        }
        else if (event.eventType.equals(MTSService.MTSEventType.didReceiveDenomination)) {
            if (null != mtsBeacon1) {
                denominationTextView.setText(event.value.toString());
            }
        }
        else if (event.eventType.equals(MTSService.MTSEventType.didReceiveGmiLinkActive)) {
            if (null != mtsBeacon1) {
                gmiLinkActiveTextView.setText(event.value.toString());
            }
        }
        else if (event.eventType.equals(MTSService.MTSEventType.didReceiveTxAttenuationLevel)) {
            if (null != mtsBeacon1) {
                byte b = (byte) event.value;
                int position = b &255;
                System.out.println("Example didReceiveTxAttenuationLevel position: " + position);
            }
        }
    }

    // Likely not relevant to customer implementation, but what is happening here:
    // Demo support is requested for 0-2 two beacon connections, so find conditional rather than array handling in this example.
    // The first beacon to connect is assigned mtsBeacon1.
    // Only if a new beacon is connected while mtsBeacon1 is already assigned do we assign the new beacon to mtsBeacon2.
    private void updateDisplayBeacons(MTSBluetoothConnectionEvent event) {

        switch (event.connectionEvent) {
            case connect:
            if (null == mtsBeacon1) {
                Log.v("","updateDisplayBeacons connect null == mtsBeacon1;");
                mtsBeacon1 = event.mtsBeacon;
            } else if (null == mtsBeacon2) {
                Log.v("","updateDisplayBeacons connect null == mtsBeacon2;");
                mtsBeacon2 = event.mtsBeacon;
            } else {
                Log.v("TAG", "unexpected assignment of more than two connected beacons.");
            }
            break;
            case disconnect:
            if (event.mtsBeacon == mtsBeacon1) {
                Log.v("","updateDisplayBeacons disconnect mtsBeacon == mtsBeacon1;");
                mtsBeacon1 = null;
            } else if (event.mtsBeacon == mtsBeacon2) {
                Log.v("","updateDisplayBeacons disconnect mtsBeacon == mtsBeacon2;");
                mtsBeacon2 = null;
            } else {
                Log.v("TAG", "unexpected disconnect of an untracked beacon.");
            }
            break;
        }
        updateInterface();
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

    // Tx Attenuation Level Persistence
    private static String kTxAttenLevelPreferenceKey = "kTxAttenLevelPreferenceKey";
    private static int kTxAttenLevelPreferenceKeyDefault = 2;

    int lastTxAttenuationLevel() {
        SharedPreferences sharedPreferences = this.getApplicationContext().getSharedPreferences(
                kSharedPreferenceKey, Context.MODE_PRIVATE);
        int value = sharedPreferences.getInt(kTxAttenLevelPreferenceKey, kTxAttenLevelPreferenceKeyDefault);
        return value;
    }

    void setLastTxAttenuationLevel(int enumPosition) {
        SharedPreferences sharedPreferences =
                this.getApplicationContext().getSharedPreferences(kSharedPreferenceKey, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putInt(kTxAttenLevelPreferenceKey, enumPosition);
        editor.commit();
        Log.v("","setLastTxAttenuationLevel: " + enumPosition);
    }

}
