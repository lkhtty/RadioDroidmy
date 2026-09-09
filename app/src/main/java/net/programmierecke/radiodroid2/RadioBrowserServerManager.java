package net.programmierecke.radiodroid2;

import android.util.Log;

/**
 * Created by segler on 15.02.18.
 * Modified: 切断海外服务器轮询，纯本地流媒体模式
 */
public class RadioBrowserServerManager {
    static String currentServer = "127.0.0.1";
    static String[] serverList = new String[]{"127.0.0.1"};

    /**
     * 彻底阻断海外 DNS 轮询，不发任何网络请求，秒级返回
     */
    private static String[] doDnsServerListing() {
        Log.d("DNS", "doDnsServerListing() 已禁用海外解析，使用本地模式");
        return new String[]{"127.0.0.1"};
    }

    /**
     * 直接返回本地列表，禁止网络刷新
     */
    public static String[] getServerList(boolean forceRefresh){
        if (serverList == null || serverList.length == 0){
            serverList = new String[]{"127.0.0.1"};
        }
        return serverList;
    }

    /**
     * 直接返回本地虚拟服务，不再随机抽取海外节点
     */
    public static String getCurrentServer() {
        if (currentServer == null){
            currentServer = "127.0.0.1";
        }
        return currentServer;
    }

    /**
     * Set new server as current
     */
    public static void setCurrentServer(String newServer){
        currentServer = newServer;
    }

    /**
     * Construct full url from server and path
     */
    public static String constructEndpoint(String server, String path){
        return "https://" + server + "/" + path;
    }
}
