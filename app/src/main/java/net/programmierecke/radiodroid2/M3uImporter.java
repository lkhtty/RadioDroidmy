package net.programmierecke.radiodroid2;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import net.programmierecke.radiodroid2.station.DataRadioStation;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
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

    private static byte[] readStreamToBytes(InputStream is) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int len;
        while ((len = is.read(buffer)) != -1) {
            baos.write(buffer, 0, len);
        }
        return baos.toByteArray();
    }

    private static String decodeBytesToString(byte[] data) {
        if (data == null || data.length == 0) return "";

        try {
            String s = new String(data, "UTF-8");
            if (s.contains("#EXTINF") || s.contains("http://") || s.contains("https://")) {
                if (s.startsWith("\uFEFF")) {
                    s = s.substring(1);
                }
                return s;
            }
        } catch (Exception ignored) {}

        try {
            String s = new String(data, "GB18030");
            if (s.contains("#EXTINF") || s.contains("http://") || s.contains("https://")) {
                return s;
            }
        } catch (Exception ignored) {}

        try {
            String s = new String(data, "UTF-16");
            if (s.contains("#EXTINF") || s.contains("http://") || s.contains("https://")) {
                return s;
            }
        } catch (Exception ignored) {}

        return new String(data);
    }

    // 本地文件导入：不占用在线 1/2/3 槽位，不加 [源X] 前缀，永久保存在收藏夹
    public static int importLocalFileStream(Context context, InputStream is) {
        try {
            if (is == null) {
                showToast(context, "打开文件失败：文件流为空");
                return 0;
            }

            byte[] data = readStreamToBytes(is);
            if (data.length == 0) {
                showToast(context, "文件内容为空（0 字节），请检查路径或存储权限");
                return 0;
            }

            String content = decodeBytesToString(data);

            RadioDroidApp app = (RadioDroidApp) context.getApplicationContext();
            FavouriteManager fm = app.getFavouriteManager();

            List<DataRadioStation> stations = parseM3uText(content, "", 0);

            if (stations.isEmpty()) {
                showToast(context, "读取到 " + data.length + " 字节，但未识别到有效频道");
                return 0;
            }

            for (DataRadioStation st : stations) {
                fm.add(st);
            }
            fm.Save();

            Intent local = new Intent(DataRadioStation.RADIO_STATION_LOCAL_INFO_CHAGED);
            LocalBroadcastManager.getInstance(context).sendBroadcast(local);

            showToast(context, "成功导入 " + stations.size() + " 个本地电台");
            return stations.size();
        } catch (Exception e) {
            Log.e(TAG, "importLocalFileStream error", e);
            showToast(context, "导入异常: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            return 0;
        }
    }

    public static int importM3u(Context context, Uri uri) {
        try {
            if (uri == null) return 0;
            InputStream is = null;

            if ("file".equalsIgnoreCase(uri.getScheme()) 
                    || (uri.getPath() != null && (uri.getPath().startsWith("/storage") || uri.getPath().startsWith("/mnt") || uri.getPath().startsWith("/sdcard")))) {
                try {
                    File file = new File(uri.getPath());
                    if (file.exists() && file.canRead()) {
                        is = new FileInputStream(file);
                    }
                } catch (Exception ignored) {}
            }

            if (is == null) {
                try {
                    is = context.getContentResolver().openInputStream(uri);
                } catch (Exception ignored) {}
            }

            if (is == null && uri.getPath() != null) {
                try {
                    File file = new File(uri.getPath());
                    if (file.exists() && file.canRead()) {
                        is = new FileInputStream(file);
                    }
                } catch (Exception ignored) {}
            }

            if (is == null) {
                showToast(context, "无法读取文件，请检查文件路径或存储权限");
                return 0;
            }

            return importLocalFileStream(context, is);
        } catch (Exception e) {
            Log.e(TAG, "importM3u error", e);
            showToast(context, "读取出错: " + e.getMessage());
            return 0;
        }
    }

    // 在线 M3U 导入：自动按 [源1]、[源2]、[源3] 循环覆盖
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
                byte[] data = readStreamToBytes(is);
                if (data.length == 0) {
                    postError(mainHandler, listener, "网络下载数据为空（0 字节）");
                    return;
                }

                String content = decodeBytesToString(data);

                RadioDroidApp app = (RadioDroidApp) context.getApplicationContext();
                FavouriteManager fm = app.getFavouriteManager();

                int slotNumber = fm.getNextOnlineSlot();
                fm.removeOnlineSlot(slotNumber);

                String prefix = "[源" + slotNumber + "]";
                List<DataRadioStation> stations = parseM3uText(content, prefix, slotNumber);

                if (stations.isEmpty()) {
                    postError(mainHandler, listener, "下载了 " + data.length + " 字节，但未识别到有效频道");
                    return;
                }

                for (DataRadioStation st : stations) {
                    fm.add(st);
                }
                fm.Save();

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

    private static void showToast(Context context, String message) {
        new Handler(Looper.getMainLooper()).post(() -> 
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        );
    }

    private static List<DataRadioStation> parseM3uText(String content, String prefix, int slotNumber) {
        List<DataRadioStation> list = new ArrayList<>();
        if (content == null || content.isEmpty()) return list;

        String[] lines = content.split("\\r?\\n");
        String currentName = null;

        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.isEmpty()) continue;

            if (line.startsWith("#EXTINF")) {
                int commaIdx = line.lastIndexOf(',');
                if (commaIdx != -1 && commaIdx < line.length() - 1) {
                    currentName = line.substring(commaIdx + 1).trim();
                }
            } else if (!line.startsWith("#")) {
                String name = currentName;
                String url = "";

                if (line.startsWith("http://") || line.startsWith("https://") || line.startsWith("rtmp://") || line.startsWith("rtsp://")) {
                    url = line;
                } else if (line.contains(",")) {
                    int commaIdx = line.indexOf(',');
                    name = line.substring(0, commaIdx).trim();
                    url = line.substring(commaIdx + 1).trim();
                }

                if (!url.isEmpty() && (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("rtmp://") || url.startsWith("rtsp://"))) {
                    DataRadioStation st = new DataRadioStation();

                    if (slotNumber > 0) {
                        st.StationUuid = "online_" + slotNumber + "_" + UUID.randomUUID().toString();
                    } else {
                        st.StationUuid = "local_" + UUID.randomUUID().toString();
                    }

                    String displayName = (name != null && !name.isEmpty()) ? name : "电台";
                    if (prefix != null && !prefix.isEmpty()) {
                        st.Name = prefix + " " + displayName;
                    } else {
                        st.Name = displayName;
                    }

                    st.StreamUrl = url;
                    st.Hls = url.contains(".m3u8");

                    // 必须加上这些初始值，绝不能留 null：
                    st.HomePageUrl = "";
                    st.IconUrl = "";
                    st.Country = "";
                    st.CountryCode = "";
                    st.State = "";
                    st.Language = "";
                    st.Tags = "";
                    st.Codec = st.Hls ? "HLS" : "MP3";
                    st.Bitrate = 128;
                    st.Votes = 0;
                    st.NegativeVotes = 0;
                    st.LastCheckOk = 1;
                    st.ClickCount = 0;
                    st.ClickTrend = 0;
                    st.HasExtendedInfo = false;

                    list.add(st);
                    currentName = null;
                }
            }
        }
        return list;
    }
}
