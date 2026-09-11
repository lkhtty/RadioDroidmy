package net.programmierecke.radiodroid2;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.os.Build;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import net.programmierecke.radiodroid2.station.DataRadioStation;

import java.util.ArrayList;

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
        String namePrefix = "[源" + slotIndex + "]"; // 与 M3uImporter 第123行 [源1] 保持100%一致
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
}
