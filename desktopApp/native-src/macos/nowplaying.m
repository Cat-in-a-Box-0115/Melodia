#import <AppKit/AppKit.h>
#import <MediaPlayer/MediaPlayer.h>
#include <pthread.h>
#include <math.h>
#include "nowplaying.h"

// 媒体状态仅在主队列访问；回调锁保证关闭后不再进入 JVM
static pthread_mutex_t callbackLock = PTHREAD_MUTEX_INITIALIZER;
static NowPlayingCallback commandCallback;
static NSMutableDictionary *info;
static NSArray<MPRemoteCommand *> *commands;
static NSMutableArray *targets;
static BOOL enabled = YES;
static BOOL playing;

static void publish(void) {
    BOOL active = enabled && info[MPMediaItemPropertyTitle] != nil;
    for (MPRemoteCommand *command in commands) command.enabled = active;
    MPNowPlayingInfoCenter *center = MPNowPlayingInfoCenter.defaultCenter;
    center.nowPlayingInfo = active ? info : nil;
    center.playbackState = active ? (playing ? MPNowPlayingPlaybackStatePlaying : MPNowPlayingPlaybackStatePaused)
                                  : MPNowPlayingPlaybackStateUnknown;
}

int nowplaying_init(NowPlayingCallback callback) {
    if (!callback) return -1;
    pthread_mutex_lock(&callbackLock);
    commandCallback = callback;
    pthread_mutex_unlock(&callbackLock);
    dispatch_async(dispatch_get_main_queue(), ^{
        if (targets) return;
        info = [NSMutableDictionary dictionary];
        targets = [NSMutableArray array];
        enabled = YES;
        playing = NO;
        MPRemoteCommandCenter *center = MPRemoteCommandCenter.sharedCommandCenter;
        commands = @[center.playCommand, center.pauseCommand, center.nextTrackCommand,
                     center.previousTrackCommand, center.changePlaybackPositionCommand, center.togglePlayPauseCommand];
        [commands enumerateObjectsUsingBlock:^(MPRemoteCommand *command, NSUInteger index, BOOL *stop) {
            id target = [command addTargetWithHandler:^MPRemoteCommandHandlerStatus(MPRemoteCommandEvent *event) {
                int64_t value = 0;
                if (index == 4) {
                    double seconds = ((MPChangePlaybackPositionCommandEvent *)event).positionTime;
                    if (!isfinite(seconds) || seconds < 0 || seconds >= (double)INT64_MAX / 1000.0)
                        return MPRemoteCommandHandlerStatusCommandFailed;
                    value = (int64_t)(seconds * 1000.0);
                }
                pthread_mutex_lock(&callbackLock);
                BOOL accepted = commandCallback != NULL;
                if (accepted) commandCallback((int)index + 1, value);
                pthread_mutex_unlock(&callbackLock);
                return accepted ? MPRemoteCommandHandlerStatusSuccess : MPRemoteCommandHandlerStatusCommandFailed;
            }];
            [targets addObject:target];
        }];
        publish();
    });
    return 0;
}

void nowplaying_set_enabled(int value) {
    dispatch_async(dispatch_get_main_queue(), ^{
        enabled = value != 0;
        publish();
    });
}

void nowplaying_set_metadata(const char *title, const char *artist, const char *album) {
    // JNA 参数只在本次调用内有效，入队前复制
    NSString *titleCopy = title ? [NSString stringWithUTF8String:title] : nil;
    NSString *artistCopy = artist ? [NSString stringWithUTF8String:artist] : @"";
    NSString *albumCopy = album ? [NSString stringWithUTF8String:album] : @"";
    dispatch_async(dispatch_get_main_queue(), ^{
        [info removeObjectForKey:MPMediaItemPropertyArtwork];
        if (titleCopy) {
            info[MPMediaItemPropertyTitle] = titleCopy;
            info[MPMediaItemPropertyArtist] = artistCopy ?: @"";
            info[MPMediaItemPropertyAlbumTitle] = albumCopy ?: @"";
            info[MPNowPlayingInfoPropertyMediaType] = @(MPNowPlayingInfoMediaTypeAudio);
        } else {
            [info removeAllObjects];
        }
        publish();
    });
}

void nowplaying_set_artwork(const unsigned char *bytes, int length) {
    NSData *data = bytes && length > 0 ? [NSData dataWithBytes:bytes length:(NSUInteger)length] : nil;
    dispatch_async(dispatch_get_main_queue(), ^{
        NSImage *image = data ? [[NSImage alloc] initWithData:data] : nil;
        if (image && image.size.width > 0 && image.size.height > 0) {
            info[MPMediaItemPropertyArtwork] = [[MPMediaItemArtwork alloc] initWithBoundsSize:image.size
                requestHandler:^NSImage *(CGSize size) { return image; }];
        } else {
            [info removeObjectForKey:MPMediaItemPropertyArtwork];
        }
        publish();
    });
}

void nowplaying_set_playback(int value, int64_t positionMs, int64_t durationMs) {
    dispatch_async(dispatch_get_main_queue(), ^{
        playing = value != 0;
        int64_t duration = MAX(0, durationMs);
        int64_t position = MAX(0, positionMs);
        if (duration > 0) position = MIN(position, duration);
        info[MPMediaItemPropertyPlaybackDuration] = @(duration / 1000.0);
        info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = @(position / 1000.0);
        info[MPNowPlayingInfoPropertyPlaybackRate] = @(playing ? 1.0 : 0.0);
        info[MPNowPlayingInfoPropertyDefaultPlaybackRate] = @1.0;
        publish();
    });
}

void nowplaying_shutdown(void) {
    pthread_mutex_lock(&callbackLock);
    commandCallback = NULL;
    pthread_mutex_unlock(&callbackLock);
    dispatch_async(dispatch_get_main_queue(), ^{
        [commands enumerateObjectsUsingBlock:^(MPRemoteCommand *command, NSUInteger index, BOOL *stop) {
            command.enabled = NO;
            [command removeTarget:targets[index]];
        }];
        commands = nil;
        targets = nil;
        info = nil;
        publish();
    });
}
