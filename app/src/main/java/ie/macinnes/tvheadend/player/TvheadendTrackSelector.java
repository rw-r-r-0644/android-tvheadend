/*
 * Copyright (c) 2016 Kiall Mac Innes <kiall@macinnes.ie>
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
import android.media.tv.TvTrackInfo;
import android.util.Log;
import android.util.Pair;

import androidx.annotation.Nullable;

import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.source.TrackGroup;
import com.google.android.exoplayer2.source.TrackGroupArray;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.trackselection.TrackSelection;
import com.google.android.exoplayer2.util.Assertions;
import com.google.android.exoplayer2.util.MimeTypes;


class TvheadendTrackSelector extends DefaultTrackSelector {

    private static final String TAG = TvheadendTrackSelector.class.getName();

    private String mVideoTrackId;
    private String mAudioTrackId;
    private String mSubtitleTrackId;

    public TvheadendTrackSelector(Context context) {
        super(context);
    }

    public boolean selectTrack(int type, String trackId) {
        Log.d(TAG, "TrackSelector selectTrack: " + type + " / " + trackId);

        switch (type) {
            case TvTrackInfo.TYPE_VIDEO:
                mVideoTrackId = trackId;
                break;
            case TvTrackInfo.TYPE_AUDIO:
                mAudioTrackId = trackId;
                break;
            case TvTrackInfo.TYPE_SUBTITLE:
                mSubtitleTrackId = trackId;
                break;
            default:
                throw new RuntimeException("Invalid track type: " + type);
        }

        invalidate();

        return true;
    }

    @Override
    protected TrackSelection.Definition[] selectAllTracks(
            MappedTrackInfo mappedTrackInfo,
            int[][][] rendererFormatSupports,
            int[] rendererMixedMimeTypeAdaptationSupports,
            Parameters params)
            throws ExoPlaybackException {
        // Apparently, it's very easy to end up choosing multiple audio track renderers, e.g ffmpeg
        // decode and MediaCodec passthrough. When this happens we end up with a Multiple renderer
        // media clocks enabled IllegalStateException exception (see Issue #107).
        TrackSelection.Definition[] definitions = super.selectAllTracks(mappedTrackInfo, rendererFormatSupports, rendererMixedMimeTypeAdaptationSupports, params);

        // If we made multiple audio track selections, keep only one of them.
        // TODO: Make this smarter, if the user prefers English, and we select a passthrough German
        // track and a English mpeg-2 audio track, we really should keep the english one over the
        // German one.
        int selectedAudioRendererIndex = -1;
        for (int trackSelectionIndex = 0; trackSelectionIndex < definitions.length; trackSelectionIndex++) {
            final TrackSelection.Definition definition = definitions[trackSelectionIndex];

            if (definition == null || !MimeTypes.isAudio(definition.group.getFormat(0).sampleMimeType)) {
                // Skip if, a) renderer has no candidate, b) not an audio renderer
                continue;
            }

            if (selectedAudioRendererIndex != -1) {
                // If we already made a selection, discard this extra selection
                definitions[trackSelectionIndex] = null;
                Log.d(TAG, "Discarding Audio Track Selection");
                continue;
            }

            // Otherwise, flag that we've made our selection
            selectedAudioRendererIndex = trackSelectionIndex;
        }

        return definitions;
    }

    @Override
    protected TrackSelection.Definition selectVideoTrack(
            TrackGroupArray groups,
            int[][] formatSupports,
            int mixedMimeTypeAdaptationSupports,
            Parameters params,
            boolean enableAdaptiveTrackSelection)
            throws ExoPlaybackException {

        Log.d(TAG, "TrackSelector selectVideoTrack");

        // If we haven't explicitly chosen a track, defer to the DefaultTrackSelector implementation.
        if (mVideoTrackId == null) {
            return super.selectVideoTrack(groups, formatSupports, mixedMimeTypeAdaptationSupports, params, enableAdaptiveTrackSelection);
        } else {
            for (int groupIndex = 0; groupIndex < groups.length; groupIndex++) {
                TrackGroup trackGroup = groups.get(groupIndex);
                int[] trackFormatSupport = formatSupports[groupIndex];

                for (int trackIndex = 0; trackIndex < trackGroup.length; trackIndex++) {
                    if (isSupported(trackFormatSupport[trackIndex], false)) {
                        Format format = trackGroup.getFormat(trackIndex);

                        if (mVideoTrackId.equals(format.id)) {
                            return new TrackSelection.Definition(trackGroup, trackIndex);
                        }
                    }
                }
            }

            return null;
        }
    }

    @Override
    protected Pair<TrackSelection.Definition, AudioTrackScore> selectAudioTrack(
            TrackGroupArray groups,
            int[][] formatSupports,
            int mixedMimeTypeAdaptationSupports,
            Parameters params,
            boolean enableAdaptiveTrackSelection)
            throws ExoPlaybackException {

        Log.d(TAG, "TrackSelector selectAudioTrack");

        // If we haven't explicitly chosen a track, defer to the DefaultTrackSelector implementation.
        if (mAudioTrackId == null) {
            return super.selectAudioTrack(groups, formatSupports, mixedMimeTypeAdaptationSupports, params, enableAdaptiveTrackSelection);
        } else {
            for (int groupIndex = 0; groupIndex < groups.length; groupIndex++) {
                TrackGroup trackGroup = groups.get(groupIndex);
                int[] trackFormatSupport = formatSupports[groupIndex];

                for (int trackIndex = 0; trackIndex < trackGroup.length; trackIndex++) {
                    if (isSupported(trackFormatSupport[trackIndex], false)) {
                        Format format = trackGroup.getFormat(trackIndex);

                        if (mAudioTrackId.equals(format.id)) {
                            Log.d(TAG, "Matched audio track, create FixedTrackSelection");
                            AudioTrackScore trackScore = new AudioTrackScore(format, params, trackFormatSupport[trackIndex]);
                            TrackSelection.Definition definition = new TrackSelection.Definition(trackGroup, trackIndex);
                            return Pair.create(definition, Assertions.checkNotNull(trackScore));
                        }
                    }
                }
            }

            return null;
        }
    }

    @Override
    protected Pair<TrackSelection.Definition, TextTrackScore> selectTextTrack(
            TrackGroupArray groups,
            int[][] formatSupport,
            Parameters params,
            @Nullable String selectedAudioLanguage)
            throws ExoPlaybackException {

        Log.d(TAG, "TrackSelector selectTextTrack");

        // If we haven't explicitly chosen a track, defer to the DefaultTrackSelector implementation.
        if (mSubtitleTrackId == null) {
            return super.selectTextTrack(groups, formatSupport, params, selectedAudioLanguage);
        } else {
            for (int groupIndex = 0; groupIndex < groups.length; groupIndex++) {
                TrackGroup trackGroup = groups.get(groupIndex);
                int[] trackFormatSupport = formatSupport[groupIndex];

                for (int trackIndex = 0; trackIndex < trackGroup.length; trackIndex++) {
                    if (isSupported(trackFormatSupport[trackIndex], false)) {
                        Format format = trackGroup.getFormat(trackIndex);

                        if (mSubtitleTrackId.equals(format.id)) {
                            TextTrackScore trackScore = new TextTrackScore(format, params, trackFormatSupport[trackIndex], selectedAudioLanguage);
                            TrackSelection.Definition definition = new TrackSelection.Definition(trackGroup, trackIndex);
                            return Pair.create(definition, trackScore);
                        }
                    }
                }
            }

            return null;
        }
    }
}
