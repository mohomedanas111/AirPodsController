package com.airpods.controller;

import android.Manifest;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.*;
import android.view.View;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import java.io.*;
import java.lang.reflect.Method;
import java.util.*;

public class MainActivity extends AppCompatActivity {

    // UI
    private TextView tvDeviceName, tvStatus, tvLog, tvBattLeft, tvBattRight, tvBattCase;
    private Button btnConnect, btnRetry;
    private Button btnOff, btnTransparency, btnAdaptive, btnANC;
    private SeekBar seekVolume;
    private TextView tvVolume;

    // Bluetooth
    private BluetoothAdapter btAdapter;
    private BluetoothDevice targetDevice;
    private BluetoothSocket btSocket;
    private BluetoothGatt btGatt;
    private OutputStream outputStream;
    private boolean connected = false;
    private Handler handler = new Handler(Looper.getMainLooper());
    private List<String> logLines = new ArrayList<>();

    // Known ANC command sets to try
    private static final byte[][] ANC_OFF = {
        {0x04, 0x00, 0x00, 0x09, 0x00},
        {0x00, 0x00, 0x00, 0x00, 0x00},
        {(byte)0xAA, 0x00, 0x00, 0x00},
    };
    private static final byte[][] ANC_ON = {
        {0x04, 0x00, 0x00, 0x09, 0x01},
        {0x01, 0x00, 0x00, 0x00, 0x01},
        {(byte)0xAA, 0x00, 0x00, 0x01},
    };
    private static final byte[][] TRANSPARENCY = {
        {0x04, 0x00, 0x00, 0x09, 0x03},
        {0x03, 0x00, 0x00, 0x00, 0x03},
        {(byte)0xAA, 0x00, 0x00, 0x03},
    };
    private static final byte[][] ADAPTIVE = {
        {0x04, 0x00, 0x00, 0x09, 0x04},
        {0x04, 0x00, 0x00, 0x00, 0x04},
        {(byte)0xAA, 0x00, 0x00, 0x04},
    };

    // SPP UUID (Classic Bluetooth serial)
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    // Apple-specific UUIDs
    private static final UUID APPLE_UUID = UUID.fromString("74ec2172-0bad-4d01-8d77-a68301a2d46d");

    private static final int REQUEST_PERMISSIONS = 1001;
    private static final int REQUEST_ENABLE_BT = 1002;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        initViews();
        checkPermissionsAndInit();
    }

    private void initViews() {
        tvDeviceName = findViewById(R.id.tvDeviceName);
        tvStatus = findViewById(R.id.tvStatus);
        tvLog = findViewById(R.id.tvLog);
        tvBattLeft = findViewById(R.id.tvBattLeft);
        tvBattRight = findViewById(R.id.tvBattRight);
        tvBattCase = findViewById(R.id.tvBattCase);
        btnConnect = findViewById(R.id.btnConnect);
        btnRetry = findViewById(R.id.btnRetry);
        btnOff = findViewById(R.id.btnOff);
        btnTransparency = findViewById(R.id.btnTransparency);
        btnAdaptive = findViewById(R.id.btnAdaptive);
        btnANC = findViewById(R.id.btnANC);
        seekVolume = findViewById(R.id.seekVolume);
        tvVolume = findViewById(R.id.tvVolume);

        btnConnect.setOnClickListener(v -> { if(connected) disconnect(); else startScan(); });
        btnRetry.setOnClickListener(v -> { btnRetry.setVisibility(View.GONE); startScan(); });
        btnOff.setOnClickListener(v -> sendNoise(ANC_OFF, "Off"));
        btnTransparency.setOnClickListener(v -> sendNoise(TRANSPARENCY, "Transparency"));
        btnAdaptive.setOnClickListener(v -> sendNoise(ADAPTIVE, "Adaptive"));
        btnANC.setOnClickListener(v -> sendNoise(ANC_ON, "Noise Cancel"));

        seekVolume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean u) { tvVolume.setText(p+"%"); addLog("Volume: "+p+"%"); }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) {}
        });
        seekVolume.setProgress(70);
    }

    private void checkPermissionsAndInit() {
        List<String> perms = new ArrayList<>();
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if(ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                perms.add(Manifest.permission.BLUETOOTH_CONNECT);
            if(ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
                perms.add(Manifest.permission.BLUETOOTH_SCAN);
        } else {
            if(ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH) != PackageManager.PERMISSION_GRANTED)
                perms.add(Manifest.permission.BLUETOOTH);
            if(ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
                perms.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if(!perms.isEmpty()) {
            ActivityCompat.requestPermissions(this, perms.toArray(new String[0]), REQUEST_PERMISSIONS);
        } else {
            initBluetooth();
        }
    }

    private void initBluetooth() {
        BluetoothManager bm = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        btAdapter = bm.getAdapter();
        if(btAdapter == null) { setStatus("No Bluetooth on this device"); return; }
        if(!btAdapter.isEnabled()) {
            Intent intent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(intent, REQUEST_ENABLE_BT);
        } else {
            setStatus("Ready — tap Connect");
            addLog("Bluetooth ready");
        }
    }

    // ── Scan paired devices first, then discover ──────────────
    private void startScan() {
        btnConnect.setText("Scanning...");
        btnConnect.setEnabled(false);
        setStatus("Scanning for earbuds...");
        addLog("Starting scan...");

        // First check already-paired devices
        Set<BluetoothDevice> paired = btAdapter.getBondedDevices();
        BluetoothDevice found = null;
        for(BluetoothDevice d : paired) {
            String name = d.getName() != null ? d.getName() : "";
            addLog("Paired: "+name+" ["+d.getAddress()+"]");
            if(name.toLowerCase().contains("airpod") || name.toLowerCase().contains("air pod")
               || name.toLowerCase().contains("airoha") || name.toLowerCase().contains("tws")
               || name.toLowerCase().contains("earbud") || name.toLowerCase().contains("headphone")) {
                found = d;
                addLog("★ Found likely earbud: "+name);
            }
        }

        if(found != null) {
            targetDevice = found;
            connectDevice(found);
        } else if(!paired.isEmpty()) {
            // Show picker
            showDevicePicker(new ArrayList<>(paired));
        } else {
            addLog("No paired devices. Please pair in Android Settings first.");
            setStatus("No paired devices found. Pair in Bluetooth Settings first.");
            resetBtn();
        }
    }

    private void showDevicePicker(List<BluetoothDevice> devices) {
        String[] names = new String[devices.size()];
        for(int i=0;i<devices.size();i++) {
            names[i] = (devices.get(i).getName()!=null?devices.get(i).getName():"Unknown")
                      +" ["+devices.get(i).getAddress()+"]";
        }
        new android.app.AlertDialog.Builder(this)
            .setTitle("Select your earbuds")
            .setItems(names, (d, which) -> {
                targetDevice = devices.get(which);
                connectDevice(targetDevice);
            })
            .setNegativeButton("Cancel", (d,w) -> resetBtn())
            .show();
    }

    // ── Connect using BOTH methods simultaneously ─────────────
    private void connectDevice(BluetoothDevice device) {
        setStatus("Connecting to "+(device.getName()!=null?device.getName():"device")+"...");
        addLog("Trying SPP (Classic BT) + BLE GATT simultaneously...");

        // Method 1: Classic Bluetooth SPP (most clones support this)
        new Thread(() -> connectSPP(device)).start();

        // Method 2: BLE GATT (fallback)
        handler.postDelayed(() -> {
            if(!connected) {
                addLog("Also trying BLE GATT...");
                connectGATT(device);
            }
        }, 2000);

        // Timeout
        handler.postDelayed(() -> {
            if(!connected) {
                addLog("Connection timeout after 15s");
                setStatus("Failed to connect. Try Retry.");
                handler.post(() -> { btnRetry.setVisibility(View.VISIBLE); resetBtn(); });
            }
        }, 15000);
    }

    // ── Method 1: Classic Bluetooth SPP ──────────────────────
    private void connectSPP(BluetoothDevice device) {
        try {
            addLog("SPP: Creating socket...");
            BluetoothSocket socket = null;

            // Try normal SPP first
            try {
                socket = device.createRfcommSocketToServiceRecord(SPP_UUID);
            } catch(Exception e) {
                addLog("SPP normal failed, trying reflection...");
            }

            // Try reflection method (works on many clones)
            if(socket == null) {
                try {
                    Method m = device.getClass().getMethod("createRfcommSocket", int.class);
                    socket = (BluetoothSocket) m.invoke(device, 1);
                } catch(Exception e) {
                    addLog("SPP reflection also failed: "+e.getMessage());
                    return;
                }
            }

            btAdapter.cancelDiscovery();
            addLog("SPP: Connecting...");
            socket.connect();
            btSocket = socket;
            outputStream = socket.getOutputStream();

            addLog("✓ SPP Connected!");
            handler.post(() -> onConnected(device.getName()!=null?device.getName():"AirPods Clone", "Classic BT SPP"));

            // Keep reading responses
            InputStream is = socket.getInputStream();
            byte[] buf = new byte[256];
            while(connected) {
                int n = is.read(buf);
                if(n>0) {
                    final String hex = bytesToHex(buf, n);
                    handler.post(() -> addLog("← Received: "+hex));
                }
            }
        } catch(Exception e) {
            addLog("SPP failed: "+e.getMessage());
        }
    }

    // ── Method 2: BLE GATT ────────────────────────────────────
    private void connectGATT(BluetoothDevice device) {
        BluetoothGattCallback cb = new BluetoothGattCallback() {
            @Override
            public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
                if(newState == BluetoothProfile.STATE_CONNECTED && !connected) {
                    addLog("GATT: Connected! Discovering services...");
                    btGatt = g;
                    g.discoverServices();
                } else if(newState == BluetoothProfile.STATE_DISCONNECTED) {
                    addLog("GATT: Disconnected");
                    if(connected) handler.post(() -> disconnect());
                }
            }

            @Override
            public void onServicesDiscovered(BluetoothGatt g, int status) {
                if(status == BluetoothGatt.GATT_SUCCESS && !connected) {
                    List<BluetoothGattService> svcs = g.getServices();
                    addLog("GATT: "+svcs.size()+" services found");
                    // Find writable characteristics
                    for(BluetoothGattService svc : svcs) {
                        for(BluetoothGattCharacteristic ch : svc.getCharacteristics()) {
                            int props = ch.getProperties();
                            if((props & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0 ||
                               (props & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
                                addLog("GATT writable: "+ch.getUuid().toString().substring(0,8)+"...");
                            }
                        }
                    }
                    btGatt = g;
                    handler.post(() -> onConnected(
                        targetDevice.getName()!=null?targetDevice.getName():"AirPods Clone",
                        "BLE GATT"
                    ));
                }
            }

            @Override
            public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
                if(status == BluetoothGatt.GATT_SUCCESS) {
                    addLog("✓ GATT write success: "+c.getUuid().toString().substring(0,8));
                } else {
                    addLog("✗ GATT write failed ("+status+"): "+c.getUuid().toString().substring(0,8));
                }
            }

            @Override
            public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
                if(status == BluetoothGatt.GATT_SUCCESS) {
                    byte[] val = c.getValue();
                    if(val != null && val.length > 0 && val[0] >= 1 && val[0] <= 100) {
                        int pct = val[0] & 0xFF;
                        handler.post(() -> {
                            tvBattLeft.setText(pct+"%");
                            tvBattRight.setText(pct+"%");
                            addLog("✓ Battery: "+pct+"%");
                        });
                    }
                }
            }
        };

        try {
            if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(this, false, cb, BluetoothDevice.TRANSPORT_LE);
            } else {
                device.connectGatt(this, false, cb);
            }
        } catch(Exception e) {
            addLog("GATT connect error: "+e.getMessage());
        }
    }

    // ── On connected ──────────────────────────────────────────
    private void onConnected(String name, String method) {
        connected = true;
        tvDeviceName.setText(name);
        tvStatus.setText("● Connected via "+method);
        tvStatus.setTextColor(0xFF34C759);
        btnConnect.setText("Disconnect");
        btnConnect.setEnabled(true);
        btnConnect.setBackgroundColor(0xFFFF3B30);
        addLog("✓ CONNECTED via "+method+"!");
        setStatus("Connected! Try noise control buttons.");
        tryReadBattery();
    }

    // ── Send noise command ────────────────────────────────────
    private void sendNoise(byte[][] cmdSets, String label) {
        if(!connected) { setStatus("Connect first!"); return; }
        addLog("Sending "+label+" command...");
        setActiveNoise(label);

        // Try SPP first
        if(outputStream != null) {
            new Thread(() -> {
                int sent = 0;
                for(byte[] cmd : cmdSets) {
                    try {
                        outputStream.write(cmd);
                        outputStream.flush();
                        addLog("✓ SPP sent: "+label+" ("+bytesToHex(cmd, cmd.length)+")");
                        sent++;
                        Thread.sleep(200);
                    } catch(Exception e) {
                        addLog("SPP write fail: "+e.getMessage());
                    }
                }
                final int s=sent;
                handler.post(()->{
                    if(s>0) setStatus("✓ "+label+" sent via Classic BT!");
                });
            }).start();
        }

        // Try GATT simultaneously
        if(btGatt != null) {
            new Thread(() -> {
                List<BluetoothGattService> svcs = btGatt.getServices();
                for(BluetoothGattService svc : svcs) {
                    for(BluetoothGattCharacteristic ch : svc.getCharacteristics()) {
                        int props = ch.getProperties();
                        if((props & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0 ||
                           (props & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
                            for(byte[] cmd : cmdSets) {
                                ch.setValue(cmd);
                                btGatt.writeCharacteristic(ch);
                                try { Thread.sleep(100); } catch(Exception ignored){}
                            }
                        }
                    }
                }
            }).start();
        }
    }

    private void setActiveNoise(String label) {
        btnOff.setBackgroundColor(0xFFE5E5EA);
        btnTransparency.setBackgroundColor(0xFFE5E5EA);
        btnAdaptive.setBackgroundColor(0xFFE5E5EA);
        btnANC.setBackgroundColor(0xFFE5E5EA);
        switch(label) {
            case "Off": btnOff.setBackgroundColor(0xFF007AFF); break;
            case "Transparency": btnTransparency.setBackgroundColor(0xFF007AFF); break;
            case "Adaptive": btnAdaptive.setBackgroundColor(0xFF007AFF); break;
            case "Noise Cancel": btnANC.setBackgroundColor(0xFF007AFF); break;
        }
    }

    private void tryReadBattery() {
        if(btGatt == null) return;
        new Thread(() -> {
            try { Thread.sleep(1000); } catch(Exception ignored){}
            List<BluetoothGattService> svcs = btGatt.getServices();
            for(BluetoothGattService svc : svcs) {
                for(BluetoothGattCharacteristic ch : svc.getCharacteristics()) {
                    if((ch.getProperties() & BluetoothGattCharacteristic.PROPERTY_READ) != 0) {
                        btGatt.readCharacteristic(ch);
                        try { Thread.sleep(300); } catch(Exception ignored){}
                    }
                }
            }
        }).start();
    }

    private void disconnect() {
        connected = false;
        try { if(btSocket!=null) btSocket.close(); } catch(Exception ignored){}
        try { if(btGatt!=null) { btGatt.disconnect(); btGatt.close(); } } catch(Exception ignored){}
        outputStream = null; btSocket = null; btGatt = null;
        tvDeviceName.setText("No Device");
        tvStatus.setText("● Not connected");
        tvStatus.setTextColor(0xFFFF3B30);
        tvBattLeft.setText("--"); tvBattRight.setText("--"); tvBattCase.setText("--");
        btnConnect.setText("🔵 Connect Earbuds");
        btnConnect.setBackgroundColor(0xFF007AFF);
        addLog("Disconnected.");
        setStatus("Disconnected. Tap Connect to reconnect.");
    }

    private void resetBtn() {
        handler.post(() -> {
            btnConnect.setText("🔵 Connect Earbuds");
            btnConnect.setEnabled(true);
            btnConnect.setBackgroundColor(0xFF007AFF);
        });
    }

    private void setStatus(String s) {
        handler.post(() -> tvStatus.setText(s));
        addLog(s);
    }

    private void addLog(String msg) {
        handler.post(() -> {
            String t = android.text.format.DateFormat.format("HH:mm:ss", new Date()).toString();
            logLines.add(0, "["+t+"] "+msg);
            if(logLines.size()>30) logLines.remove(logLines.size()-1);
            tvLog.setText(android.text.TextUtils.join("\n", logLines));
        });
    }

    private String bytesToHex(byte[] b, int len) {
        StringBuilder sb = new StringBuilder();
        for(int i=0;i<len;i++) sb.append(String.format("%02X ", b[i]));
        return sb.toString().trim();
    }

    @Override
    protected void onDestroy() { super.onDestroy(); disconnect(); }

    @Override
    public void onRequestPermissionsResult(int req, @NonNull String[] perms, @NonNull int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
        if(req==REQUEST_PERMISSIONS) initBluetooth();
    }
}
