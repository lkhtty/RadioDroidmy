package net.programmierecke.radiodroid2;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import net.programmierecke.radiodroid2.station.DataRadioStation;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class M3uImporter {

    public interface OnOnlineImportListener {
        void onSuccess(int slotNumber, int count);
        void onError(String message);
    }

    // 昨天的本地导入代码原封不动保留，100% 兼容
    public static int importM3u(Context context, Uri fileUri) {
        int count = 0;
        try {
            InputStream inputStream = context.getContentResolver().openInputStream(fileUri);
            if (inputStream == null) return 0;

            BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
            String line;
            String currentTitle = "Unknown Station";

            RadioDroidApp app = (RadioDroidApp) context.getApplicationContext();
            FavouriteManager favouriteManager = app.getFavouriteManager();

            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                if (line.startsWith("#EXTINF:")) {
                    int commaIdx = line.indexOf(',');
                    if (commaIdx != -1 && commaIdx + 1 < line.length()) {
                        currentTitle = line.substring(commaIdx + 1).trim();
                    }
                } else if (!line.startsWith("#")) {
                    String streamUrl = line;

                    DataRadioStation station = new DataRadioStation();
                    station.StationUuid = UUID.randomUUID().toString();
                    station.Name = currentTitle;
                    station.StreamUrl = streamUrl;

                    favouriteManager.add(station);
                    count++;
                    currentTitle = "Unknown Station";
                }
            }
            reader.close();
            inputStream.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return count;
    }

    /**
     * 新增：在线 M3U 自动下载导入
     * 1. 自动轮替槽位（源1、源2、源3），第 4 个覆盖第 1 个 (FIFO)
     * 2. 覆盖时通过 removeOnlineSlot 仅删当前槽位旧电台，本地导入的 M3U 绝不受影响
     * 3. 名称自动冠以 [源1]、[源2]、[源3] 前缀，电台名字互不冲突
     */
    public static void importOnlineM3u(final Context context, final String urlString, final OnOnlineImportListener listener) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection conn = null;
                try {
                    // 1. 获取当前 FIFO 槽位 (0, 1, 2)
                    SharedPreferences sp = context.getSharedPreferences("online_m3u_cfg", Context.MODE_PRIVATE);
                    int pointer = sp.getInt("pointer", 0);
                    final int targetSlot = pointer % 3;
                    final int slotNumber = targetSlot + 1; // 1, 2, 3
                    final String namePrefix = "[源" + slotNumber + "] ";
                    final String uuidPrefix = "online_" + targetSlot + "_";

                    // 2. 发起网络请求并下载 M3U
                    URL url = new URL(urlString);
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(15000);
                    conn.setInstanceFollowRedirects(true);
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0 RadioDroid/Car");

                    if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
                        notifyError(listener, "下载失败，HTTP 响应码: " + conn.getResponseCode());
                        return;
                    }

                    BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    String line;
                    String currentTitle = "Unknown Station";
                    List<DataRadioStation> newStations = new ArrayList<>();

                    while ((line = reader.readLine()) != null) {
                        line = line.trim();
                        if (line.isEmpty()) continue;

                        if (line.startsWith("#EXTINF:")) {
                            int commaIdx = line.indexOf(',');
                            if (commaIdx != -1 && commaIdx + 1 < line.length()) {
                                currentTitle = line.substring(commaIdx + 1).trim();
                            }
                        } else if (!line.startsWith("#")) {
                            String streamUrl = line;

                            DataRadioStation station = new DataRadioStation();
                            station.StationUuid = uuidPrefix + UUID.randomUUID().toString();
                            station.Name = namePrefix + currentTitle;
                            station.StreamUrl = streamUrl;

                            newStations.add(station);
                            currentTitle = "Unknown Station";
                        }
                    }
                    reader.close();

                    if (newStations.isEmpty()) {
                        notifyError(listener, "未在 M3U 中解析到有效的流媒体播放地址");
                        return;
                    }

                    // 3. 注入收藏夹：先清理该槽位旧电台，再插入新电台
                    RadioDroidApp app = (RadioDroidApp) context.getApplicationContext();
                    FavouriteManager favouriteManager = app.getFavouriteManager();

                    // 精准删除当前槽位的旧电台（本地 M3U 完全不受影响）
                    favouriteManager.removeOnlineSlot(targetSlot);

                    // 写入新电台
                    for (DataRadioStation st : newStations) {
                        favouriteManager.add(st);
                    }

                    // 4. 更新指针
                    sp.edit().putInt("pointer", pointer + 1).apply();

                    final int totalCount = newStations.size();
                    new Handler(Looper.getMainLooper()).post(new Runnable() {
                        @Override
                        public void run() {
                            if (listener != null) {
                                listener.onSuccess(slotNumber, totalCount);
                            }
                        }
                    });

                } catch (final Exception e) {
                    notifyError(listener, "导入异常: " + e.getMessage());
                } finally {
                    if (conn != null) {
                        conn.disconnect();
                    }
                }
            }
        }).start();
    }

    private static void notifyError(final OnOnlineImportListener listener, final String msg) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                if (listener != null) {
                    listener.onError(msg);
                }
            }
        });
    }
}
