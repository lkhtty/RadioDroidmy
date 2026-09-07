package net.programmierecke.radiodroid2;

import android.content.Context;
import android.net.Uri;
import net.programmierecke.radiodroid2.station.DataRadioStation;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.UUID;

public class M3uImporter {

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
}
