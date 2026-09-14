package net.programmierecke.radiodroid2;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import net.programmierecke.radiodroid2.station.DataRadioStation;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

@SuppressLint({"TrustAllX509TrustManager", "BadHostnameVerifier"})
public class M3uImporter {
    private static final String TAG = "M3uImporter";

    public interface OnOnlineImportListener {
        void onSuccess(int slotNumber, int count);
        void onError(String message);
    }

    // 适配 Android 5 车机：信任所有证书并强制开启 TLSv1.2
    @SuppressLint({"TrustAllX509TrustManager", "BadHostnameVerifier"})
    private static void trustAllCertificates() {
        try {
            TrustManager[] trustAllCerts = new TrustManager[]{
                new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                    public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                    public void checkServerTrusted(X509Certificate[] certs, String authType) {}
                }
            };
            SSLContext sc;
            try {
                sc = SSLContext.getInstance("TLSv1.2");
            } catch (Exception e) {
                sc = SSLContext.getInstance("TLS");
            }
            sc.init(null, trustAllCerts, new java.security.SecureRandom());
            HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
            HttpsURLConnection.setDefaultHostnameVerifier((hostname, session) -> true);
        } catch (Exception ignored) {}
    }

    /**
     * 本地文件流导入
     */
    public static int importLocalFileStream(Context context, InputStream is) {
        try {
            if (is == null) return 0;
            BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));

            RadioDroidApp app = (RadioDroidApp) context.getApplicationContext();
            FavouriteManager fm = app.getFavouriteManager();

            int slotNumber = fm.getNextOnlineSlot();
            fm.removeOnlineSlot(slotNumber);

            String prefix = "[源" + slotNumber + "]";
            List<DataRadioStation> stations = parseM3uStream(reader, prefix, slotNumber);

            for (DataRadioStation st : stations) {
                fm.add(st);
            }
            fm.Save(); // 强制持久化保存到磁盘

            // 广播通知收藏夹界面立即刷新
            Intent local = new Intent(DataRadioStation.RADIO_STATION_LOCAL_INFO_CHAGED);
            LocalBroadcastManager.getInstance(context).sendBroadcast(local);

            return stations.size();
        } catch (Exception e) {
            Log.e(TAG, "importLocalFileStream error", e);
            return 0;
        }
    }

    /**
     * 本地 Uri 导入
     */
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

    /**
     * 在线网络 URL 导入（支持 CDN 301/302 重定向跟随）
     */
    public static void importOnlineM3u(Context context, String urlString, OnOnlineImportListener listener) {
        new Thread(() -> {
            Handler mainHandler = new Handler(Looper.getMainLooper());
            try {
                trustAllCertificates();

                URL url = new URL(urlString);
                HttpURLConnection conn = null;
                int redirectCount = 0;

                while (redirectCount < 5) {
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(15000);
                    conn.setInstanceFollowRedirects(true);
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");

                    int responseCode = conn.getResponseCode();
                    if (responseCode == HttpURLConnection.HTTP_MOVED_PERM 
                            || responseCode == HttpURLConnection.HTTP_MOVED_TEMP 
                            || responseCode == 307 || responseCode == 308) {
                        String newUrl = conn.getHeaderField("Location");
                        if (newUrl != null && !newUrl.isEmpty()) {
                            url = new URL(newUrl);
                            redirectCount++;
                            continue;
                        }
                    }
                    if (responseCode != 200) {
                        postError(mainHandler, listener, "HTTP 错误: " + responseCode);
                        return;
                    }
                    break;
                }

                if (conn == null) {
                    postError(mainHandler, listener, "网络连接失败");
                    return;
                }

                InputStream is = conn.getInputStream();
                BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));

                RadioDroidApp app = (RadioDroidApp) context.getApplicationContext();
                FavouriteManager fm = app.getFavouriteManager();

                int slotNumber = fm.getNextOnlineSlot();
                fm.removeOnlineSlot(slotNumber);

                String prefix = "[源" + slotNumber + "]";
                List<DataRadioStation> stations = parseM3uStream(reader, prefix, slotNumber);

                if (stations.isEmpty()) {
                    postError(mainHandler, listener, "未在 M3U 中解析到有效频道");
                    return;
                }

                for (DataRadioStation st : stations) {
                    fm.add(st);
                }
                fm.Save(); // 强制写入本地存储

                // 发送全局广播立即更新列表
                Intent local = new Intent(DataRadioStation.RADIO_STATION_LOCAL_INFO_CHAGED);
                LocalBroadcastManager.getInstance(context).sendBroadcast(local);

                mainHandler.post(() -> {
                    if (listener != null) {
                        listener.onSuccess(slotNumber, stations.size());
                    }
                });

            } catch (Exception e) {
                Log.e(TAG, "importOnlineM3u error", e);
                postError(mainHandler, listener, "网络或解析异常: " + e.getLocalizedMessage());
            }
        }).start();
    }

    private static void postError(Handler handler, OnOnlineImportListener listener, String message) {
        handler.post(() -> {
            if (listener != null) {
                listener.onError(message);
            }
        });
    }

    /**
     * 解析 M3U 数据流（只使用项目中真实存在的字段）
     */
    private static List<DataRadioStation> parseM3uStream(BufferedReader reader, String prefix, int slotNumber) throws Exception {
        List<DataRadioStation> list = new ArrayList<>();
        String line;
        String currentName = null;

        while ((line = reader.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty()) continue;

            // 过滤 UTF-8 BOM 头
            if (line.startsWith("\uFEFF")) {
                line = line.substring(1).trim();
            }

            if (line.startsWith("#EXTINF:") || line.startsWith("#EXTINF")) {
                int commaIdx = line.lastIndexOf(',');
                if (commaIdx != -1 && commaIdx < line.length() - 1) {
                    currentName = line.substring(commaIdx + 1).trim();
                }
            } else if (!line.startsWith("#")) {
                if (line.startsWith("http://") || line.startsWith("https://") || line.startsWith("rtmp://") || line.startsWith("rtsp://")) {
                    DataRadioStation st = new DataRadioStation();
                    
                    st.StationUuid = "online_" + slotNumber + "_" + UUID.randomUUID().toString();
                    st.Name = prefix + " " + ((currentName != null && !currentName.isEmpty()) ? currentName : "电台");
                    st.StreamUrl = line;
                    st.Hls = line.contains(".m3u8"); // boolean 类型赋值

                    list.add(st);
                    currentName = null;
                }
            }
        }
        return list;
    }
}
