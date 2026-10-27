# Native callbacks are looked up by exact method names and descriptors.
-keep interface io.github.jqssun.airplay.bridge.RaopCallbackHandler { *; }
-keep,allowoptimization,includedescriptorclasses class * implements io.github.jqssun.airplay.bridge.RaopCallbackHandler {
    public byte[] obtainVideoBuffer(int);
    public void onVideoData(byte[],int,long,boolean);
    public void onAudioFormat(int,int,boolean);
    public void onVideoSize(float,float,float,float);
    public void onClientVolume(float);
    public float onClientVolumeQuery();
    public void onAudioTeardown();
    public void onConnectionInit();
    public void onConnectionDestroy();
    public void onConnectionReset(int);
    public void onDisplayPin(java.lang.String);
    public void onMetadata(byte[]);
    public void onCoverArt(byte[]);
    public void onProgress(long,long,long);
    public void onDacpId(java.lang.String,java.lang.String);
    public void onMirrorRunning(boolean);
    public void onVideoPlay(java.lang.String,float);
    public void onVideoScrub(float);
    public void onVideoRate(float);
    public void onVideoStop();
    public void onVideoSessionPoll();
}
-keep interface io.github.jqssun.airplay.bridge.LogListener { *; }
-keep,allowoptimization,includedescriptorclasses class * implements io.github.jqssun.airplay.bridge.LogListener {
    public void onLog(java.lang.String);
}
-keep class io.github.jqssun.airplay.bridge.NativeBridge { *; }
