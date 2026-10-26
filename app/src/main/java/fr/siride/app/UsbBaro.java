// UsbBaro.java — lecture d'un Arduino (pression + température) branché en USB, pour l'appli Siride
// À placer dans le MÊME package que MainActivity. Remplace la ligne « package » ci-dessous par celle de ton MainActivity.java.
//
// Dépendance Gradle (voir LISEZMOI_vario_usb.md) :
//     implementation 'com.github.mik3y:usb-serial-for-android:3.11.0'   (+ maven { url 'https://jitpack.io' })
//
// Ce que fait la classe :
//   - start(baud)  : cherche un périphérique USB série, demande l'autorisation, ouvre le port, lit les lignes
//                    envoyées par l'Arduino et appelle, dans la page web, window.onUsbBaro("<ligne>").
//   - stop()       : ferme tout.
//   - Reconnexion automatique toutes les 2 s tant que start() est actif (câble débranché/rebranché, autorisation donnée...).
//   - L'état est envoyé à la page par window.onUsbBaroStatus("no_device" | "permission" | "connected" | "error:...").
package fr.siride.app;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebView;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import com.hoho.android.usbserial.util.SerialInputOutputManager;

import org.json.JSONObject;

import java.util.List;

public class UsbBaro implements SerialInputOutputManager.Listener {
    private static final String ACTION_USB_PERMISSION = "siride.USB_PERMISSION";
    private static final int MAX_LINE = 200;

    private final Activity activity;
    private final WebView webView;
    private final UsbManager usbManager;
    private final Handler main = new Handler(Looper.getMainLooper());

    private boolean wanted = false;
    private int baud = 115200;
    private UsbSerialPort port;
    private SerialInputOutputManager ioManager;
    private final StringBuilder line = new StringBuilder();
    private String lastStatus = "";
    private boolean receiverRegistered = false;

    public UsbBaro(Activity activity, WebView webView) {
        this.activity = activity;
        this.webView = webView;
        this.usbManager = (UsbManager) activity.getSystemService(Context.USB_SERVICE);
    }

    // ----------------------------------------------------------------- API (appelée depuis MainActivity, thread UI)

    public void start(int baudRate) {
        baud = baudRate > 0 ? baudRate : 115200;
        if (wanted) { closePort(); }
        wanted = true;
        registerReceiverOnce();
        main.removeCallbacks(retry);
        main.post(retry);
    }

    public void stop() {
        wanted = false;
        main.removeCallbacks(retry);
        closePort();
        unregisterReceiverQuietly();
        status("stopped");
    }

    // ----------------------------------------------------------------- Connexion

    private final Runnable retry = new Runnable() {
        @Override public void run() {
            if (!wanted) return;
            if (port == null) tryConnect();
            main.postDelayed(this, 2000);
        }
    };

    private void tryConnect() {
        try {
            List<UsbSerialDriver> drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager);
            if (drivers.isEmpty()) { status("no_device"); return; }
            UsbSerialDriver driver = drivers.get(0);
            UsbDevice device = driver.getDevice();
            if (!usbManager.hasPermission(device)) {
                status("permission");
                int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_MUTABLE : 0;
                Intent i = new Intent(ACTION_USB_PERMISSION);
                i.setPackage(activity.getPackageName());
                usbManager.requestPermission(device, PendingIntent.getBroadcast(activity, 0, i, flags));
                return;
            }
            UsbDeviceConnection connection = usbManager.openDevice(device);
            if (connection == null) { status("error:ouverture du périphérique USB impossible"); return; }
            UsbSerialPort p = driver.getPorts().get(0);
            p.open(connection);
            p.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
            p.setDTR(true);   // nécessaire pour certaines cartes (Arduino Leonardo/Pro Micro) ; un Uno/Nano redémarre à l'ouverture, c'est normal
            p.setRTS(true);
            port = p;
            line.setLength(0);
            ioManager = new SerialInputOutputManager(p, this);
            ioManager.start();
            status("connected");
        } catch (Exception e) {
            closePort();
            status("error:" + e.getMessage());
        }
    }

    private void closePort() {
        try { if (ioManager != null) { ioManager.setListener(null); ioManager.stop(); } } catch (Exception ignored) {}
        ioManager = null;
        try { if (port != null) port.close(); } catch (Exception ignored) {}
        port = null;
        line.setLength(0);
    }

    // ----------------------------------------------------------------- Réception (thread de lecture USB)

    @Override public void onNewData(byte[] data) {
        for (byte b : data) {
            char c = (char) (b & 0xFF);
            if (c == '\n') {
                if (line.length() > 0) sendLine(line.toString());
                line.setLength(0);
            } else if (c != '\r') {
                if (line.length() < MAX_LINE) line.append(c); else line.setLength(0); // ligne trop longue : poubelle
            }
        }
    }

    @Override public void onRunError(Exception e) {
        // câble débranché, erreur de lecture : on ferme, la reconnexion automatique reprendra.
        main.post(() -> { closePort(); if (wanted) status("no_device"); });
    }

    private void sendLine(String s) {
        final String js = "window.onUsbBaro && window.onUsbBaro(" + JSONObject.quote(s) + ");";
        activity.runOnUiThread(() -> webView.evaluateJavascript(js, null));
    }

    private void status(String st) {
        if (st.equals(lastStatus)) return;
        lastStatus = st;
        final String js = "window.onUsbBaroStatus && window.onUsbBaroStatus(" + JSONObject.quote(st) + ");";
        activity.runOnUiThread(() -> webView.evaluateJavascript(js, null));
    }

    // ----------------------------------------------------------------- Autorisation USB / débranchement

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String a = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(a)) {
                main.post(() -> { if (wanted && port == null) tryConnect(); });
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(a)) {
                main.post(() -> { closePort(); if (wanted) status("no_device"); });
            }
        }
    };

    private void registerReceiverOnce() {
        if (receiverRegistered) return;
        IntentFilter f = new IntentFilter();
        f.addAction(ACTION_USB_PERMISSION);
        f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= 33) activity.registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED);
        else activity.registerReceiver(receiver, f);
        receiverRegistered = true;
    }

    private void unregisterReceiverQuietly() {
        if (!receiverRegistered) return;
        try { activity.unregisterReceiver(receiver); } catch (Exception ignored) {}
        receiverRegistered = false;
    }
}
