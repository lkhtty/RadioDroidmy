package net.programmierecke.radiodroid2;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.os.Build;
import android.widget.Toast;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import net.programmierecke.radiodroid2.station.DataRadioStation;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
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

    public int getNextOnlineSlot() {
        return 1;
    }

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

    // =========================================================================
    // 核心重写：直接重写官方父类的 LoadM3U 方法，批量添加，单次存盘，彻底消除闪退
    // =========================================================================
    @Override
    public void LoadM3U(String path, String name) {
        File file = new File(path, name);
        if (!file.exists() || !file.canRead()) {
            Toast.makeText(context, "无法读取文件: " + file.getAbsolutePath(), Toast.LENGTH_LONG).show();
            return;
        }

        try {
            FileInputStream fis = new FileInputStream(file);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = fis.read(buf)) != -1) {
                baos.write(buf, 0, n);
            }
            fis.close();
            byte[] data = baos.toByteArray();

            String content = "";
            try {
                content = new String(data, "UTF-8");
                if (content.startsWith("\uFEFF")) {
                    content = content.substring(1);
                }
            } catch (Exception ignored) {}

            if (!content.contains("#EXTINF") && !content.contains("http://") && !content.contains("https://")) {
                try {
                    content = new String(data, "GB18030");
                } catch (Exception ignored) {}
            }

            importM3uContent(content);
        } catch (Throwable t) {
            Toast.makeText(context, "导入出错: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // 辅助解析方法（移除了错误的 @Override，保证编译正常）
    public void LoadM3USimple(InputStreamReader reader) {
        try {
            BufferedReader br = new BufferedReader(reader);
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append("\n");
            }
            br.close();
            importM3uContent(sb.toString());
        } catch (Throwable t) {
            Toast.makeText(context, "导入出错: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void importM3uContent(String content) {
        if (content == null || content.isEmpty()) {
            Toast.makeText(context, "M3U 内容为空", Toast.LENGTH_SHORT).show();
            return;
        }

        List<DataRadioStation> newStations = new ArrayList<>();
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

                    st.StationUuid = UUID.randomUUID().toString();
                    st.Name = (name != null && !name.isEmpty()) ? name : "电台";
                    st.StreamUrl = url;
                    st.Hls = url.contains(".m3u8");

                    st.HomePageUrl = "";
                    st.IconUrl = "";
                    st.Country = "";
                    st.CountryCode = "";
                    st.State = "";
                    st.Language = "";
                    st.Codec = st.Hls ? "HLS" : "MP3";
                    st.Bitrate = 128;
                    st.Votes = 0;
                    st.ClickCount = 0;
                    st.ClickTrend = 0;

                    newStations.add(st);
                    currentName = null;
                }
            }
        }

        if (newStations.isEmpty()) {
            Toast.makeText(context, "未在 M3U 中识别到有效频道", Toast.LENGTH_SHORT).show();
            return;
        }

        if (listStations == null) {
            listStations = new ArrayList<>();
        }

        // 批量合并到列表，绝不触发高频广播
        int addedCount = 0;
        for (DataRadioStation st : newStations) {
            if (!has(st.StationUuid)) {
                listStations.add(st);
                addedCount++;
            }
        }

        // 仅在最后统一进行单次存盘！
        Save();

        Toast.makeText(context, "成功导入 " + addedCount + " 个电台", Toast.LENGTH_LONG).show();
    }
}
