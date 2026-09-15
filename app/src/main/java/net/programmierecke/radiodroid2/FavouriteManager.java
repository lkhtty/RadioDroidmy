package net.programmierecke.radiodroid2;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import net.programmierecke.radiodroid2.station.DataRadioStation;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.UUID;

import static java.lang.Math.min;

public class FavouriteManager extends StationSaveManager {
    @Override
    protected String getSaveId() {
        return "favourites";
    }

    public FavouriteManager(Context ctx) {
        super(ctx);

        setStationStatusListener((station, favourite) -> {
            Intent local = new Intent();
            local.setAction(DataRadioStation.RADIO_STATION_LOCAL_INFO_CHAGED);
            local.putExtra(DataRadioStation.RADIO_STATION_UUID, station.StationUuid);
            LocalBroadcastManager.getInstance(ctx).sendBroadcast(local);
        });
    }

    @Override
    public void add(DataRadioStation station) {
        if (!has(station.StationUuid)) {
            super.add(station);
        }
    }

    @Override
    public void restore(DataRadioStation station, int pos) {
        if (!has(station.StationUuid)) {
            super.restore(station, pos);
        }
    }

    @Override
    void Load() {
        super.Load();
        updateShortcuts();
    }

    @Override
    void Save() {
        super.Save();
        updateShortcuts();
    }

    public void updateShortcuts() {
        if (Build.VERSION.SDK_INT >= 25 && !BuildConfig.IS_TESTING.get()) {
            int number = min(listStations.size(), ActivityMain.MAX_DYNAMIC_LAUNCHER_SHORTCUTS);
            SetDynamicAppLauncherShortcuts setDynamicAppLauncherShortcuts = new SetDynamicAppLauncherShortcuts(number);
            for (int i = 0; i < number; i++) {
                listStations.get(i).prepareShortcut(context, setDynamicAppLauncherShortcuts);
            }
        }
    }

    @TargetApi(25)
    class SetDynamicAppLauncherShortcuts implements DataRadioStation.ShortcutReadyListener {
        ArrayList<ShortcutInfo> shortcuts;
        int expectedNumber;

        SetDynamicAppLauncherShortcuts(int expectedNumber) {
            this.expectedNumber = expectedNumber;
            shortcuts = new ArrayList<ShortcutInfo>(expectedNumber);
        }

        @Override
        public void onShortcutReadyListener(ShortcutInfo shortcut) {
            shortcuts.add(shortcut);
            if (shortcuts.size() >= expectedNumber) {
                ShortcutManager shortcutManager = context.getApplicationContext().getSystemService(ShortcutManager.class);
                shortcutManager.removeAllDynamicShortcuts();
                shortcutManager.setDynamicShortcuts(shortcuts);
            }
        }
    }

    /**
     * 获取下一个在线槽位索引（固定返回 1，即对应源1）
     */
    public int getNextOnlineSlot() {
        return 1;
    }

    /**
     * 清理指定在线槽位的旧电台（例如覆盖源1时只删[源1]，绝对不触动源2、源3和本地收藏）
     */
    public void removeOnlineSlot(int slotIndex) {
        String uuidPrefix = "online_" + slotIndex + "_";
        String namePrefix = "[源" + slotIndex + "]";
        if (listStations != null) {
            for (int i = listStations.size() - 1; i >= 0; i--) {
                DataRadioStation station = listStations.get(i);
                if (station != null) {
                    if ((station.StationUuid != null && station.StationUuid.startsWith(uuidPrefix))
                            || (station.Name != null && station.Name.startsWith(namePrefix))) {
                        listStations.remove(i);
                    }
                }
            }
            Save();
        }
    }

    /**
     * 【全盘拦截核心】：直接重写父类原版的旧导入方法，彻底接管 ActivityMain 的调用
     */
    @Override
    public void LoadM3USimple(InputStreamReader reader) {
        try {
            int slotNumber = getNextOnlineSlot();
            removeOnlineSlot(slotNumber);
            String prefix = "[源" + slotNumber + "]";

            BufferedReader br = new BufferedReader(reader);
            String line;
            String currentName = null;
            int count = 0;

            while ((line = br.readLine()) != null) {
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
                        st.StationUuid = "online_" + slotNumber + "_" + UUID.randomUUID().toString();
                        st.Name = prefix + " " + ((name != null && !name.isEmpty()) ? name : "电台");
                        st.StreamUrl = url;
                        st.Hls = url.contains(".m3u8");

                        add(st);
                        count++;
                        currentName = null;
                    }
                }
            }

            Save(); // 强制持久化保存

            // 广播通知收藏夹界面立即刷新
            Intent local = new Intent(DataRadioStation.RADIO_STATION_LOCAL_INFO_CHAGED);
            LocalBroadcastManager.getInstance(context).sendBroadcast(local);

            final int finalCount = count;
            new Handler(Looper.getMainLooper()).post(() -> {
                if (finalCount > 0) {
                    Toast.makeText(context, "成功导入 " + finalCount + " 个电台", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(context, "未在文件中识别到有效电台链接", Toast.LENGTH_SHORT).show();
                }
            });

        } catch (Exception e) {
            final String err = e.getMessage();
            new Handler(Looper.getMainLooper()).post(() -> 
                Toast.makeText(context, "导入异常: " + err, Toast.LENGTH_SHORT).show()
            );
        }
    }
}
