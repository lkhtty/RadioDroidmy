package net.programmierecke.radiodroid2;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import net.programmierecke.radiodroid2.station.DataRadioStation;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

public class M3uImporter {
    private static final String TAG = "M3uImporter";

    public interface OnOnlineImportListener {
        void onSuccess(int slotNumber, int count);
        void onError(String message);
    }

    // 信任所有证书，解决老旧车机系统访问 HTTPS 报错的问题
    private static void trustAllCertificates() {
        try {
            TrustManager[] trustAllCerts = new TrustManager[]{
                new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                    public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                    public void checkServerTrusted(X509Certificate[] certs, String authType) {}
                }
            };
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, trustAllCerts, new java.security.SecureRandom());
            HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
            HttpsURLConnection.setDefaultHostnameVerifier((hostname, session) -> true);
        } catch (Exception ignored) {}
    }

    public static int importLocalFileStream(Context context, InputStream is) {
        try {
            if (is == null) return 0;
            BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            List<DataRadioStation> stations = parseM3uStream(reader, null);
            RadioDroidApp app = (RadioDroidApp) context.getApplicationContext();
            FavouriteManager fm = app.getFavouriteManager();
            for (DataRadioStation st : stations) {
                fm.add(st);
            }
            return stations.size();
        } catch (Exception e) {
            Log.e(TAG, "importLocalFileStream error", e);
            return 0;
        }
    }

    // 补齐此方法，解决 ActivityMain.java 编译时找不到符号的报错
    public static int importM3u(Context context, Uri uri) {
        try {
            InputStream is = context.getContentResolver().openInputStream(uri);
            if (is == null) return 0;
            return importLocalFileStream(context, is);
        } catch (Exception e) {
            Log.e(TAG, "importM3u error", e);
            return 0;
        }
    }

    public static void importOnlineM3u(Context context, String urlString, OnOnlineImportListener listener) {
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                trustAllCertificates();
                URL url = new URL(urlString);
                conn = (HttpURLConnection) url.openConnection();
                conn.setInstanceFollowRedirects(true);
                conn.setConnectTimeout(20000);
                conn.setReadTimeout(20000);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0");
                conn.connect();

                int code = conn.getResponseCode();
                // 支持 301/302 自动重定向
                if (code == HttpURLConnection.HTTP_MOVED_PERM || code == HttpURLConnection.HTTP_MOVED_TEMP) {
                    String newUrl = conn.getHeaderField("Location");
                    conn.disconnect();
                    url = new URL(newUrl);
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(20000);
                    conn.setReadTimeout(20000);
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0");
                    conn.connect();
                    code = conn.getResponseCode();
                }

                if (code < 200 || code >= 300) {
                    postError(listener, "下载失败，HTTP状态码: " + code);
                    return;
                }

                InputStream is = conn.getInputStream();
                BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));

                RadioDroidApp app = (RadioDroidApp) context.getApplicationContext();
                FavouriteManager fm = app.getFavouriteManager();

                int slotNumber = fm.getNextOnlineSlot();
                fm.removeOnlineSlot(slotNumber);

                String prefix = "[源" + slotNumber + "]";
                List<DataRadioStation> stations = parseM3uStream(reader, prefix);

                if (stations.isEmpty()) {
                    postError(listener, "未在链接中解析到有效的 M3U 频道");
                    return;
                }

                for (DataRadioStation st : stations) {
                    fm.add(st);
                }

                new Handler(Looper.getMainLooper()).post(() -> {
                    if (listener != null) {
                        listener.onSuccess(slotNumber, stations.size());
                    }
                });

            } catch (Exception e) {
                Log.e(TAG, "importOnlineM3u error", e);
                postError(listener, "导入失败: " + e.getMessage());
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }).start();
    }

    public static List<DataRadioStation> parseM3uStream(BufferedReader reader, String prefix) throws Exception {
        List<DataRadioStation> list = new ArrayList<>();
        String line;
        String currentTitle = "电台频道";
        while ((line = reader.readLine()) != null) {
            line = line.trim();
            if (line.startsWith("\uFEFF")) {
                line = line.substring(1).trim();
            }
            if (line.isEmpty()) continue;
            if (line.startsWith("#EXTINF:")) {
                int commaIdx = line.indexOf(',');
                if (commaIdx != -1 && commaIdx < line.length() - 1) {
                    currentTitle = line.substring(commaIdx + 1).trim();
                }
            } else if (!line.startsWith("#")) {
                DataRadioStation station = new DataRadioStation();
                station.StationUuid = UUID.randomUUID().toString();
                station.Name = (prefix != null ? prefix + " " : "") + currentTitle;
                station.StreamUrl = line;
                list.add(station);
                currentTitle = "电台频道";
            }
        }
        return list;
    }

    private static void postError(OnOnlineImportListener listener, String message) {
        new Handler(Looper.getMainLooper()).post(() -> {
            if (listener != null) {
                listener.onError(message);
            }
        });
    }
}
