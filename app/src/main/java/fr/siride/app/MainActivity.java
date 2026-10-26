package fr.siride.app;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.MediaStore;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.GnssStatus;
import android.os.Looper;
import android.os.SystemClock;
import android.os.Environment;
import android.print.PrintAttributes;
import android.print.PrintManager;
import android.os.Bundle;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Locale;

public class MainActivity extends Activity implements SensorEventListener, LocationListener {
    private static final int LOCATION_PERMISSION_REQUEST = 10;
    private static final int FILE_CHOOSER_REQUEST = 11;
    private static final String APP_ORIGIN = "https://appassets.androidplatform.net/";

    private WebView webView;
    private SensorManager sensorManager;
    private Sensor pressureSensor;
    private LocationManager locationManager;
    private volatile boolean nativeGpsRunning = false;
    private volatile int gnssSatellites = 0;
    // Dernière trame NMEA GGA : altitude MSL et séparation géoïde (utilisées sous Android < 14
    // pour ramener l'altitude GPS au niveau de la mer sans calage manuel).
    private volatile double nmeaMslAlt = Double.NaN;
    private volatile double nmeaGeoidSep = Double.NaN;
    private volatile long nmeaStampMs = 0;
    private final android.location.OnNmeaMessageListener nmeaListener = (message, timestamp) -> {
        try {
            if (message == null || message.length() < 7 || !message.regionMatches(3, "GGA", 0, 3)) return;
            String[] f = message.split(",", -1);
            if (f.length < 12 || f[6].isEmpty() || "0".equals(f[6]) || f[9].isEmpty() || f[11].isEmpty()) return;
            nmeaMslAlt = Double.parseDouble(f[9]);
            nmeaGeoidSep = Double.parseDouble(f[11]);
            nmeaStampMs = SystemClock.elapsedRealtime();
        } catch (Exception ignored) {}
    };
    private final GnssStatus.Callback gnssStatusCallback = new GnssStatus.Callback() {
        @Override public void onSatelliteStatusChanged(GnssStatus status) {
            int used = 0;
            for (int i = 0; i < status.getSatelliteCount(); i++) {
                if (status.usedInFix(i)) used++;
            }
            gnssSatellites = used;
        }
    };
    private volatile float pressureHpa = Float.NaN;
    private volatile long pressureTime = 0;
    private ValueCallback<Uri[]> filePathCallback;
    private UsbBaro usbBaro;   // vario externe USB (Arduino + BMP580)
    private SensorBridge sensors; // accéléromètre + compas (appli Vol), exposé sous "AndroidSensors"

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        setContentView(webView);
        usbBaro = new UsbBaro(this, webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setGeolocationEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);

        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                boolean granted = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                        || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
                callback.invoke(origin, granted, false);
            }

            @Override
            public boolean onShowFileChooser(
                    WebView webView,
                    ValueCallback<Uri[]> newFilePathCallback,
                    FileChooserParams fileChooserParams
            ) {
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                }
                filePathCallback = newFilePathCallback;

                Intent intent;
                try {
                    intent = fileChooserParams.createIntent();
                    startActivityForResult(intent, FILE_CHOOSER_REQUEST);
                    return true;
                } catch (Exception e) {
                    filePathCallback = null;
                    Toast.makeText(MainActivity.this, "Impossible d'ouvrir le sélecteur de fichiers.", Toast.LENGTH_SHORT).show();
                    return false;
                }
            }
        });

        // Le pont JavaScript n'est exposé qu'au contenu local de confiance chargé ci-dessous.
        webView.addJavascriptInterface(new AndroidBridge(), "Android");
        sensors = new SensorBridge(this);
        webView.addJavascriptInterface(sensors, "AndroidSensors");
        // Garde l'écran allumé pendant le vol
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        pressureSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE);

        try {
            String html = readAssetText("index.html");
            // Une base HTTPS donne à la page une origine sécurisée : nécessaire pour
            // navigator.geolocation sur les WebView Android récentes.
            webView.loadDataWithBaseURL(APP_ORIGIN, html, "text/html", "UTF-8", null);
        } catch (IOException e) {
            Toast.makeText(this, "Impossible de charger l'application Siride.", Toast.LENGTH_LONG).show();
        }

        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION},
                    LOCATION_PERMISSION_REQUEST
            );
        }
    }

    private String readAssetText(String filename) throws IOException {
        try (InputStream input = getAssets().open(filename);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_CHOOSER_REQUEST && filePathCallback != null) {
            Uri[] results = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
            filePathCallback.onReceiveValue(results);
            filePathCallback = null;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == LOCATION_PERMISSION_REQUEST) {
            boolean granted = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                    || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
            // Prévient la page web du résultat, qu'il soit positif ou négatif, pour
            // qu'elle relance le GPS elle-même au lieu de rester bloquée en attente.
            if (webView != null) {
                webView.evaluateJavascript(
                        "window.onLocationPermissionResult && window.onLocationPermissionResult(" + granted + ");",
                        null
                );
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (pressureSensor != null && sensorManager != null) {
            sensorManager.registerListener(this, pressureSensor, SensorManager.SENSOR_DELAY_GAME);
        }
        if (sensors != null) sensors.start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (sensors != null) sensors.stop();
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
    }

    @Override
    protected void onDestroy() {
        stopNativeGpsInternal();
        if (usbBaro != null) usbBaro.stop();
        if (webView != null) {
            webView.removeJavascriptInterface("Android");
            webView.removeJavascriptInterface("AndroidSensors");
            webView.destroy();
        }
        super.onDestroy();
    }

    private boolean hasLocationPermissionInternal() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void startNativeGpsInternal() {
        if (locationManager == null || !hasLocationPermissionInternal()) return;
        if (nativeGpsRunning) return;
        try {
            if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                sendNativeGpsStatus("GPS — activé mais fournisseur GPS désactivé");
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                android.os.Handler mainHandler = new android.os.Handler(Looper.getMainLooper());
                locationManager.registerGnssStatusCallback(gnssStatusCallback, mainHandler);
                try { locationManager.addNmeaListener(nmeaListener, mainHandler); } catch (Exception ignored) {}
            }
            // GPS_PROVIDER : vitesse issue de la solution GNSS Android. Quand le
            // chipset fournit une mesure Doppler, Location.getSpeed() la restitue
            // directement en m/s. Cette voie native évite de dépendre de
            // navigator.geolocation pour la vitesse Doppler.
            locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    500L,
                    0f,
                    this,
                    Looper.getMainLooper()
            );
            nativeGpsRunning = true;
            sendNativeGpsStatus("GPS — acquisition native…");
        } catch (SecurityException ignored) {
            nativeGpsRunning = false;
            sendNativeGpsStatus("GPS — autorisation refusée");
        } catch (Exception e) {
            nativeGpsRunning = false;
            sendNativeGpsStatus("GPS — erreur acquisition");
        }
    }

    private void stopNativeGpsInternal() {
        if (locationManager != null) {
            try { locationManager.removeUpdates(this); } catch (Exception ignored) {}
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try { locationManager.unregisterGnssStatusCallback(gnssStatusCallback); } catch (Exception ignored) {}
                try { locationManager.removeNmeaListener(nmeaListener); } catch (Exception ignored) {}
            }
        }
        nativeGpsRunning = false;
    }

    private void sendNativeGpsStatus(String text) {
        if (webView == null) return;
        runOnUiThread(() -> webView.evaluateJavascript(
                "window.onNativeGpsStatus && window.onNativeGpsStatus(" + JSONObject.quote(text) + ");", null));
    }
	
	private static Object num(double v) {
		return (Double.isNaN(v) || Double.isInfinite(v)) ? JSONObject.NULL : (Object) v;
	}
    
	private void sendNativeLocation(Location location) {
        if (location == null) return;
        try {
            JSONObject coords = new JSONObject();
            coords.put("latitude", location.getLatitude());
            coords.put("longitude", location.getLongitude());
			coords.put("accuracy", location.hasAccuracy() ? num(location.getAccuracy()) : JSONObject.NULL);
            // Altitude : on cherche d'abord une altitude réelle (MSL) pour ne plus dépendre
            // du calage manuel de l'ondulation du géoïde.
            //  1) Android 14+ : Location.getMslAltitudeMeters() (+ sa précision) ;
            //  2) Android 7-13 : séparation géoïde lue dans la trame NMEA GGA (variation très
            //     lente, donc sans retard gênant), appliquée seulement si la comparaison avec la
            //     trame prouve que getAltitude() est bien ellipsoïdale ;
            //  3) sinon : altitude brute, signalée « non MSL » (le JS applique alors gpsAltCorr).
            Double alt = null;
            Double vacc = null;
            boolean isMsl = false;
            if (location.hasAltitude()) {
                alt = location.getAltitude();
                if (Build.VERSION.SDK_INT >= 34 && location.hasMslAltitude()) {
                    alt = location.getMslAltitudeMeters();
                    isMsl = true;
                    if (location.hasMslAltitudeAccuracy()) vacc = (double) location.getMslAltitudeAccuracyMeters();
                } else if (nmeaStampMs > 0 && SystemClock.elapsedRealtime() - nmeaStampMs < 5000
                        && !Double.isNaN(nmeaGeoidSep) && Math.abs(nmeaGeoidSep) >= 10.0) {
                    double a = location.getAltitude();
                    boolean isEllipsoid = Math.abs(a - (nmeaMslAlt + nmeaGeoidSep)) < Math.abs(a - nmeaMslAlt);
                    if (isEllipsoid) alt = a - nmeaGeoidSep;
                    isMsl = true;
                }
                if (vacc == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && location.hasVerticalAccuracy()) {
                    vacc = (double) location.getVerticalAccuracyMeters();
                }
            }
			coords.put("altitude", alt != null ? num(alt) : JSONObject.NULL);
			coords.put("altitudeAccuracy", vacc != null ? num(vacc) : JSONObject.NULL);
         //   coords.put("altitude", alt != null ? alt : JSONObject.NULL);
         //   coords.put("altitudeAccuracy", vacc != null ? vacc : JSONObject.NULL);
            coords.put("altitudeIsMsl", isMsl);
			
            // getSpeed() est en m/s. On transmet la valeur brute sans conversion
            // ni filtrage pour permettre au JavaScript de diagnostiquer précisément
            // la chaîne Doppler.
			
           // coords.put("speed", location.hasSpeed() ? location.getSpeed() : JSONObject.NULL);
           // coords.put("heading", location.hasBearing() ? location.getBearing() : JSONObject.NULL);
           // coords.put("speedAccuracy", location.hasSpeedAccuracy() ? location.getSpeedAccuracyMetersPerSecond() : JSONObject.NULL);
			coords.put("speed", location.hasSpeed() ? num(location.getSpeed()) : JSONObject.NULL);
			coords.put("heading", location.hasBearing() ? num(location.getBearing()) : JSONObject.NULL);
			coords.put("speedAccuracy", location.hasSpeedAccuracy() ? num(location.getSpeedAccuracyMetersPerSecond()) : JSONObject.NULL);

		   JSONObject payload = new JSONObject();
            payload.put("coords", coords);
            payload.put("timestamp", location.getTime() > 0 ? location.getTime() : System.currentTimeMillis());
            if (webView != null) {
                runOnUiThread(() -> webView.evaluateJavascript(
                        "window.onNativeLocation && window.onNativeLocation(" + payload.toString() + ");", null));
            }
        } 
		catch (Exception e) { android.util.Log.e("Siride", "sendNativeLocation: point perdu", e); }
    }

    @Override
    public void onLocationChanged(Location location) {
        sendNativeLocation(location);
    }

    @Override public void onProviderEnabled(String provider) { sendNativeGpsStatus("GPS — fournisseur activé"); }
    @Override public void onProviderDisabled(String provider) { sendNativeGpsStatus("GPS — fournisseur désactivé"); }
    @Override public void onStatusChanged(String provider, int status, android.os.Bundle extras) {}

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_PRESSURE) {
            pressureHpa = event.values[0];
            pressureTime = System.currentTimeMillis();
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // Rien à faire.
    }


    public class AndroidBridge {
        @JavascriptInterface
        public boolean hasBarometer() {
            return pressureSensor != null;
        }

        @JavascriptInterface
        public boolean hasLocationPermission() {
            return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                    || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        }

        @JavascriptInterface
        public void requestLocationPermission() {
            runOnUiThread(() -> requestPermissions(
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION},
                    LOCATION_PERMISSION_REQUEST
            ));
        }

        @JavascriptInterface
        public void startNativeGps() {
            runOnUiThread(() -> startNativeGpsInternal());
        }

        @JavascriptInterface
        public void stopNativeGps() {
            runOnUiThread(() -> stopNativeGpsInternal());
        }

        // ---- Compatibilité appli Vol ----
        // L'appli Vol appelle Android.getSatCount() et Android.usbConnect().
        @JavascriptInterface
        public int getSatCount() {
            return gnssSatellites;
        }

        @JavascriptInterface
        public void usbConnect() {
            runOnUiThread(() -> { if (usbBaro != null) usbBaro.start(115200); });
        }

        // ---- Vario externe USB (Arduino) ----
        @JavascriptInterface
        public void startUsbBaro(int baud) {
            runOnUiThread(() -> { if (usbBaro != null) usbBaro.start(baud); });
        }

        @JavascriptInterface
        public void stopUsbBaro() {
            runOnUiThread(() -> { if (usbBaro != null) usbBaro.stop(); });
        }

        @JavascriptInterface
        public String getGnssStatusJson() {
            try {
                JSONObject json = new JSONObject();
                json.put("satellites", gnssSatellites);
                json.put("native_running", nativeGpsRunning);
                return json.toString();
            } catch (Exception e) {
                return "{\"satellites\":0,\"native_running\":false}";
            }
        }


        @JavascriptInterface
        public void printWebView() {
            runOnUiThread(() -> {
                try {
                    PrintManager printManager = (PrintManager) getSystemService(Context.PRINT_SERVICE);
                    if (printManager == null) {
                        Toast.makeText(MainActivity.this, "Impression indisponible sur cet appareil.", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    // Le paysage reste l'orientation proposée par défaut, mais on ne
                    // verrouille plus la mise en page : le dialogue Android peut passer
                    // en portrait puis revenir en paysage sans générer une page blanche.
                    PrintAttributes attributes = new PrintAttributes.Builder()
                            .setMediaSize(new PrintAttributes.MediaSize("SirideA4L", "Siride A4 paysage", 11693, 8268))
                            .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                            .build();
                    printManager.print("Siride - graphique", webView.createPrintDocumentAdapter("Siride - graphique"), attributes);
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "Impossible de lancer l'impression.", Toast.LENGTH_SHORT).show();
                }
            });
        }
             //L’application n’avait aucune fonction pour se fermer. Le bouton appelle Android.quitApp()
        @JavascriptInterface
			public void quitApp() {
			runOnUiThread(() -> finishAndRemoveTask());
		}

        @JavascriptInterface
        public String getBarometerJson() {
            try {
                JSONObject json = new JSONObject();
                json.put("available", pressureSensor != null && !Float.isNaN(pressureHpa));
                json.put("pressure_hpa", Float.isNaN(pressureHpa) ? JSONObject.NULL : pressureHpa);
                json.put("timestamp_ms", pressureTime);
                return json.toString();
            } catch (Exception e) {
                return "{\"available\":false}";
            }
        }

        @JavascriptInterface
        public void openManualPdf() {
            runOnUiThread(() -> {
                try {
                    // Le WebView ne sait pas afficher un PDF lui-même (settings.setAllowFileAccess(false)
                    // interdit de toute façon le file:// direct) : on copie le PDF embarqué dans les
                    // assets vers le cache de l'appli, puis on l'ouvre avec la visionneuse PDF du
                    // téléphone via une URI content:// (FileProvider). Le bouton "retour" (téléphone
                    // ou visionneuse) referme cet écran et revient directement sur Siride, sur la
                    // page Données, sans code supplémentaire côté web.
                    File dir = new File(getCacheDir(), "manual");
                    if (!dir.exists()) dir.mkdirs();
                    File pdfFile = new File(dir, "manuel_operateur.pdf");
                    try (InputStream input = getAssets().open("manuel_operateur.pdf");
                         OutputStream output = new FileOutputStream(pdfFile)) {
                        byte[] buffer = new byte[8192];
                        int count;
                        while ((count = input.read(buffer)) != -1) {
                            output.write(buffer, 0, count);
                        }
                    }
                    Uri uri = FileProvider.getUriForFile(MainActivity.this, "fr.polaire.app.fileprovider", pdfFile);
                    Intent intent = new Intent(Intent.ACTION_VIEW);
                    intent.setDataAndType(uri, "application/pdf");
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(intent, "Ouvrir le manuel opérateur"));
                } catch (ActivityNotFoundException e) {
                    Toast.makeText(MainActivity.this, "Aucune visionneuse PDF installée sur cet appareil.", Toast.LENGTH_LONG).show();
                } catch (IOException e) {
                    Toast.makeText(MainActivity.this, "Impossible d'ouvrir le manuel (fichier introuvable).", Toast.LENGTH_LONG).show();
                }
            });
        }

        // ============ Export CSV / IGC (runs, trajectoire) ============
        // Appelée par exportCsv() côté JS -- absente jusqu'ici, ce qui faisait
        // échouer silencieusement TOUS les exports (CSV runs, IGC runs, CSV
        // trajectoire complète) : la méthode Android.exportFile() n'existait
        // simplement pas côté natif. Deux effets, dans cet ordre :
        // 1) une copie persistante dans le dossier public "Téléchargements"
        //    (MediaStore, sans permission de stockage nécessaire sur Android
        //    10+/API 29+) -- pour que le fichier soit toujours retrouvable
        //    dans n'importe quel gestionnaire de fichiers, même si l'écran de
        //    partage ci-dessous est refermé sans choisir de destination ;
        // 2) l'ouverture du menu de partage natif (même principe que
        //    openManualPdf() ci-dessus), pour envoyer directement le fichier
        //    par mail/Drive/etc. sans repasser par un gestionnaire de fichiers.
        @JavascriptInterface
        public void exportFile(String name, String content) {
            runOnUiThread(() -> {
                String rawName = (name == null || name.trim().isEmpty()) ? "siride-export.txt" : name.trim();
                String safeName = rawName.replace("/", "_").replace("\\", "_").replace("..", "_");
                String mime = safeName.toLowerCase().endsWith(".igc") ? "application/octet-stream" : "text/csv";
                byte[] bytes = content == null ? new byte[0] : content.getBytes(StandardCharsets.UTF_8);
                boolean savedToDownloads = false;

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    try {
                        ContentValues values = new ContentValues();
                        values.put(MediaStore.Downloads.DISPLAY_NAME, safeName);
                        values.put(MediaStore.Downloads.MIME_TYPE, mime);
                        values.put(MediaStore.Downloads.IS_PENDING, 1);
                        Uri downloadsUri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                        if (downloadsUri != null) {
                            try (OutputStream out = getContentResolver().openOutputStream(downloadsUri)) {
                                if (out != null) {
                                    out.write(bytes);
                                    savedToDownloads = true;
                                }
                            }
                            values.clear();
                            values.put(MediaStore.Downloads.IS_PENDING, 0);
                            getContentResolver().update(downloadsUri, values, null, null);
                        }
                    } catch (Exception e) {
                        savedToDownloads = false;
                    }
                }

                try {
                    File dir = new File(getCacheDir(), "export");
                    if (!dir.exists()) dir.mkdirs();
                    File outFile = new File(dir, safeName);
                    try (FileOutputStream out = new FileOutputStream(outFile)) {
                        out.write(bytes);
                    }
                    Uri shareUri = FileProvider.getUriForFile(MainActivity.this, "fr.siride.app.fileprovider", outFile);
                    Intent intent = new Intent(Intent.ACTION_SEND);
                    intent.setType(mime);
                    intent.putExtra(Intent.EXTRA_STREAM, shareUri);
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(intent, "Partager " + safeName));
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "Fichier enregistré, mais impossible d'ouvrir le partage.", Toast.LENGTH_LONG).show();
                }

                if (savedToDownloads) {
                    Toast.makeText(MainActivity.this, "Enregistré dans Téléchargements : " + safeName, Toast.LENGTH_LONG).show();
                }
            });
        }

        // ============ Stockage des runs enregistrés (fichiers, pas localStorage) ============
        // Chaque run/session Siride est écrit comme un fichier JSON dans un dossier dédié
        // de l'espace privé de l'appli (Android/data/fr.siride.app/files/runs), à l'image de
        // ce que fait XCTrack avec son dossier de vols. Aucune permission de stockage n'est
        // requise : ce dossier appartient à l'appli.
        private File runsDir() {
            File base = getExternalFilesDir(null);
            if (base == null) base = getFilesDir();
            File dir = new File(base, "runs");
            if (!dir.exists()) dir.mkdirs();
            return dir;
        }

        // Une clé logique ("polar:index", "polar:s1234567890") est convertie en nom de
        // fichier sûr : seuls lettres, chiffres, "-" et "_" sont conservés.
        private File runFile(String key) {
            StringBuilder safe = new StringBuilder();
            for (char c : key.toCharArray()) {
                safe.append(Character.isLetterOrDigit(c) || c == '-' || c == '_' ? c : '_');
            }
            return new File(runsDir(), safe.toString() + ".json");
        }

        @JavascriptInterface
        public String storageGet(String key) {
            File f = runFile(key);
            if (!f.exists()) return null;
            try (InputStream input = new java.io.FileInputStream(f);
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                return output.toString(StandardCharsets.UTF_8.name());
            } catch (IOException e) {
                return null;
            }
        }

        @JavascriptInterface
        public boolean storageSet(String key, String value) {
            // Écriture atomique : on écrit d'abord un fichier temporaire, on force
            // les octets sur disque, puis on remplace l'ancien fichier. Ainsi une
            // coupure pendant l'écriture ne laisse jamais un JSON partiellement écrit.
            File target = runFile(key);
            File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(bytes);
                out.flush();
                out.getFD().sync();
            } catch (IOException e) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                return false;
            }
            if (!tmp.renameTo(target)) {
                // Certains systèmes de fichiers n'écrasent pas un fichier existant
                // avec renameTo : suppression contrôlée puis second essai.
                if (target.exists() && !target.delete()) {
                    //noinspection ResultOfMethodCallIgnored
                    tmp.delete();
                    return false;
                }
                if (!tmp.renameTo(target)) {
                    //noinspection ResultOfMethodCallIgnored
                    tmp.delete();
                    return false;
                }
            }
            return true;
        }

        @JavascriptInterface
        public boolean storageDelete(String key) {
            File f = runFile(key);
            return !f.exists() || f.delete();
        }

        // Liste directement le contenu du répertoire des runs (et non un index qui pourrait
        // être désynchronisé) : c'est ce qui alimente la fenêtre "Runs enregistrés" côté JS.
        @JavascriptInterface
        public String storageList(String prefix) {
            File[] files = runsDir().listFiles();
            JSONArray arr = new JSONArray();
            if (files != null) {
                for (File f : files) {
                    String name = f.getName();
                    if (name.endsWith(".json")) name = name.substring(0, name.length() - 5);
                    if (prefix == null || prefix.isEmpty() || name.startsWith(sanitizedPrefix(prefix))) {
                        arr.put(name);
                    }
                }
            }
            return arr.toString();
        }

        private String sanitizedPrefix(String key) {
            StringBuilder safe = new StringBuilder();
            for (char c : key.toCharArray()) {
                safe.append(Character.isLetterOrDigit(c) || c == '-' || c == '_' ? c : '_');
            }
            return safe.toString();
        }

    }
}
