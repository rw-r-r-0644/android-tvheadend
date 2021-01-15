/*
 * Copyright (c) 2017 Kiall Mac Innes <kiall@macinnes.ie>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ie.macinnes.tvheadend.player;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.util.Log;

import androidx.annotation.Nullable;

import com.google.android.exoplayer2.DefaultRenderersFactory;
import com.google.android.exoplayer2.Renderer;
import com.google.android.exoplayer2.audio.AudioProcessor;
import com.google.android.exoplayer2.audio.AudioRendererEventListener;
import com.google.android.exoplayer2.drm.DrmSessionManager;
import com.google.android.exoplayer2.drm.FrameworkMediaCrypto;
import com.google.android.exoplayer2.mediacodec.MediaCodecInfo;
import com.google.android.exoplayer2.mediacodec.MediaCodecSelector;
import com.google.android.exoplayer2.mediacodec.MediaCodecUtil;
import com.google.android.exoplayer2.video.VideoRendererEventListener;

import java.util.ArrayList;
import java.util.List;

import ie.macinnes.tvheadend.Constants;
import ie.macinnes.tvheadend.R;

class TvheadendRenderersFactory extends DefaultRenderersFactory {

    private static final String TAG = TvheadendRenderersFactory.class.getName();

    public TvheadendRenderersFactory(Context context) {
        super(context);
        setExtensionRendererMode(EXTENSION_RENDERER_MODE_ON);
        setMediaCodecSelector(buildMediaCodecSelector(context));
    }

    @Override
    protected void buildVideoRenderers(Context context,
                                       @ExtensionRendererMode int extensionRendererMode,
                                       MediaCodecSelector mediaCodecSelector,
                                       @Nullable DrmSessionManager<FrameworkMediaCrypto> drmSessionManager,
                                       boolean playClearSamplesWithoutKeys,
                                       boolean enableDecoderFallback,
                                       Handler eventHandler,
                                       VideoRendererEventListener eventListener,
                                       long allowedVideoJoiningTimeMs,
                                       ArrayList<Renderer> out) {

        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());

        final boolean enableShieldWorkaround = sharedPreferences.getBoolean(
                Constants.KEY_SHIELD_WORKAROUND_ENABLED,
                context.getResources().getBoolean(R.bool.pref_default_shield_workaround_enabled)
        );

        if (Build.MODEL.equals("SHIELD Android TV") && enableShieldWorkaround) {
            Log.d(TAG, "Adding ShieldVideoRenderer");
            out.add(new ShieldVideoRenderer(
                    context,
                    mediaCodecSelector,
                    allowedVideoJoiningTimeMs,
                    drmSessionManager,
                    false,
                    eventHandler,
                    eventListener,
                    MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY));
        } else {
            super.buildVideoRenderers(
                    context,
                    extensionRendererMode,
                    mediaCodecSelector,
                    drmSessionManager,
                    playClearSamplesWithoutKeys,
                    enableDecoderFallback,
                    eventHandler,
                    eventListener,
                    allowedVideoJoiningTimeMs,
                    out);
        }
    }

    @Override
    protected void buildAudioRenderers(Context context,
                                       @ExtensionRendererMode int extensionRendererMode,
                                       MediaCodecSelector mediaCodecSelector,
                                       @Nullable DrmSessionManager<FrameworkMediaCrypto> drmSessionManager,
                                       boolean playClearSamplesWithoutKeys,
                                       boolean enableDecoderFallback,
                                       AudioProcessor[] audioProcessors,
                                       Handler eventHandler,
                                       AudioRendererEventListener eventListener,
                                       ArrayList<Renderer> out) {

        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());

        // Enable FFmpeg Audio Decoder
        final boolean enableFfmpegAudioRenderer = sharedPreferences.getBoolean(
                Constants.KEY_FFMPEG_AUDIO_ENABLED,
                context.getResources().getBoolean(R.bool.pref_default_audio_ffmpeg_audio_enabled)
        );

        super.buildAudioRenderers(
                context,
                enableFfmpegAudioRenderer ? EXTENSION_RENDERER_MODE_PREFER : EXTENSION_RENDERER_MODE_OFF,
                mediaCodecSelector,
                drmSessionManager,
                playClearSamplesWithoutKeys,
                enableDecoderFallback,
                audioProcessors,
                eventHandler,
                eventListener,
                out);
    }

    /**
     * Builds a MediaCodecSelector that can explicitly disable audio passthrough
     *
     * @param context
     * @return
     */
    private MediaCodecSelector buildMediaCodecSelector(Context context) {

        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());

        final boolean enablePassthroughDecoder = sharedPreferences.getBoolean(
                Constants.KEY_AUDIO_PASSTHROUGH_DECODER_ENABLED,
                context.getResources().getBoolean(R.bool.pref_default_audio_passthrough_decodeder_enabled));

        return new MediaCodecSelector() {
            @Override
            public List<MediaCodecInfo> getDecoderInfos(String mimeType, boolean requiresSecureDecoder, boolean requiresTunnelingDecoder) throws MediaCodecUtil.DecoderQueryException {
                return MediaCodecUtil.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder);
            }

            @Override
            public MediaCodecInfo getPassthroughDecoderInfo() throws MediaCodecUtil.DecoderQueryException {
                if (enablePassthroughDecoder) {
                    return MediaCodecUtil.getPassthroughDecoderInfo();
                }
                return null;
            }
        };
    }
}
