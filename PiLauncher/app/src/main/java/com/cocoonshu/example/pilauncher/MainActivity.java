package com.cocoonshu.example.pilauncher;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.NetworkUtils;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.widget.TextView;

import com.google.android.things.pio.PeripheralManagerService;
import com.google.android.things.contrib.driver.button.Button;
import com.google.android.things.contrib.driver.button.ButtonInputDriver;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import static android.view.KeyEvent.KEYCODE_0;

public class MainActivity extends Activity {
    private static final String TAG                              = "A2DPSinkActivity";
    private static final String ADAPTER_FRIENDLY_NAME            = "Cocoonshu Bluetooth device";
    private static final int    DISCOVERABLE_TIMEOUT_MS          = 30 * 1000;
    private static final int    REQUEST_CODE_ENABLE_DISCOVERABLE = 100;
    private static final String UTTERANCE_ID                     = "com.example.androidthings.bluetooth.audio.UTTERANCE_ID";

    private PeripheralManagerService mGPIOService         = new PeripheralManagerService();
    private ButtonInputDriver        mBtnDriver           = null;
    private Nokia5110                mNokia5110           = null;

    private BluetoothAdapter         mBluetoothAdapter    = null;
    private BluetoothProfile         mA2DPSinkProxy       = null;
    private android.widget.Button    mPairingButton       = null;
    private android.widget.Button    mDisconnectAllButton = null;
    private TextToSpeech             mTtsEngine           = null;
    private Handler                  mHandler             = new Handler();
    private Runnable                 mIPUpdateRunnable    = new Runnable() {
        @Override
        public void run() {
            updateIpAddress();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mPairingButton = (android.widget.Button) findViewById(R.id.Button_Pairing);
        mDisconnectAllButton = (android.widget.Button) findViewById(R.id.Button_DisconnectAll);
        mPairingButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                enableDiscoverable();
            }
        });
        mDisconnectAllButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                disconnectConnectedDevices();
            }
        });

        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (mBluetoothAdapter == null) {
            Log.w(TAG, "No default Bluetooth adapter. Device likely does not support bluetooth.");
            return;
        }

        initTts();
        registerReceiver(mAdapterStateChangeReceiver, new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED));
        registerReceiver(mSinkProfileStateChangeReceiver, new IntentFilter(A2dpSinkHelper.ACTION_CONNECTION_STATE_CHANGED));
        registerReceiver(mSinkProfilePlaybackChangeReceiver, new IntentFilter(A2dpSinkHelper.ACTION_PLAYING_STATE_CHANGED));

        if (mBluetoothAdapter.isEnabled()) {
            Log.d(TAG, "Bluetooth Adapter is already enabled.");
            initA2DPSink();
        } else {
            Log.d(TAG, "Bluetooth adapter not enabled. Enabling.");
            mBluetoothAdapter.enable();
        }

        configureButton();
        configureNokia5110();
    }

    @Override
    protected void onStart() {
        super.onStart();
        mHandler.postDelayed(mIPUpdateRunnable, 5000);
    }

    @Override
    protected void onStop() {
        super.onStop();
        mHandler.removeCallbacks(mIPUpdateRunnable);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(mAdapterStateChangeReceiver);
        unregisterReceiver(mSinkProfileStateChangeReceiver);
        unregisterReceiver(mSinkProfilePlaybackChangeReceiver);

        if (mA2DPSinkProxy != null) {
            mBluetoothAdapter.closeProfileProxy(A2dpSinkHelper.A2DP_SINK_PROFILE, mA2DPSinkProxy);
        }

        if (mTtsEngine != null) {
            mTtsEngine.stop();
            mTtsEngine.shutdown();
        }

        deconfigureButton();
        deconfigureNokia5110();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_ENABLE_DISCOVERABLE) {
            Log.d(TAG, "Enable discoverable returned with result " + resultCode);

            if (resultCode == RESULT_CANCELED) {
                Log.e(TAG, "Enable discoverable has been cancelled by the user. " +
                        "This should never happen in an Android Things device.");
                return;
            }
            Log.i(TAG, "Bluetooth adapter successfully set to discoverable mode. " +
                    "Any A2DP source can find it with the name " + ADAPTER_FRIENDLY_NAME +
                    " and pair for the next " + DISCOVERABLE_TIMEOUT_MS + " ms. " +
                    "Try looking for it on your phone, for example.");

            speak("Bluetooth audio sink is discoverable for " + DISCOVERABLE_TIMEOUT_MS +
                    " milliseconds. Look for a device named " + ADAPTER_FRIENDLY_NAME);

        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KEYCODE_0:{
                Log.i(TAG, "[onKeyDown] KEYCODE_0");
                event.startTracking();
            } return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KEYCODE_0:{
                Log.i(TAG, "[onKeyUp] KEYCODE_0");
                if (mNokia5110 != null) {
                    mNokia5110.enableLighting(!mNokia5110.isLightingEnabled());
                }
            } return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    @Override
    public boolean onKeyLongPress(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KEYCODE_0:{
                Log.i(TAG, "[onKeyLongPress] wifi.reconnect()");
                WifiManager wifi = (WifiManager) getSystemService(WIFI_SERVICE);
                wifi.reconnect();
            } break;
        }
        return super.onKeyLongPress(keyCode, event);
    }

    private void initA2DPSink() {
        if (mBluetoothAdapter == null || !mBluetoothAdapter.isEnabled()) {
            Log.e(TAG, "Bluetooth adapter not available or not enabled.");
            return;
        }
        Log.d(TAG, "Set up Bluetooth Adapter name and profile");
        mBluetoothAdapter.setName(ADAPTER_FRIENDLY_NAME);
        mBluetoothAdapter.getProfileProxy(this, new BluetoothProfile.ServiceListener() {
            @Override
            public void onServiceConnected(int profile, BluetoothProfile proxy) {
                mA2DPSinkProxy = proxy;
                enableDiscoverable();
            }
            @Override
            public void onServiceDisconnected(int profile) {
            }
        }, A2dpSinkHelper.A2DP_SINK_PROFILE);
    }

    private void enableDiscoverable() {
        Log.d(TAG, "Registering for discovery.");
        Intent discoverableIntent =
                new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
        discoverableIntent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION,
                DISCOVERABLE_TIMEOUT_MS);
        startActivityForResult(discoverableIntent, REQUEST_CODE_ENABLE_DISCOVERABLE);
    }

    private void disconnectConnectedDevices() {
        if (mA2DPSinkProxy == null || mBluetoothAdapter == null || !mBluetoothAdapter.isEnabled()) {
            return;
        }
        speak("Disconnecting devices");
        for (BluetoothDevice device: mA2DPSinkProxy.getConnectedDevices()) {
            Log.i(TAG, "Disconnecting device " + device);
            A2dpSinkHelper.disconnect(mA2DPSinkProxy, device);
        }
    }

    private void initTts() {
        mTtsEngine = new TextToSpeech(MainActivity.this,
                new TextToSpeech.OnInitListener() {
                    @Override
                    public void onInit(int status) {
                        if (status == TextToSpeech.SUCCESS) {
                            mTtsEngine.setLanguage(Locale.US);
                        } else {
                            Log.w(TAG, "Could not open TTS Engine (onInit status=" + status
                                    + "). Ignoring text to speech");
                            mTtsEngine = null;
                        }
                    }
                });
    }

    private void speak(String utterance) {
        Log.i(TAG, utterance);
        if (mTtsEngine != null) {
            mTtsEngine.speak(utterance, TextToSpeech.QUEUE_ADD, null, UTTERANCE_ID);
        }
    }

    private void configureButton() {
        try {
            mBtnDriver = new ButtonInputDriver("BCM18", Button.LogicState.PRESSED_WHEN_HIGH, KEYCODE_0);
            mBtnDriver.register();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void configureNokia5110() {
        final String BUS = "SPI0.0";
        final String DC  = "BCM22";
        final String CS  = "BCM27";
        final String RST = "BCM17";
        final String BL  = "BCM25";
        mNokia5110 = new Nokia5110(BUS, DC, CS, RST, BL);
        mNokia5110.open();
    }

    private void deconfigureButton() {
        if (mBtnDriver != null) {
            mBtnDriver.unregister();
        }
    }

    private void deconfigureNokia5110() {
        mNokia5110.close();
        mNokia5110 = null;
    }

    private void updateIpAddress() {
        WifiManager wifi = (WifiManager) getSystemService(WIFI_SERVICE);
        int ip = wifi.getConnectionInfo().getIpAddress();
        TextView txvNetwork = (TextView) findViewById(R.id.TextView_Network);
        String ipAddress = NetworkUtils.intToInetAddress(ip).getHostAddress();
        txvNetwork.setText(ipAddress);
        if (mNokia5110 != null) {
            mNokia5110.drawText(0, 0, ipAddress);
            mNokia5110.testLCD();
        }
    }

    private final BroadcastReceiver mAdapterStateChangeReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            int oldState = A2dpSinkHelper.getPreviousAdapterState(intent);
            int newState = A2dpSinkHelper.getCurrentAdapterState(intent);
            Log.d(TAG, "Bluetooth Adapter changing state from " + oldState + " to " + newState);
            if (newState == BluetoothAdapter.STATE_ON) {
                Log.i(TAG, "Bluetooth Adapter is ready");
                initA2DPSink();
            }
        }
    };

    private final BroadcastReceiver mSinkProfileStateChangeReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(A2dpSinkHelper.ACTION_CONNECTION_STATE_CHANGED)) {
                int oldState = A2dpSinkHelper.getPreviousProfileState(intent);
                int newState = A2dpSinkHelper.getCurrentProfileState(intent);
                BluetoothDevice device = A2dpSinkHelper.getDevice(intent);
                Log.d(TAG, "Bluetooth A2DP sink changing connection state from " + oldState +
                        " to " + newState + " device " + device);
                if (device != null) {
                    String deviceName = Objects.toString(device.getName(), "a device");
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        speak("Connected to " + deviceName);
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        speak("Disconnected from " + deviceName);
                    }
                }
            }
        }
    };

    private final BroadcastReceiver mSinkProfilePlaybackChangeReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(A2dpSinkHelper.ACTION_PLAYING_STATE_CHANGED)) {
                int oldState = A2dpSinkHelper.getPreviousProfileState(intent);
                int newState = A2dpSinkHelper.getCurrentProfileState(intent);
                BluetoothDevice device = A2dpSinkHelper.getDevice(intent);
                Log.d(TAG, "Bluetooth A2DP sink changing playback state from " + oldState +
                        " to " + newState + " device " + device);
                if (device != null) {
                    if (newState == A2dpSinkHelper.STATE_PLAYING) {
                        Log.i(TAG, "Playing audio from device " + device.getAddress());
                    } else if (newState == A2dpSinkHelper.STATE_NOT_PLAYING) {
                        Log.i(TAG, "Stopped playing audio from " + device.getAddress());
                    }
                }
            }
        }
    };

    public native String stringFromJNI();

    static {
        System.loadLibrary("native-lib");
    }
}
