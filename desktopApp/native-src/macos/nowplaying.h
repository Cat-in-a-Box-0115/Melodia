#pragma once
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

// 命令：1 播放，2 暂停，3 下一首，4 上一首，5 跳转，6 播放/暂停；位置单位为毫秒
typedef void (*NowPlayingCallback)(int command, int64_t value);
int nowplaying_init(NowPlayingCallback callback);
void nowplaying_set_enabled(int enabled);
// 字符串为 UTF-8；空标题指针清除曲目信息
void nowplaying_set_metadata(const char *title, const char *artist, const char *album);
void nowplaying_set_artwork(const unsigned char *bytes, int length);
void nowplaying_set_playback(int playing, int64_t positionMs, int64_t durationMs);
void nowplaying_shutdown(void);

#ifdef __cplusplus
}
#endif
