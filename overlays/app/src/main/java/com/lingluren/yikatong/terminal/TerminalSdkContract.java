package com.lingluren.yikatong.terminal;

/** SDK Demo FaceSDKManager: FEATURE_SIZE / 4 floats in a 512-byte buffer. */
public final class TerminalSdkContract {
    public static final int FEATURE_BYTES=512;
    public static boolean featureSucceeded(float returned){return returned==FEATURE_BYTES/4;}
    public static boolean canStart(boolean serverReady,boolean platformSynced,boolean settingsOpen,
                                   long pendingEvents,long lastHeartbeat,long now){
        return serverReady&&platformSynced&&!settingsOpen&&pendingEvents==0
                &&lastHeartbeat>0&&now>=lastHeartbeat&&now-lastHeartbeat<30000;
    }
    private TerminalSdkContract(){}
}
