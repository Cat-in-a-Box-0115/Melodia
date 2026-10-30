#ifndef MELODIA_WINDOWCHROME_H
#define MELODIA_WINDOWCHROME_H

typedef void (*melodia_theme_callback)(void);

// 系统当前是否为深色外观；可在任意线程调用
int melodia_system_is_dark(void);

// 系统切换深浅色时回调（在主线程）；传 NULL 注销
void melodia_observe_theme(melodia_theme_callback callback);

// 按标题找到主窗口，把红绿灯移到距左 x、垂直中心距顶 center_y 处（单位为窗口逻辑点），
// 并让窗口外观（含系统外框线）跟随应用深浅色。系统重新排版标题栏后会自动再次摆放
void melodia_window_chrome(const char *title, double x, double center_y, int dark);

#endif
