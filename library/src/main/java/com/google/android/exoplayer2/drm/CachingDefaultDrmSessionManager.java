package com.google.android.exoplayer2.drm;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.ConditionVariable;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.extractor.mp4.PsshAtomUtil;
import com.google.android.exoplayer2.util.Util;

import java.util.Arrays;
import java.util.HashMap;
import java.util.UUID;

import static com.google.android.exoplayer2.drm.DefaultDrmSessionManager.MODE_DOWNLOAD;
import static com.google.android.exoplayer2.drm.DefaultDrmSessionManager.MODE_QUERY;

public class CachingDefaultDrmSessionManager<T extends ExoMediaCrypto> implements DrmSessionManager<T> {

    private final SharedPreferences drmkeys;
    public static final String TAG="CachingDRM";
    private final DefaultDrmSessionManager<T> delegateDefaultDrmSessionManager;
    private final UUID uuid;
    private final ConditionVariable conditionVariable;
    private byte[] schemeInitD;

    public interface EventListener {

        /**
         * Called each time keys are loaded.
         */
        void onDrmKeysLoaded();

        /**
         * Called when a drm error occurs.
         *
         * @param e The corresponding exception.
         */
        void onDrmSessionManagerError(Exception e);

        /**
         * Called each time offline keys are restored.
         */
        void onDrmKeysRestored();

        /**
         * Called each time offline keys are removed.
         */
        void onDrmKeysRemoved();

    }

    public CachingDefaultDrmSessionManager(Context context, UUID uuid, ExoMediaDrm<T> mediaDrm, MediaDrmCallback callback, HashMap<String, String> optionalKeyRequestParameters, final Handler eventHandler, final EventListener eventListener) {
        //super(uuid, mediaDrm, callback, optionalKeyRequestParameters, eventHandler, eventListener);
        this.uuid = uuid;
        DefaultDrmSessionManager.EventListener eventListenerInternal = new DefaultDrmSessionManager.EventListener() {

            @Override
            public void onDrmKeysLoaded() {
                saveDrmKeys();
                conditionVariable.open();
                if (eventListener!=null) eventListener.onDrmKeysLoaded();
            }

            @Override
            public void onDrmSessionManagerError(Exception e) {
                conditionVariable.open();
                if (eventListener!=null) eventListener.onDrmSessionManagerError(e);
            }

            @Override
            public void onDrmKeysRestored() {
                saveDrmKeys();
                conditionVariable.open();
                if (eventListener!=null) eventListener.onDrmKeysRestored();
            }

            @Override
            public void onDrmKeysRemoved() {
                conditionVariable.open();
                if (eventListener!=null) eventListener.onDrmKeysRemoved();
            }
        };
        delegateDefaultDrmSessionManager = new DefaultDrmSessionManager<T>(uuid, mediaDrm, callback, optionalKeyRequestParameters, eventHandler, eventListenerInternal);
        drmkeys = context.getSharedPreferences("drmkeys", Context.MODE_PRIVATE);
        conditionVariable = new ConditionVariable();
        conditionVariable.open();
    }

    final protected static char[] hexArray = "0123456789ABCDEF".toCharArray();
    public static String bytesToHex(byte[] bytes) {
        char[] hexChars = new char[bytes.length * 2];
        for ( int j = 0; j < bytes.length; j++ ) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = hexArray[v >>> 4];
            hexChars[j * 2 + 1] = hexArray[v & 0x0F];
        }
        return new String(hexChars);
    }

    public void saveDrmKeys() {
        byte[] offlineLicenseKeySetId = delegateDefaultDrmSessionManager.getOfflineLicenseKeySetId();
        if (offlineLicenseKeySetId==null) {
            Log.i(TAG,"Failed to download offline license key");
        } else {
            Log.i(TAG,"Storing downloaded offline license key for "+bytesToHex(schemeInitD)+": "+bytesToHex(offlineLicenseKeySetId));
            storeKeySetId(schemeInitD, offlineLicenseKeySetId);
        }
    }

    @Override
    public DrmSession<T> acquireSession(Looper playbackLooper, DrmInitData drmInitData) {
        conditionVariable.block();
        conditionVariable.close();

        // First check if we already have this license in local storage and if it's still valid.
        DrmInitData.SchemeData schemeData = drmInitData.get(uuid);
        schemeInitD = schemeData.data;
        Log.i(TAG,"Request for key for init data "+bytesToHex(schemeInitD));
        if (Util.SDK_INT < 21) {
            // Prior to L the Widevine CDM required data to be extracted from the PSSH atom.
            byte[] psshData = PsshAtomUtil.parseSchemeSpecificData(schemeInitD, C.WIDEVINE_UUID);
            if (psshData == null) {
                // Extraction failed. schemeData isn't a Widevine PSSH atom, so leave it unchanged.
            } else {
                schemeInitD = psshData;
            }
        }
        byte[] cachedKeySetId=loadKeySetId(schemeInitD);
        if (cachedKeySetId!=null) {
            //Load successful.
            Log.i(TAG,"Cached key set found "+bytesToHex(cachedKeySetId));
            if (!Arrays.equals(delegateDefaultDrmSessionManager.getOfflineLicenseKeySetId(), cachedKeySetId))
            {
                delegateDefaultDrmSessionManager.setMode(MODE_QUERY, cachedKeySetId);
            }
        } else {
            Log.i(TAG,"No cached key set found ");
            delegateDefaultDrmSessionManager.setMode(MODE_DOWNLOAD,null);
        }
        DrmSession<T> tDrmSession = delegateDefaultDrmSessionManager.acquireSession(playbackLooper, drmInitData);
        return tDrmSession;
    }

    @Override
    public void releaseSession(DrmSession<T> drmSession) {
        delegateDefaultDrmSessionManager.releaseSession(drmSession);
    }

    public void storeKeySetId(byte[] initData, byte[] keySetId) {
        String encodedInitData = Base64.encodeToString(initData, Base64.NO_WRAP);
        String encodedKeySetId = Base64.encodeToString(keySetId, Base64.NO_WRAP);
        drmkeys.edit()
                .putString(encodedInitData, encodedKeySetId)
                .apply();
    }

    public byte[] loadKeySetId(byte[] initData) {
        String encodedInitData = Base64.encodeToString(initData, Base64.NO_WRAP);
        String encodedKeySetId = drmkeys.getString(encodedInitData, null);
        if (encodedKeySetId == null) return null;
        return Base64.decode(encodedKeySetId, 0);
    }

}
