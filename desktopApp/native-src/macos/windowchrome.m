#import <AppKit/AppKit.h>
#import "windowchrome.h"

static const int kFindRetries = 20;
static const int64_t kFindRetryDelayNs = 150 * NSEC_PER_MSEC;

static __weak NSWindow *gWindow;
static NSString *gTitle;
static double gX;
static double gCenterY;
static BOOL gDark;
static BOOL gApplying;
static BOOL gPending;
static CGFloat gSpacing;
static NSMutableArray *gWindowTokens;
static id gThemeToken;
static melodia_theme_callback gThemeCallback;

static void run_on_main(dispatch_block_t block) {
    if ([NSThread isMainThread]) {
        block();
    } else {
        dispatch_async(dispatch_get_main_queue(), block);
    }
}

int melodia_system_is_dark(void) {
    // 直接读偏好而不是 NSApp.effectiveAppearance：后者受应用自身外观设置影响，且只能在主线程读
    CFPropertyListRef value = CFPreferencesCopyAppValue(CFSTR("AppleInterfaceStyle"), kCFPreferencesAnyApplication);
    if (value == NULL) return 0;
    int dark = CFGetTypeID(value) == CFStringGetTypeID() &&
        CFStringCompare((CFStringRef)value, CFSTR("Dark"), kCFCompareCaseInsensitive) == kCFCompareEqualTo;
    CFRelease(value);
    return dark;
}

void melodia_observe_theme(melodia_theme_callback callback) {
    run_on_main(^{
        NSDistributedNotificationCenter *center = [NSDistributedNotificationCenter defaultCenter];
        if (gThemeToken != nil) {
            [center removeObserver:gThemeToken];
            gThemeToken = nil;
        }
        gThemeCallback = callback;
        if (callback == NULL) return;
        gThemeToken = [center addObserverForName:@"AppleInterfaceThemeChangedNotification"
                                          object:nil
                                           queue:[NSOperationQueue mainQueue]
                                      usingBlock:^(NSNotification *note) {
            melodia_theme_callback current = gThemeCallback;
            if (current != NULL) current();
        }];
    });
}

static void apply_appearance(NSWindow *window) {
    NSAppearanceName name = gDark ? NSAppearanceNameDarkAqua : NSAppearanceNameAqua;
    if (![window.appearance.name isEqualToString:name]) {
        window.appearance = [NSAppearance appearanceNamed:name];
    }
}

// 标题栏容器加高到按钮中心所需的高度，再逐个摆放按钮；全屏时标题栏由系统单独管理，不动
static void layout_buttons(void) {
    NSWindow *window = gWindow;
    if (window == nil || gApplying) return;
    if ((window.styleMask & NSWindowStyleMaskFullScreen) != 0) return;
    NSButton *close = [window standardWindowButton:NSWindowCloseButton];
    NSButton *mini = [window standardWindowButton:NSWindowMiniaturizeButton];
    NSButton *zoom = [window standardWindowButton:NSWindowZoomButton];
    NSView *titlebar = close.superview;
    NSView *container = titlebar.superview;
    if (close == nil || mini == nil || zoom == nil || container == nil) return;

    gApplying = YES;
    CGFloat buttonHeight = NSHeight(close.frame);
    CGFloat height = MAX(round(gCenterY * 2), buttonHeight);
    NSRect containerFrame = container.frame;
    containerFrame.size.height = height;
    containerFrame.origin.y = NSHeight(window.frame) - height;
    if (!NSEqualRects(containerFrame, container.frame)) container.frame = containerFrame;
    if (NSHeight(titlebar.frame) != height) titlebar.frame = container.bounds;

    // 间距只在首次摆放前量一次：之后系统可能只复位了部分按钮，再量会混入改过的坐标
    if (gSpacing <= 0) gSpacing = NSMinX(mini.frame) - NSMinX(close.frame);
    CGFloat spacing = gSpacing > 0 ? gSpacing : 20;
    NSArray<NSButton *> *buttons = @[ close, mini, zoom ];
    for (NSUInteger i = 0; i < buttons.count; i++) {
        NSButton *button = buttons[i];
        NSPoint origin = NSMakePoint(round(gX + i * spacing), round((height - NSHeight(button.frame)) / 2));
        if (!NSEqualPoints(origin, button.frame.origin)) [button setFrameOrigin:origin];
    }
    gApplying = NO;
}

// 合并到下一轮主循环再摆，等系统这一轮的标题栏排版全部结束
static void schedule_layout(void) {
    if (gPending) return;
    gPending = YES;
    dispatch_async(dispatch_get_main_queue(), ^{
        gPending = NO;
        layout_buttons();
    });
}

// 系统会在缩放、切换焦点、退出全屏等时机把按钮放回原位：当场摆一次，下一轮主循环再补一次
static void relayout_soon(void) {
    layout_buttons();
    schedule_layout();
}

static void observe_window(NSWindow *window) {
    NSNotificationCenter *center = [NSNotificationCenter defaultCenter];
    for (id token in gWindowTokens) [center removeObserver:token];
    gWindowTokens = [NSMutableArray array];

    NSArray<NSNotificationName> *names = @[
        NSWindowDidResizeNotification,
        NSWindowDidEndLiveResizeNotification,
        NSWindowDidBecomeKeyNotification,
        NSWindowDidResignKeyNotification,
        NSWindowDidBecomeMainNotification,
        NSWindowDidResignMainNotification,
        NSWindowDidExitFullScreenNotification,
        NSWindowDidDeminiaturizeNotification,
        NSWindowDidChangeBackingPropertiesNotification,
        NSWindowDidChangeScreenNotification
    ];
    for (NSNotificationName name in names) {
        [gWindowTokens addObject:[center addObserverForName:name
                                                     object:window
                                                      queue:nil
                                                 usingBlock:^(NSNotification *note) { relayout_soon(); }]];
    }
    NSButton *close = [window standardWindowButton:NSWindowCloseButton];
    if (close != nil) {
        close.postsFrameChangedNotifications = YES;
        [gWindowTokens addObject:[center addObserverForName:NSViewFrameDidChangeNotification
                                                     object:close
                                                      queue:nil
                                                 usingBlock:^(NSNotification *note) {
            if (!gApplying) schedule_layout();
        }]];
    }
}

static NSWindow *find_window(NSString *title) {
    for (NSWindow *window in [NSApp windows]) {
        if ((window.styleMask & NSWindowStyleMaskTitled) == 0) continue;
        if (![window.title isEqualToString:title]) continue;
        if ([window standardWindowButton:NSWindowCloseButton] == nil) continue;
        return window;
    }
    return nil;
}

static void attach(int retriesLeft) {
    NSWindow *window = find_window(gTitle);
    if (window == nil) {
        if (retriesLeft > 0) {
            dispatch_after(dispatch_time(DISPATCH_TIME_NOW, kFindRetryDelayNs), dispatch_get_main_queue(), ^{
                attach(retriesLeft - 1);
            });
        }
        return;
    }
    if (window != gWindow) {
        gWindow = window;
        observe_window(window);
    }
    apply_appearance(window);
    relayout_soon();
}

void melodia_window_chrome(const char *title, double x, double center_y, int dark) {
    NSString *titleString = title != NULL ? [NSString stringWithUTF8String:title] : nil;
    if (titleString == nil) return;
    run_on_main(^{
        gTitle = titleString;
        gX = x;
        gCenterY = center_y;
        gDark = dark != 0;
        attach(kFindRetries);
    });
}
