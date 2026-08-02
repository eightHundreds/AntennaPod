package de.danoeh.antennapod.ui.screen.playback.audio;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.res.Configuration;
import android.graphics.ColorFilter;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.BlendModeColorFilterCompat;
import androidx.core.graphics.BlendModeCompat;
import androidx.fragment.app.Fragment;
import androidx.media3.session.MediaController;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.bumptech.glide.RequestBuilder;
import com.bumptech.glide.load.resource.bitmap.FitCenter;
import com.bumptech.glide.load.resource.bitmap.RoundedCorners;
import com.bumptech.glide.request.RequestOptions;
import de.danoeh.antennapod.BuildConfig;
import de.danoeh.antennapod.R;
import de.danoeh.antennapod.activity.MainActivity;
import de.danoeh.antennapod.event.MessageEvent;
import de.danoeh.antennapod.event.PlayerStatusEvent;
import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.model.feed.Transcript;
import de.danoeh.antennapod.model.feed.TranscriptSegment;
import de.danoeh.antennapod.playback.service.PlaybackService;
import de.danoeh.antennapod.playback.service.PlaybackServiceStarter;
import de.danoeh.antennapod.storage.database.DBReader;
import de.danoeh.antennapod.storage.preferences.PlaybackPreferences;
import de.danoeh.antennapod.ui.appstartintent.MainActivityStarter;
import de.danoeh.antennapod.ui.appstartintent.MediaButtonStarter;
import de.danoeh.antennapod.ui.appstartintent.OnlineFeedviewActivityStarter;
import de.danoeh.antennapod.ui.chapters.ChapterUtils;
import de.danoeh.antennapod.ui.screen.chapter.ChaptersFragment;
import de.danoeh.antennapod.playback.service.PlaybackController;
import de.danoeh.antennapod.ui.common.DateFormatter;
import de.danoeh.antennapod.databinding.CoverFragmentBinding;
import de.danoeh.antennapod.event.playback.PlaybackPositionEvent;
import de.danoeh.antennapod.model.feed.Chapter;
import de.danoeh.antennapod.model.feed.EmbeddedChapterImage;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.model.playback.Playable;
import de.danoeh.antennapod.ui.episodes.ImageResourceUtils;
import de.danoeh.antennapod.ui.screen.playback.TranscriptAdapter;
import de.danoeh.antennapod.ui.screen.playback.TranscriptDialogFragment;
import de.danoeh.antennapod.ui.transcript.TranscriptUtils;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import org.apache.commons.lang3.StringUtils;
import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.List;

import static android.widget.LinearLayout.LayoutParams.MATCH_PARENT;
import static android.widget.LinearLayout.LayoutParams.WRAP_CONTENT;

/**
 * Displays the cover and the title of a FeedItem.
 */
public class CoverFragment extends Fragment implements TranscriptAdapter.SegmentClickListener {
    private static final String TAG = "CoverFragment";
    private CoverFragmentBinding viewBinding;
    private Disposable disposable;
    private Disposable transcriptDisposable;
    private int displayedChapterIndex = -1;
    private Playable media;
    private Transcript transcript;
    private TranscriptAdapter transcriptAdapter;
    private LinearLayoutManager transcriptLayoutManager;
    private boolean transcriptVisible = false;
    private boolean doInitialTranscriptScroll = true;
    private int lastFollowedSegmentIndex = -1;
    private int pendingFollowSegmentIndex = -1;
    private int lastPlaybackPositionMs = -1;
    private long transcriptMediaGeneration = 0;
    private boolean ignoreFollowCheckboxCallback = false;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        viewBinding = CoverFragmentBinding.inflate(inflater);
        viewBinding.imgvCover.setOnClickListener(v -> onCoverClicked());
        viewBinding.butCloseTranscript.setOnClickListener(v -> setTranscriptVisible(false));
        viewBinding.butRefreshTranscript.setOnClickListener(v -> {
            if (transcriptVisible) {
                doInitialTranscriptScroll = true;
                lastFollowedSegmentIndex = -1;
                pendingFollowSegmentIndex = -1;
                loadTranscript(true);
            }
        });
        viewBinding.openDescription.setOnClickListener(view -> {
            if (transcriptVisible) {
                setTranscriptVisible(false);
            }
            ((AudioPlayerFragment) requireParentFragment())
                    .scrollToPage(AudioPlayerFragment.POS_DESCRIPTION, true);
        });
        ColorFilter colorFilter = BlendModeColorFilterCompat.createBlendModeColorFilterCompat(
                viewBinding.txtvPodcastTitle.getCurrentTextColor(), BlendModeCompat.SRC_IN);
        viewBinding.butNextChapter.setColorFilter(colorFilter);
        viewBinding.butPrevChapter.setColorFilter(colorFilter);
        viewBinding.descriptionIcon.setColorFilter(colorFilter);
        viewBinding.chapterButton.setOnClickListener(v ->
                new ChaptersFragment().show(getChildFragmentManager(), ChaptersFragment.TAG));
        viewBinding.butPrevChapter.setOnClickListener(v -> seekToPrevChapter());
        viewBinding.butNextChapter.setOnClickListener(v -> seekToNextChapter());

        transcriptLayoutManager = new LinearLayoutManager(getContext());
        viewBinding.transcriptList.setLayoutManager(transcriptLayoutManager);
        viewBinding.transcriptList.setItemAnimator(null);
        transcriptAdapter = new TranscriptAdapter(getContext(), this);
        viewBinding.transcriptList.setAdapter(transcriptAdapter);
        viewBinding.transcriptList.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                super.onScrollStateChanged(recyclerView, newState);
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    setFollowAudioChecked(false);
                }
            }
        });
        setFollowAudioChecked(true);
        viewBinding.followAudioCheckbox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (ignoreFollowCheckboxCallback) {
                return;
            }
            if (isChecked && transcriptVisible && transcript != null) {
                lastFollowedSegmentIndex = -1;
                pendingFollowSegmentIndex = -1;
                doInitialTranscriptScroll = true;
                scrollTranscriptToPosition(transcript.findSegmentIndexBefore(getBestPlaybackPositionMs()));
            }
        });
        return viewBinding.getRoot();
    }

    private int getBestPlaybackPositionMs() {
        if (lastPlaybackPositionMs >= 0) {
            return lastPlaybackPositionMs;
        }
        if (media != null) {
            return media.getPosition();
        }
        return 0;
    }

    private void setFollowAudioChecked(boolean checked) {
        if (viewBinding == null) {
            return;
        }
        if (viewBinding.followAudioCheckbox.isChecked() == checked) {
            return;
        }
        ignoreFollowCheckboxCallback = true;
        viewBinding.followAudioCheckbox.setChecked(checked);
        ignoreFollowCheckboxCallback = false;
    }

    private void onCoverClicked() {
        if (media instanceof FeedMedia && ((FeedMedia) media).hasTranscript()) {
            setTranscriptVisible(true);
            return;
        }
        togglePlayPause();
    }

    private void togglePlayPause() {
        if (BuildConfig.USE_MEDIA3_PLAYBACK_SERVICE) {
            if (PlaybackService.isRunning) {
                PlaybackController.bindToMedia3Service(getActivity(), MediaController::pause);
            } else if (media != null) {
                new PlaybackServiceStarter(getContext(), media)
                        .callEvenIfRunning(true)
                        .start();
            }
            return;
        }
        if (PlaybackService.isRunning
                && PlaybackPreferences.getCurrentPlayerStatus() == PlaybackPreferences.PLAYER_STATUS_PLAYING) {
            getContext().sendBroadcast(MediaButtonStarter.createIntent(getContext(), KeyEvent.KEYCODE_MEDIA_PAUSE));
        } else if (media != null) {
            new PlaybackServiceStarter(getContext(), media)
                    .callEvenIfRunning(true)
                    .start();
        }
    }

    public void setTranscriptVisible(boolean visible) {
        if (viewBinding == null) {
            return;
        }
        if (visible) {
            if (media == null) {
                transcriptVisible = true;
                viewBinding.imgvCover.setVisibility(View.GONE);
                viewBinding.transcriptContainer.setVisibility(View.VISIBLE);
                viewBinding.transcriptLoading.setVisibility(View.VISIBLE);
                setFollowAudioChecked(true);
                loadMediaInfo(false);
                updateBottomSheetScrollingChild();
                return;
            }
            if (!(media instanceof FeedMedia) || !((FeedMedia) media).hasTranscript()) {
                Toast.makeText(getContext(), R.string.no_transcript_label, Toast.LENGTH_LONG).show();
                return;
            }
        }
        transcriptVisible = visible;
        viewBinding.imgvCover.setVisibility(visible ? View.GONE : View.VISIBLE);
        viewBinding.transcriptContainer.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (visible) {
            doInitialTranscriptScroll = true;
            lastFollowedSegmentIndex = -1;
            pendingFollowSegmentIndex = -1;
            setFollowAudioChecked(true);
            loadTranscript(false);
        } else {
            pendingFollowSegmentIndex = -1;
        }
        updateBottomSheetScrollingChild();
    }

    private void updateBottomSheetScrollingChild() {
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).getBottomSheet().updateScrollingChild();
        }
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        configureForOrientation(getResources().getConfiguration());
    }

    private void loadMediaInfo(boolean includingChapters) {
        if (disposable != null) {
            disposable.dispose();
        }
        disposable = Maybe.<Playable>create(emitter -> {
            Playable media = DBReader.getFeedMedia(PlaybackPreferences.getCurrentlyPlayingFeedMediaId());
            if (media != null) {
                if (includingChapters) {
                    ChapterUtils.loadChapters(media, getContext(), false);
                }
                emitter.onSuccess(media);
            } else {
                emitter.onComplete();
            }
        }).subscribeOn(Schedulers.computation())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(media -> {
                    boolean mediaChanged = this.media == null || this.media.getIdentifier() == null
                            || !this.media.getIdentifier().equals(media.getIdentifier());
                    if (!mediaChanged && this.media instanceof FeedMedia && media instanceof FeedMedia
                            && transcript != null) {
                        ((FeedMedia) media).setTranscript(transcript);
                    }
                    this.media = media;
                    displayMediaInfo(media, mediaChanged);
                    if (media.getChapters() == null && !includingChapters) {
                        loadMediaInfo(true);
                    }
                }, error -> Log.e(TAG, Log.getStackTraceString(error)));
    }

    private void displayMediaInfo(@NonNull Playable media, boolean mediaChanged) {
        String pubDateStr = DateFormatter.formatAbbrev(getActivity(), media.getPubDate());
        viewBinding.txtvPodcastTitle.setText(StringUtils.stripToEmpty(media.getFeedTitle())
                + "\u00A0"
                + "・"
                + "\u00A0"
                + StringUtils.replace(StringUtils.stripToEmpty(pubDateStr), " ", "\u00A0"));
        if (media instanceof FeedMedia) {
            viewBinding.txtvPodcastTitle.setOnClickListener(v -> openFeed(((FeedMedia) media).getItem().getFeed()));
        } else {
            viewBinding.txtvPodcastTitle.setOnClickListener(null);
        }
        viewBinding.txtvPodcastTitle.setOnLongClickListener(v -> copyText(media.getFeedTitle()));
        viewBinding.txtvEpisodeTitle.setText(media.getEpisodeTitle());
        viewBinding.txtvEpisodeTitle.setOnLongClickListener(v -> copyText(media.getEpisodeTitle()));
        viewBinding.txtvEpisodeTitle.setOnClickListener(v -> {
            int lines = viewBinding.txtvEpisodeTitle.getLineCount();
            int animUnit = 1500;
            if (lines > viewBinding.txtvEpisodeTitle.getMaxLines()) {
                int titleHeight = viewBinding.txtvEpisodeTitle.getHeight()
                        - viewBinding.txtvEpisodeTitle.getPaddingTop()
                        - viewBinding.txtvEpisodeTitle.getPaddingBottom();
                ObjectAnimator verticalMarquee = ObjectAnimator.ofInt(
                        viewBinding.txtvEpisodeTitle, "scrollY", 0, (lines - viewBinding.txtvEpisodeTitle.getMaxLines())
                                        * (titleHeight / viewBinding.txtvEpisodeTitle.getMaxLines()))
                        .setDuration(lines * animUnit);
                ObjectAnimator fadeOut = ObjectAnimator.ofFloat(
                        viewBinding.txtvEpisodeTitle, "alpha", 0);
                fadeOut.setStartDelay(animUnit);
                fadeOut.addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        viewBinding.txtvEpisodeTitle.scrollTo(0, 0);
                    }
                });
                ObjectAnimator fadeBackIn = ObjectAnimator.ofFloat(
                        viewBinding.txtvEpisodeTitle, "alpha", 1);
                AnimatorSet set = new AnimatorSet();
                set.playSequentially(verticalMarquee, fadeOut, fadeBackIn);
                set.start();
            }
        });
        
        displayedChapterIndex = -1;
        refreshChapterData(Chapter.getAfterPosition(media.getChapters(), media.getPosition()));
        updateChapterControlVisibility();
        updateCoverClickAccessibility();

        if (mediaChanged) {
            transcriptMediaGeneration++;
            transcript = null;
            lastFollowedSegmentIndex = -1;
            pendingFollowSegmentIndex = -1;
            lastPlaybackPositionMs = -1;
            if (transcriptVisible) {
                if (media instanceof FeedMedia && ((FeedMedia) media).hasTranscript()) {
                    doInitialTranscriptScroll = true;
                    loadTranscript(false);
                } else {
                    setTranscriptVisible(false);
                }
            }
        }
    }

    private void updateCoverClickAccessibility() {
        if (viewBinding == null) {
            return;
        }
        if (media instanceof FeedMedia && ((FeedMedia) media).hasTranscript()) {
            viewBinding.imgvCover.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            viewBinding.imgvCover.setContentDescription(getString(R.string.show_transcript));
        } else {
            viewBinding.imgvCover.setContentDescription(null);
            viewBinding.imgvCover.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
    }

    private void openFeed(Feed feed) {
        if (feed == null) {
            return;
        }
        if (feed.getState() == Feed.STATE_NOT_SUBSCRIBED) {
            startActivity(new OnlineFeedviewActivityStarter(getContext(), feed.getDownloadUrl()).getIntent());
        } else {
            new MainActivityStarter(getContext()).withOpenFeed(feed.getId()).withClearTop().start();
        }
    }

    private void updateChapterControlVisibility() {
        boolean chapterControlVisible = false;
        if (media.getChapters() != null) {
            chapterControlVisible = media.getChapters().size() > 0;
        } else if (media instanceof FeedMedia) {
            FeedMedia fm = ((FeedMedia) media);
            // If an item has chapters but they are not loaded yet, still display the button.
            chapterControlVisible = fm.getItem() != null && fm.getItem().hasChapters();
        }
        int newVisibility = chapterControlVisible ? View.VISIBLE : View.GONE;
        if (viewBinding.chapterButton.getVisibility() != newVisibility) {
            viewBinding.chapterButton.setVisibility(newVisibility);
            ObjectAnimator.ofFloat(viewBinding.chapterButton,
                    "alpha",
                    chapterControlVisible ? 0 : 1,
                    chapterControlVisible ? 1 : 0)
                    .start();
        }
    }

    private void refreshChapterData(int chapterIndex) {
        List<Chapter> chapters = media.getChapters();
        if (chapterIndex > -1 && chapters != null) {
            if (media.getPosition() > media.getDuration() || chapterIndex >= chapters.size() - 1) {
                displayedChapterIndex = chapters.size() - 1;
                viewBinding.butNextChapter.setVisibility(View.INVISIBLE);
            } else {
                displayedChapterIndex = chapterIndex;
                viewBinding.butNextChapter.setVisibility(View.VISIBLE);
            }
        }

        displayCoverImage();
    }

    private Chapter getCurrentChapter() {
        if (media == null || media.getChapters() == null || displayedChapterIndex == -1) {
            return null;
        }
        return media.getChapters().get(displayedChapterIndex);
    }

    private void seekToPrevChapter() {
        Chapter curr = getCurrentChapter();

        if (curr == null || displayedChapterIndex == -1) {
            return;
        }

        PlaybackController.bindToMedia3Service(getActivity(), controller -> {
            if (displayedChapterIndex < 1) {
                controller.seekTo(0);
            } else if ((controller.getCurrentPosition() - 10000 * controller.getPlaybackParameters().speed)
                    < curr.getStart()) {
                refreshChapterData(displayedChapterIndex - 1);
                controller.seekTo(media.getChapters().get(displayedChapterIndex).getStart());
            } else {
                controller.seekTo(curr.getStart());
            }
        });
    }

    private void seekToNextChapter() {
        if (media == null || media.getChapters() == null
                || displayedChapterIndex == -1 || displayedChapterIndex + 1 >= media.getChapters().size()) {
            return;
        }

        refreshChapterData(displayedChapterIndex + 1);
        PlaybackController.bindToMedia3Service(getActivity(), controller ->
                controller.seekTo(media.getChapters().get(displayedChapterIndex).getStart()));
    }

    @Override
    public void onStart() {
        super.onStart();
        loadMediaInfo(false);
        EventBus.getDefault().register(this);
    }

    @Override
    public void onStop() {
        super.onStop();

        EventBus.getDefault().unregister(this);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (disposable != null) {
            disposable.dispose();
        }
        if (transcriptDisposable != null) {
            transcriptDisposable.dispose();
        }
        viewBinding = null;
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onPlayerStatusEvent(PlayerStatusEvent event) {
        loadMediaInfo(false);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onEventMainThread(PlaybackPositionEvent event) {
        lastPlaybackPositionMs = event.getPosition();
        if (media == null) {
            return;
        }
        int newChapterIndex = Chapter.getAfterPosition(media.getChapters(), event.getPosition());
        if (newChapterIndex > -1 && newChapterIndex != displayedChapterIndex) {
            refreshChapterData(newChapterIndex);
        }
        if (transcriptVisible && transcript != null) {
            scrollTranscriptToPosition(transcript.findSegmentIndexBefore(event.getPosition()));
        }
    }

    private void loadTranscript(boolean forceRefresh) {
        if (!(media instanceof FeedMedia) || !((FeedMedia) media).hasTranscript()) {
            setTranscriptVisible(false);
            return;
        }
        if (transcriptDisposable != null) {
            transcriptDisposable.dispose();
        }
        viewBinding.transcriptLoading.setVisibility(View.VISIBLE);
        final FeedMedia feedMedia = (FeedMedia) media;
        final long generation = transcriptMediaGeneration;
        transcriptDisposable = Maybe.<Transcript>create(emitter -> {
            Transcript loaded = TranscriptUtils.loadTranscript(feedMedia, forceRefresh);
            if (loaded != null) {
                feedMedia.setTranscript(loaded);
                emitter.onSuccess(loaded);
            } else {
                emitter.onComplete();
            }
        })
        .subscribeOn(Schedulers.computation())
        .observeOn(AndroidSchedulers.mainThread())
        .subscribe(loaded -> {
            if (viewBinding == null || generation != transcriptMediaGeneration) {
                return;
            }
            transcript = loaded;
            viewBinding.transcriptLoading.setVisibility(View.GONE);
            transcriptAdapter.setMedia(feedMedia);
            doInitialTranscriptScroll = true;
            lastFollowedSegmentIndex = -1;
            pendingFollowSegmentIndex = -1;
            scrollTranscriptToPosition(transcript.findSegmentIndexBefore(getBestPlaybackPositionMs()));
        }, error -> {
            Log.e(TAG, Log.getStackTraceString(error));
            if (viewBinding != null) {
                viewBinding.transcriptLoading.setVisibility(View.GONE);
            }
        }, () -> {
            if (viewBinding == null || generation != transcriptMediaGeneration) {
                return;
            }
            viewBinding.transcriptLoading.setVisibility(View.GONE);
            Toast.makeText(getContext(), R.string.no_transcript_label, Toast.LENGTH_LONG).show();
            setTranscriptVisible(false);
        });
    }

    private void scrollTranscriptToPosition(int pos) {
        if (viewBinding == null || pos < 0 || transcriptLayoutManager == null || transcriptAdapter == null) {
            return;
        }
        if (!viewBinding.followAudioCheckbox.isChecked() && !doInitialTranscriptScroll) {
            return;
        }
        int itemCount = transcriptAdapter.getItemCount();
        if (itemCount == 0) {
            return;
        }
        final int segmentIndex = Math.min(pos, itemCount - 1);
        if (!doInitialTranscriptScroll
                && segmentIndex == lastFollowedSegmentIndex
                && pendingFollowSegmentIndex < 0) {
            return;
        }
        if (!doInitialTranscriptScroll && segmentIndex == pendingFollowSegmentIndex) {
            return;
        }

        final boolean initialScroll = doInitialTranscriptScroll;
        doInitialTranscriptScroll = false;
        pendingFollowSegmentIndex = segmentIndex;
        final long generation = transcriptMediaGeneration;
        final int topOffsetPx = (int) (24f * getResources().getDisplayMetrics().density);

        viewBinding.transcriptList.stopScroll();
        viewBinding.transcriptList.post(() -> {
            if (viewBinding == null || transcriptLayoutManager == null
                    || generation != transcriptMediaGeneration) {
                return;
            }
            if (!viewBinding.followAudioCheckbox.isChecked() && !initialScroll) {
                pendingFollowSegmentIndex = -1;
                return;
            }
            int count = transcriptAdapter.getItemCount();
            if (count == 0 || segmentIndex >= count) {
                pendingFollowSegmentIndex = -1;
                return;
            }

            int firstVisible = transcriptLayoutManager.findFirstVisibleItemPosition();
            int lastVisible = transcriptLayoutManager.findLastVisibleItemPosition();
            if (!initialScroll && firstVisible != RecyclerView.NO_POSITION
                    && firstVisible < segmentIndex - 1
                    && !viewBinding.transcriptList.canScrollVertically(1)) {
                lastFollowedSegmentIndex = segmentIndex;
                pendingFollowSegmentIndex = -1;
                return;
            }
            if (!initialScroll && firstVisible != RecyclerView.NO_POSITION
                    && lastVisible != RecyclerView.NO_POSITION
                    && segmentIndex >= firstVisible && segmentIndex <= lastVisible) {
                View child = transcriptLayoutManager.findViewByPosition(segmentIndex);
                if (child != null && child.getTop() >= 0 && child.getTop() <= topOffsetPx * 3) {
                    lastFollowedSegmentIndex = segmentIndex;
                    pendingFollowSegmentIndex = -1;
                    return;
                }
            }

            transcriptLayoutManager.scrollToPositionWithOffset(segmentIndex, topOffsetPx);
            lastFollowedSegmentIndex = segmentIndex;
            pendingFollowSegmentIndex = -1;
        });
    }

    @Override
    public void onTranscriptClicked(int position, TranscriptSegment segment) {
        long startTime = segment.getStartTime();
        long endTime = segment.getEndTime();

        lastFollowedSegmentIndex = -1;
        pendingFollowSegmentIndex = -1;
        doInitialTranscriptScroll = true;
        lastPlaybackPositionMs = (int) startTime;
        scrollTranscriptToPosition(position);
        PlaybackController.bindToMedia3Service(getActivity(), controller -> {
            if (!(controller.getCurrentPosition() >= startTime
                    && controller.getCurrentPosition() <= endTime)) {
                controller.seekTo(startTime);
            } else if (controller.isPlaying()) {
                controller.pause();
            } else {
                controller.play();
            }
        });
        setFollowAudioChecked(true);
    }

    @Override
    public void onTranscriptLongClicked(int position, TranscriptSegment seg) {
        new TranscriptDialogFragment().show(
                requireActivity().getSupportFragmentManager(), TranscriptDialogFragment.TAG);
    }

    private void displayCoverImage() {
        RequestOptions options = new RequestOptions()
                .dontAnimate()
                .transform(new FitCenter(),
                        new RoundedCorners((int) (16 * getResources().getDisplayMetrics().density)));

        RequestBuilder<Drawable> cover = Glide.with(this)
                .load(media.getImageLocation())
                .error(Glide.with(this)
                        .load(ImageResourceUtils.getFallbackImageLocation(media))
                        .apply(options))
                .apply(options);

        if (displayedChapterIndex == -1 || media == null || media.getChapters() == null
                || TextUtils.isEmpty(media.getChapters().get(displayedChapterIndex).getImageUrl())) {
            cover.into(viewBinding.imgvCover);
        } else {
            Glide.with(this)
                    .load(EmbeddedChapterImage.getModelFor(media, displayedChapterIndex))
                    .apply(options)
                    .thumbnail(cover)
                    .error(cover)
                    .into(viewBinding.imgvCover);
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        configureForOrientation(newConfig);
    }

    private void configureForOrientation(Configuration newConfig) {
        boolean isPortrait = newConfig.orientation == Configuration.ORIENTATION_PORTRAIT;

        viewBinding.coverFragment.setOrientation(isPortrait ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);

        if (isPortrait) {
            viewBinding.coverHolder.setLayoutParams(new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1));
            viewBinding.coverFragmentTextContainer.setLayoutParams(
                    new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        } else {
            viewBinding.coverHolder.setLayoutParams(new LinearLayout.LayoutParams(0, MATCH_PARENT, 1));
            viewBinding.coverFragmentTextContainer.setLayoutParams(new LinearLayout.LayoutParams(0, MATCH_PARENT, 1));
        }

        ((ViewGroup) viewBinding.episodeDetails.getParent()).removeView(viewBinding.episodeDetails);
        if (isPortrait) {
            viewBinding.coverFragment.addView(viewBinding.episodeDetails);
        } else {
            viewBinding.coverFragmentTextContainer.addView(viewBinding.episodeDetails);
        }
    }

    private boolean copyText(String text) {
        ClipboardManager clipboardManager = ContextCompat.getSystemService(requireContext(), ClipboardManager.class);
        if (clipboardManager != null) {
            clipboardManager.setPrimaryClip(ClipData.newPlainText("AntennaPod", text));
        }
        if (Build.VERSION.SDK_INT <= 32) {
            EventBus.getDefault().post(new MessageEvent(getString(R.string.copied_to_clipboard)));
        }
        return true;
    }
}
