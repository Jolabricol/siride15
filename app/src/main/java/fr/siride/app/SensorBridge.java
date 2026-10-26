package fr.siride.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.webkit.JavascriptInterface;

/**
 * Pont capteurs pour la WebView : FUSION accéléromètre + gyroscope + magnétomètre (+ baromètre).
 * Exposé en JavaScript sous le nom "AndroidSensors".
 *
 *   AndroidSensors.start() / stop()
 *   AndroidSensors.getSensors()      -> "ax,ay,az,cap,tangage,roulis,pression_hPa,vz_fusion,qualité,déclinaison"
 *                                       (m/s², degrés ; cap magnétique = -1 si inconnu ; qualité 0 = inconnu, 1 = gyroscope seul
 *                                        (compas perturbé), 2 = compas fiable ; les champs au-delà du 6e peuvent être vides)
 *   AndroidSensors.setLocation(lat, lon, alt) -> permet de calculer la déclinaison magnétique automatiquement
 *   AndroidSensors.startMagCal()     -> démarre l'enregistrement des mesures du magnétomètre
 *   AndroidSensors.stopMagCal()      -> calcule et mémorise le calibrage ; retourne "ok" ou "insuffisant"
 *   AndroidSensors.hasMagCal()       -> true si un calibrage compas est mémorisé
 *   AndroidSensors.resetMagCal()
 *   AndroidSensors.getBatteryMicroAmps() / getBatteryChargeUAh() -> consommation batterie (test)
 *   AndroidSensors.setScreenDim(true/false) -> luminosité minimale (écran noir) / retour à la luminosité système
 */
public class SensorBridge implements SensorEventListener {
    private final SensorManager sm;
    private final SharedPreferences sp;
    private final Sensor acc, mag, magRaw, gyro, baro;
    private final float[] a = new float[3], m = new float[3], mr = new float[3];
    private boolean hasA, hasM, hasMR, running;
    private final Fusion fus = new Fusion();
    private long tGyro, tAcc;
    private double peakA;                                       // pic de |accélération| - g depuis la dernière lecture (chocs)
    private float presHpa = Float.NaN;
    private float declination = Float.NaN;
    private final android.location.LocationManager lm;
    private volatile double nmeaAlt = Double.NaN;
    private volatile long nmeaT;
    private volatile int sats = -1;
    private volatile long satsT;
    private volatile double pdop = Double.NaN;
    private volatile long pdopT;
    private final android.location.OnNmeaMessageListener nmeaL = new android.location.OnNmeaMessageListener() {
        @Override public void onNmeaMessage(String msg, long ts) {
            try {
                int g = msg.indexOf("GSA,");
                if (g >= 0) {
                    // GSA : [2]=type de fix (1 = aucun, 2 = 2D, 3 = 3D), [15]=PDOP, [16]=HDOP, [17]=VDOP
                    String[] h = msg.substring(g).split(",", -1);
                    if (h.length > 15 && !"1".equals(h[2]) && !h[15].isEmpty()) {
                        pdop = Double.parseDouble(h[15]);
                        pdopT = System.currentTimeMillis();
                    }
                    return;
                }
                int i = msg.indexOf("GGA,");
                if (i < 0) return;
                String[] f = msg.substring(i).split(",", -1);
                // GGA : [6]=qualité du fix, [9]=altitude au-dessus du niveau moyen de la mer (m)
                if (f.length > 9 && !f[6].isEmpty() && !"0".equals(f[6]) && !f[9].isEmpty()) {
                    nmeaAlt = Double.parseDouble(f[9]);
                    nmeaT = System.currentTimeMillis();
                }
                // GGA : [7]=nombre de satellites utilisés dans la solution
                if (f.length > 7 && !f[6].isEmpty() && !"0".equals(f[6]) && !f[7].isEmpty()) {
                    sats = Integer.parseInt(f[7].trim());
                    satsT = System.currentTimeMillis();
                }
            } catch (Exception ignored) {}
        }
    };

    // calibrage compas : biais (hard-iron) et échelle (soft-iron simplifiée) sur les valeurs brutes
    private final float[] bias = new float[3];
    private final float[] scale = {1, 1, 1};
    private boolean calibrated;

    // enregistrement pendant le calibrage
    private boolean calRec;
    private final float[] mn = new float[3], mx = new float[3];
    private int calN;

    private final Context ctx;

    public SensorBridge(Context c) {
        ctx = c;
        sm = (SensorManager) c.getSystemService(Context.SENSOR_SERVICE);
        lm = (android.location.LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
        sp = c.getSharedPreferences("sensorbridge", Context.MODE_PRIVATE);
        acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        mag = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        magRaw = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED);
        gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        baro = sm.getDefaultSensor(Sensor.TYPE_PRESSURE);
        calibrated = sp.getBoolean("cal", false);
        for (int i = 0; i < 3; i++) {
            bias[i] = sp.getFloat("b" + i, 0f);
            scale[i] = sp.getFloat("s" + i, 1f);
        }
    }

    /** Écran noir : luminosité au minimum (dim=true) ou rendue au système (dim=false). */
    @JavascriptInterface public void setScreenDim(final boolean dim) {
        if (!(ctx instanceof android.app.Activity)) return;
        final android.app.Activity a = (android.app.Activity) ctx;
        a.runOnUiThread(new Runnable() { @Override public void run() {
            try {
                android.view.WindowManager.LayoutParams lp = a.getWindow().getAttributes();
                lp.screenBrightness = dim ? 0.01f : android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
                a.getWindow().setAttributes(lp);
            } catch (Exception e) { }
        } });
    }

    /** Courant instantané de la batterie en µA (valeur absolue du signe du constructeur), 0 si indisponible. */
    @JavascriptInterface public int getBatteryMicroAmps() {
        try {
            android.os.BatteryManager bm = (android.os.BatteryManager) ctx.getSystemService(Context.BATTERY_SERVICE);
            return bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
        } catch (Exception e) { return 0; }
    }

    /** Charge restante de la batterie en µAh (compteur de charge), 0 si indisponible. */
    @JavascriptInterface public int getBatteryChargeUAh() {
        try {
            android.os.BatteryManager bm = (android.os.BatteryManager) ctx.getSystemService(Context.BATTERY_SERVICE);
            return bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER);
        } catch (Exception e) { return 0; }
    }

    @JavascriptInterface public synchronized void start() {
        if (running) return;
        running = true;
        fus.reset(); tGyro = 0; tAcc = 0;
        if (acc != null) sm.registerListener(this, acc, SensorManager.SENSOR_DELAY_GAME);
        if (gyro != null) sm.registerListener(this, gyro, SensorManager.SENSOR_DELAY_GAME);
        if (mag != null) sm.registerListener(this, mag, SensorManager.SENSOR_DELAY_GAME);
        if (magRaw != null) sm.registerListener(this, magRaw, SensorManager.SENSOR_DELAY_GAME);
        if (baro != null) sm.registerListener(this, baro, SensorManager.SENSOR_DELAY_GAME);
        // NMEA : altitude GPS au-dessus du niveau de la mer (géoïde), pour corriger l'altitude ellipsoïdale automatiquement
        try { if (lm != null) lm.addNmeaListener(nmeaL, new android.os.Handler(android.os.Looper.getMainLooper())); } catch (Exception ignored) {}
    }

    @JavascriptInterface public synchronized void stop() {
        if (!running) return;
        running = false;
        sm.unregisterListener(this);
        try { if (lm != null) lm.removeNmeaListener(nmeaL); } catch (Exception ignored) {}
    }

    /** position GPS : sert à calculer la déclinaison magnétique (nord magnétique -> nord vrai) */
    @JavascriptInterface public synchronized void setLocation(double lat, double lon, double alt) {
        try {
            declination = new android.hardware.GeomagneticField((float) lat, (float) lon, (float) alt, System.currentTimeMillis()).getDeclination();
        } catch (Exception ignored) {}
    }

    @Override public synchronized void onSensorChanged(SensorEvent e) {
        switch (e.sensor.getType()) {
            case Sensor.TYPE_ACCELEROMETER: {
                System.arraycopy(e.values, 0, a, 0, 3); hasA = true;
                { double dv = Math.abs(Math.sqrt((double) a[0] * a[0] + (double) a[1] * a[1] + (double) a[2] * a[2]) - 9.80665); if (dv > peakA) peakA = dv; }
                double dt = tAcc == 0 ? 0 : (e.timestamp - tAcc) * 1e-9; tAcc = e.timestamp;
                if (!fus.ready) { if (hasM || hasMR) fus.init(a, field()); }
                else if (dt > 0 && dt < 0.2) {
                    if (gyro == null) fus.step(0, 0, 0, a, field(), hasM || hasMR, dt, true);
                    fus.predictVertical(a, dt);
                }
                break;
            }
            case Sensor.TYPE_GYROSCOPE: {
                double dt = tGyro == 0 ? 0 : (e.timestamp - tGyro) * 1e-9; tGyro = e.timestamp;
                if (fus.ready && dt > 0 && dt < 0.2) fus.step(e.values[0], e.values[1], e.values[2], a, field(), hasM || hasMR, dt, false);
                break;
            }
            case Sensor.TYPE_MAGNETIC_FIELD:
                System.arraycopy(e.values, 0, m, 0, 3); hasM = true; break;
            case Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED:
                System.arraycopy(e.values, 0, mr, 0, 3); hasMR = true;
                if (calRec) {
                    if (calN == 0) for (int i = 0; i < 3; i++) { mn[i] = mr[i]; mx[i] = mr[i]; }
                    for (int i = 0; i < 3; i++) { mn[i] = Math.min(mn[i], mr[i]); mx[i] = Math.max(mx[i], mr[i]); }
                    calN++;
                }
                break;
            case Sensor.TYPE_PRESSURE:
                presHpa = e.values[0];
                fus.updateBaro(presHpa);
                break;
        }
    }

    @Override public void onAccuracyChanged(Sensor s, int accuracy) {}

    /** champ magnétique utilisé : brut corrigé par NOTRE calibrage si présent, sinon celui du système */
    private float[] field() {
        if (calibrated && hasMR) {
            return new float[]{(mr[0] - bias[0]) * scale[0], (mr[1] - bias[1]) * scale[1], (mr[2] - bias[2]) * scale[2]};
        }
        return m.clone();
    }

    /** "ax,ay,az,cap,tangage,roulis,pressionHPa,vzFusionné,qualité,déclinaison,altitudeMerNMEA,gyroDegS,picChocMs2" (champs vides si indisponibles) */
    @JavascriptInterface public synchronized String getSensors() {
        if (!hasA) return "";
        double[] o = fus.ready ? fus.output() : null;
        String cap = o == null ? "-1" : String.valueOf(o[0]);
        String pitch = o == null ? "0" : String.valueOf(o[1]);
        String roll = o == null ? "0" : String.valueOf(o[2]);
        String q = o == null ? "0" : String.valueOf((int) o[3]);
        String pr = Float.isNaN(presHpa) ? "" : String.valueOf(presHpa);
        String vz = fus.baroOk() ? String.valueOf(fus.vz()) : "";
        String dc = Float.isNaN(declination) ? "" : String.valueOf(declination);
        String ms = (!Double.isNaN(nmeaAlt) && System.currentTimeMillis() - nmeaT < 4000) ? String.valueOf(nmeaAlt) : "";
        String res = a[0] + "," + a[1] + "," + a[2] + "," + cap + "," + pitch + "," + roll + "," + pr + "," + vz + "," + q + "," + dc + "," + ms + "," + (fus.wLp < 0 ? "" : String.valueOf(fus.wLp)) + "," + peakA + "," + ((!Double.isNaN(pdop) && System.currentTimeMillis() - pdopT < 5000) ? String.valueOf(pdop) : "") + "," + ((sats >= 0 && System.currentTimeMillis() - satsT < 5000) ? String.valueOf(sats) : "");
        peakA = 0;
        return res;
    }

    @JavascriptInterface public synchronized void startMagCal() { calRec = true; calN = 0; }

    @JavascriptInterface public synchronized String stopMagCal() {
        calRec = false;
        if (calN < 50 || !hasMR) return "insuffisant";
        float[] half = new float[3];
        float avg = 0;
        for (int i = 0; i < 3; i++) {
            bias[i] = (mx[i] + mn[i]) / 2f;
            half[i] = (mx[i] - mn[i]) / 2f;
            avg += half[i];
        }
        avg /= 3f;
        if (avg < 10f) return "insuffisant";           // champ mesuré trop faible : pas assez de mouvement
        for (int i = 0; i < 3; i++) if (half[i] < 0.6f * avg) return "insuffisant"; // un axe n'a pas été balayé
        for (int i = 0; i < 3; i++) scale[i] = half[i] > 1f ? avg / half[i] : 1f;
        calibrated = true;
        fus.reset();
        SharedPreferences.Editor ed = sp.edit().putBoolean("cal", true);
        for (int i = 0; i < 3; i++) ed.putFloat("b" + i, bias[i]).putFloat("s" + i, scale[i]);
        ed.apply();
        return "ok";
    }

    @JavascriptInterface public synchronized boolean hasMagCal() { return calibrated; }

    @JavascriptInterface public synchronized void resetMagCal() {
        calibrated = false;
        fus.reset();
        sp.edit().clear().apply();
    }

    // =====================================================================================
    //  FUSION : AHRS (gyroscope + accéléromètre + compas) et vario baro-inertiel
    //  Java pur (aucune dépendance Android) : testable à part.
    // =====================================================================================
    static final class Fusion {
        static final double G = 9.80665;
        static final double KPG = 3.0, KPM = 2.0, KI = 0.1;
        static final double GATE = 0.06;
        boolean ready;
        double qw, qx, qy, qz;                                  // appareil -> terre (E, N, Haut)
        final double[] ib = new double[3];
        double refN;
        boolean flatMode;
        double lastHdg = -1;
        int quality;
        double age;
        double wLp = -1;                                        // |vitesse angulaire| filtrée (°/s) : -1 = pas de gyroscope
        boolean bOk; double bh, bv; double p00 = 4, p01 = 0, p11 = 4;

        void reset() { ready = false; bOk = false; refN = 0; age = 0; for (int i = 0; i < 3; i++) ib[i] = 0; lastHdg = -1; quality = 0; }

        void init(float[] a, float[] f) {
            double ax = a[0], ay = a[1], az = a[2], n = Math.sqrt(ax * ax + ay * ay + az * az);
            if (n < 1) return;
            double ux = ax / n, uy = ay / n, uz = az / n;
            double ex = f[1] * uz - f[2] * uy, ey = f[2] * ux - f[0] * uz, ez = f[0] * uy - f[1] * ux;
            double en = Math.sqrt(ex * ex + ey * ey + ez * ez);
            if (en < 1e-3) return;
            ex /= en; ey /= en; ez /= en;
            double nx = uy * ez - uz * ey, ny = uz * ex - ux * ez, nz = ux * ey - uy * ex;
            double r00 = ex, r01 = ey, r02 = ez, r10 = nx, r11 = ny, r12 = nz, r20 = ux, r21 = uy, r22 = uz;
            double t = r00 + r11 + r22;
            if (t > 0) { double s = Math.sqrt(t + 1) * 2; qw = s / 4; qx = (r21 - r12) / s; qy = (r02 - r20) / s; qz = (r10 - r01) / s; }
            else if (r00 > r11 && r00 > r22) { double s = Math.sqrt(1 + r00 - r11 - r22) * 2; qw = (r21 - r12) / s; qx = s / 4; qy = (r01 + r10) / s; qz = (r02 + r20) / s; }
            else if (r11 > r22) { double s = Math.sqrt(1 + r11 - r00 - r22) * 2; qw = (r02 - r20) / s; qx = (r01 + r10) / s; qy = s / 4; qz = (r12 + r21) / s; }
            else { double s = Math.sqrt(1 + r22 - r00 - r11) * 2; qw = (r10 - r01) / s; qx = (r02 + r20) / s; qy = (r12 + r21) / s; qz = s / 4; }
            norm();
            refN = Math.sqrt((double) f[0] * f[0] + (double) f[1] * f[1] + (double) f[2] * f[2]);
            ready = true; age = 0; quality = 2;
        }

        void norm() { double n = Math.sqrt(qw * qw + qx * qx + qy * qy + qz * qz); qw /= n; qx /= n; qy /= n; qz /= n; }

        void rot(double x, double y, double z, double[] o) {
            double w = qw, a = qx, b = qy, c = qz;
            o[0] = (1 - 2 * (b * b + c * c)) * x + 2 * (a * b - w * c) * y + 2 * (a * c + w * b) * z;
            o[1] = 2 * (a * b + w * c) * x + (1 - 2 * (a * a + c * c)) * y + 2 * (b * c - w * a) * z;
            o[2] = 2 * (a * c - w * b) * x + 2 * (b * c + w * a) * y + (1 - 2 * (a * a + b * b)) * z;
        }
        void rotInv(double x, double y, double z, double[] o) {
            double w = qw, a = -qx, b = -qy, c = -qz;
            o[0] = (1 - 2 * (b * b + c * c)) * x + 2 * (a * b - w * c) * y + 2 * (a * c + w * b) * z;
            o[1] = 2 * (a * b + w * c) * x + (1 - 2 * (a * a + c * c)) * y + 2 * (b * c - w * a) * z;
            o[2] = 2 * (a * c - w * b) * x + 2 * (b * c + w * a) * y + (1 - 2 * (a * a + b * b)) * z;
        }

        private final double[] t1 = new double[3], t2 = new double[3];

        void step(double wx, double wy, double wz, float[] a, float[] f, boolean hasMag, double dt, boolean noGyro) {
            age += dt;
            if (!noGyro) { double wd = Math.toDegrees(Math.sqrt(wx * wx + wy * wy + wz * wz)); wLp = wLp < 0 ? wd : wLp + 0.05 * (wd - wLp); }
            double boost = age < 6 ? 2 : 1;
            double ex = 0, ey = 0, ez = 0, kg, km;
            double an = Math.sqrt((double) a[0] * a[0] + (double) a[1] * a[1] + (double) a[2] * a[2]);
            rotInv(0, 0, 1, t1);
            if (an > 1) {
                double ux = a[0] / an, uy = a[1] / an, uz = a[2] / an;
                double dev = Math.abs(an - G) / G;
                kg = KPG * boost * Math.max(0, 1 - dev / GATE);
                if (noGyro) kg = Math.max(kg, 0.5);
                ex += kg * (uy * t1[2] - uz * t1[1]); ey += kg * (uz * t1[0] - ux * t1[2]); ez += kg * (ux * t1[1] - uy * t1[0]);
            }
            double fn = Math.sqrt((double) f[0] * f[0] + (double) f[1] * f[1] + (double) f[2] * f[2]);
            boolean magOk = false;
            if (hasMag && fn > 5 && refN > 5) {
                double dev = Math.abs(fn - refN) / refN;
                if (dev < 0.12) {
                    magOk = true; km = KPM * boost * (1 - dev / 0.12); refN += 0.002 * (fn - refN);
                    rot(f[0], f[1], f[2], t2);
                    double h = Math.hypot(t2[0], t2[1]);
                    rotInv(0, h, t2[2], t2);
                    double wn = Math.sqrt(t2[0] * t2[0] + t2[1] * t2[1] + t2[2] * t2[2]);
                    if (wn > 1e-6) {
                        double mx = f[0] / fn, my = f[1] / fn, mz = f[2] / fn, px = t2[0] / wn, py = t2[1] / wn, pz = t2[2] / wn;
                        ex += km * (my * pz - mz * py); ey += km * (mz * px - mx * pz); ez += km * (mx * py - my * px);
                    }
                }
            }
            quality = magOk ? 2 : 1;
            if (!noGyro) {
                ib[0] = Math.max(-0.05, Math.min(0.05, ib[0] + KI * ex * dt));
                ib[1] = Math.max(-0.05, Math.min(0.05, ib[1] + KI * ey * dt));
                ib[2] = Math.max(-0.05, Math.min(0.05, ib[2] + KI * ez * dt));
            }
            double wxc = wx + ib[0] + ex, wyc = wy + ib[1] + ey, wzc = wz + ib[2] + ez;
            double dw = -qx * wxc - qy * wyc - qz * wzc;
            double dx = qw * wxc + qy * wzc - qz * wyc;
            double dy = qw * wyc - qx * wzc + qz * wxc;
            double dz = qw * wzc + qx * wyc - qy * wxc;
            qw += 0.5 * dw * dt; qx += 0.5 * dx * dt; qy += 0.5 * dy * dt; qz += 0.5 * dz * dt;
            norm();
        }

        final double[] out = new double[4];
        double[] output() {
            rotInv(0, 0, 1, t1);
            double uz = Math.abs(t1[2]);
            if (flatMode) { if (uz < 0.65) flatMode = false; } else { if (uz > 0.85) flatMode = true; }
            double[] fe = new double[3];
            if (flatMode) rot(0, 1, 0, fe); else rot(0, 0, -1, fe);
            double h = Math.hypot(fe[0], fe[1]);
            if (h > 0.25) lastHdg = (Math.toDegrees(Math.atan2(fe[0], fe[1])) + 360) % 360;
            double[] re = new double[3];
            rot(1, 0, 0, re);
            out[0] = lastHdg;
            out[1] = Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, fe[2]))));
            out[2] = -Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, re[2]))));
            out[3] = quality;
            return out;
        }

        void predictVertical(float[] a, double dt) {
            if (!bOk || !ready) return;
            rot(a[0], a[1], a[2], t2);
            double acc = Math.max(-15, Math.min(15, t2[2] - G));
            bh += bv * dt + 0.5 * acc * dt * dt; bv += acc * dt;
            double qa = 0.64, g0 = 0.5 * dt * dt, g1 = dt;
            double n00 = p00 + dt * (p01 + p01) + dt * dt * p11 + g0 * g0 * qa;
            double n01 = p01 + dt * p11 + g0 * g1 * qa;
            double n11 = p11 + g1 * g1 * qa;
            p00 = n00; p01 = n01; p11 = n11;
        }
        void updateBaro(float hpa) {
            if (hpa < 300 || hpa > 1100) return;
            double z = 44330.0 * (1 - Math.pow(hpa / 1013.25, 0.190295));
            if (!bOk) { bh = z; bv = 0; p00 = 4; p01 = 0; p11 = 4; bOk = true; return; }
            double r = 0.25, s = p00 + r, k0 = p00 / s, k1 = p01 / s, y = z - bh;
            bh += k0 * y; bv += k1 * y;
            double n00 = p00 - k0 * p00, n01 = p01 - k0 * p01, n11 = p11 - k1 * p01;
            p00 = n00; p01 = n01; p11 = n11;
        }
        boolean baroOk() { return bOk && ready; }
        double vz() { return bv; }
    }
}
