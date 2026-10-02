#define XR_USE_PLATFORM_ANDROID
#define XR_USE_GRAPHICS_API_OPENGL_ES

#include <jni.h>
#include <android/log.h>
#include <string.h>
#include <stdlib.h>
#include <unistd.h>
#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include "openxr.h"
#include "openxr_platform.h"

#define TAG "VRUnityXR"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define MAX_EYES 2
#define MAX_SWAP_IMAGES 8

static XrInstance gInstance = XR_NULL_HANDLE;
static XrSystemId gSystem = XR_NULL_SYSTEM_ID;
static XrSession gSession = XR_NULL_HANDLE;
static XrSpace gSpace = XR_NULL_HANDLE;
static XrViewConfigurationType gViewConfig = XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO;
static XrEnvironmentBlendMode gBlend = XR_ENVIRONMENT_BLEND_MODE_OPAQUE;
static XrSwapchain gSwap[MAX_EYES] = { XR_NULL_HANDLE, XR_NULL_HANDLE };
static GLuint gSwapImage[MAX_EYES][MAX_SWAP_IMAGES];
static uint32_t gSwapCount[MAX_EYES];
static uint32_t gSwapIndex[MAX_EYES];
static int32_t gSwapW = 0;
static int32_t gSwapH = 0;
static XrActionSet gActionSet = XR_NULL_HANDLE;
static XrAction gMoveAction = XR_NULL_HANDLE;
static XrAction gTurnAction = XR_NULL_HANDLE;
static XrPath gHandPath[MAX_EYES];
static XrPosef gEyePose[MAX_EYES];
static XrFovf gEyeFov[MAX_EYES];
static XrTime gDisplayTime = 0;
static XrSessionState gState = XR_SESSION_STATE_UNKNOWN;
static int gRunning = 0;
static int gFloorSpace = 0;
static int gAcquired = 0;
static int gShouldQuit = 0;
static EGLDisplay gEglDisplay = EGL_NO_DISPLAY;
static EGLConfig gEglConfig = 0;
static EGLContext gEglContext = EGL_NO_CONTEXT;
static EGLSurface gEglSurface = EGL_NO_SURFACE;
static jobject gActivity = NULL;
static JavaVM *gVm = NULL;

static XrPath pathOf(const char *s) {
    XrPath p = XR_NULL_PATH;
    if (gInstance == XR_NULL_HANDLE) return XR_NULL_PATH;
    if (XR_FAILED(xrStringToPath(gInstance, s, &p))) return XR_NULL_PATH;
    return p;
}

// Both sticks drive the same two actions, so whichever controller layout the
// headset understands works: the left stick walks, the right stick turns.
static void suggestProfile(const char *profile) {
    XrPath profilePath = pathOf(profile);
    if (profilePath == XR_NULL_PATH) return;
    XrActionSuggestedBinding binds[MAX_EYES];
    binds[0].action = gMoveAction;
    binds[0].binding = pathOf("/user/hand/left/input/thumbstick");
    binds[1].action = gTurnAction;
    binds[1].binding = pathOf("/user/hand/right/input/thumbstick");
    if (binds[0].binding == XR_NULL_PATH || binds[1].binding == XR_NULL_PATH) return;
    XrInteractionProfileSuggestedBinding sp;
    memset(&sp, 0, sizeof(sp));
    sp.type = XR_TYPE_INTERACTION_PROFILE_SUGGESTED_BINDING;
    sp.interactionProfile = profilePath;
    sp.countSuggestedBindings = MAX_EYES;
    sp.suggestedBindings = binds;
    xrSuggestInteractionProfileBindings(gInstance, &sp);
}

static int makeContext(void) {
    gEglDisplay = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (gEglDisplay == EGL_NO_DISPLAY) return 0;
    if (!eglInitialize(gEglDisplay, NULL, NULL)) return 0;
    EGLint cfgAttr[] = {
        EGL_RENDERABLE_TYPE, 0x0040, /* EGL_OPENGL_ES3_BIT */
        EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
        EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8,
        EGL_DEPTH_SIZE, 16,
        EGL_NONE };
    EGLint num = 0;
    if (!eglChooseConfig(gEglDisplay, cfgAttr, &gEglConfig, 1, &num) || num < 1) return 0;
    EGLint ctxAttr[] = { EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE };
    gEglContext = eglCreateContext(gEglDisplay, gEglConfig, EGL_NO_CONTEXT, ctxAttr);
    if (gEglContext == EGL_NO_CONTEXT) return 0;
    EGLint pbAttr[] = { EGL_WIDTH, 16, EGL_HEIGHT, 16, EGL_NONE };
    gEglSurface = eglCreatePbufferSurface(gEglDisplay, gEglConfig, pbAttr);
    if (gEglSurface == EGL_NO_SURFACE) return 0;
    if (!eglMakeCurrent(gEglDisplay, gEglSurface, gEglSurface, gEglContext)) return 0;
    return 1;
}

static int createInstance(JNIEnv *env, jobject activity) {
    uint32_t extCount = 0;
    if (XR_FAILED(xrEnumerateInstanceExtensionProperties(NULL, 0, &extCount, NULL))) return 0;
    if (extCount == 0) return 0;
    if (extCount > 256) extCount = 256;
    XrExtensionProperties exts[256];
    for (uint32_t i = 0; i < extCount; i++) {
        memset(&exts[i], 0, sizeof(exts[i]));
        exts[i].type = XR_TYPE_EXTENSION_PROPERTIES;
    }
    uint32_t have = 0;
    if (XR_FAILED(xrEnumerateInstanceExtensionProperties(NULL, extCount, &have, exts))) return 0;
    int hasGraphics = 0;
    int hasAndroid = 0;
    for (uint32_t i = 0; i < have; i++) {
        if (strcmp(exts[i].extensionName, XR_KHR_OPENGL_ES_ENABLE_EXTENSION_NAME) == 0) hasGraphics = 1;
        if (strcmp(exts[i].extensionName, XR_KHR_ANDROID_CREATE_INSTANCE_EXTENSION_NAME) == 0) hasAndroid = 1;
    }
    // No graphics extension means there is no VR runtime on this device.
    if (!hasGraphics) return 0;

    const char *use[2];
    uint32_t useCount = 0;
    use[useCount++] = XR_KHR_OPENGL_ES_ENABLE_EXTENSION_NAME;
    if (hasAndroid) use[useCount++] = XR_KHR_ANDROID_CREATE_INSTANCE_EXTENSION_NAME;

    XrInstanceCreateInfoAndroidKHR androidInfo;
    memset(&androidInfo, 0, sizeof(androidInfo));
    androidInfo.type = XR_TYPE_INSTANCE_CREATE_INFO_ANDROID_KHR;
    androidInfo.applicationVM = gVm;
    androidInfo.applicationActivity = activity;

    XrInstanceCreateInfo ci;
    memset(&ci, 0, sizeof(ci));
    ci.type = XR_TYPE_INSTANCE_CREATE_INFO;
    ci.next = &androidInfo;
    strcpy(ci.applicationInfo.applicationName, "VRUnity");
    ci.applicationInfo.applicationVersion = 1;
    strcpy(ci.applicationInfo.engineName, "VRUnity");
    ci.applicationInfo.engineVersion = 1;
    ci.applicationInfo.apiVersion = XR_MAKE_VERSION(1, 0, 0);
    ci.enabledExtensionCount = useCount;
    ci.enabledExtensionNames = use;
    if (XR_FAILED(xrCreateInstance(&ci, &gInstance))) {
        gInstance = XR_NULL_HANDLE;
        return 0;
    }
    return 1;
}

static int createSession(void) {
    XrSystemGetInfo sgi;
    memset(&sgi, 0, sizeof(sgi));
    sgi.type = XR_TYPE_SYSTEM_GET_INFO;
    sgi.formFactor = XR_FORM_FACTOR_HEAD_MOUNTED_DISPLAY;
    if (XR_FAILED(xrGetSystem(gInstance, &sgi, &gSystem))) return 0;

    // The runtime's own entry point for this is looked up rather than linked, so
    // the build never depends on it being exported.
    typedef XrResult (XRAPI_PTR *PFN_getGlesReqs)(XrInstance, XrSystemId, XrGraphicsRequirementsOpenGLESKHR *);
    PFN_getGlesReqs getReqs = NULL;
    if (XR_FAILED(xrGetInstanceProcAddr(gInstance, "xrGetOpenGLESGraphicsRequirementsKHR", (PFN_xrVoidFunction *)(&getReqs)))) return 0;
    if (getReqs == NULL) return 0;
    XrGraphicsRequirementsOpenGLESKHR reqs;
    memset(&reqs, 0, sizeof(reqs));
    reqs.type = XR_TYPE_GRAPHICS_REQUIREMENTS_OPENGL_ES_KHR;
    if (XR_FAILED(getReqs(gInstance, gSystem, &reqs))) return 0;

    if (!makeContext()) return 0;

    XrGraphicsBindingOpenGLESAndroidKHR binding;
    memset(&binding, 0, sizeof(binding));
    binding.type = XR_TYPE_GRAPHICS_BINDING_OPENGL_ES_ANDROID_KHR;
    binding.display = gEglDisplay;
    binding.config = gEglConfig;
    binding.context = gEglContext;

    XrSessionCreateInfo sci;
    memset(&sci, 0, sizeof(sci));
    sci.type = XR_TYPE_SESSION_CREATE_INFO;
    sci.next = &binding;
    sci.systemId = gSystem;
    if (XR_FAILED(xrCreateSession(gInstance, &sci, &gSession))) {
        gSession = XR_NULL_HANDLE;
        return 0;
    }

    // Floor-relative space, so the player's real height is used as it is.
    XrReferenceSpaceCreateInfo rci;
    memset(&rci, 0, sizeof(rci));
    rci.type = XR_TYPE_REFERENCE_SPACE_CREATE_INFO;
    rci.referenceSpaceType = XR_REFERENCE_SPACE_TYPE_STAGE;
    rci.poseInReferenceSpace.orientation.w = 1.0f;
    if (XR_SUCCEEDED(xrCreateReferenceSpace(gSession, &rci, &gSpace))) {
        gFloorSpace = 1;
    } else {
        rci.referenceSpaceType = XR_REFERENCE_SPACE_TYPE_LOCAL;
        if (XR_FAILED(xrCreateReferenceSpace(gSession, &rci, &gSpace))) return 0;
    }

    uint32_t cfgCount = 0;
    if (XR_FAILED(xrEnumerateViewConfigurations(gInstance, gSystem, 0, &cfgCount, NULL))) return 0;
    if (cfgCount > 8) cfgCount = 8;
    XrViewConfigurationType cfgs[8];
    if (XR_FAILED(xrEnumerateViewConfigurations(gInstance, gSystem, cfgCount, &cfgCount, cfgs))) return 0;
    gViewConfig = cfgs[0];
    for (uint32_t i = 0; i < cfgCount; i++) {
        if (cfgs[i] == XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO) gViewConfig = cfgs[i];
    }

    uint32_t blendCount = 0;
    if (XR_SUCCEEDED(xrEnumerateEnvironmentBlendModes(gInstance, gSystem, gViewConfig, 0, &blendCount, NULL)) && blendCount > 0) {
        if (blendCount > 4) blendCount = 4;
        XrEnvironmentBlendMode modes[4];
        if (XR_SUCCEEDED(xrEnumerateEnvironmentBlendModes(gInstance, gSystem, gViewConfig, blendCount, &blendCount, modes))) {
            gBlend = modes[0];
            for (uint32_t i = 0; i < blendCount; i++) {
                if (modes[i] == XR_ENVIRONMENT_BLEND_MODE_OPAQUE) gBlend = modes[i];
            }
        }
    }

    XrViewConfigurationView views[MAX_EYES];
    for (int i = 0; i < MAX_EYES; i++) {
        memset(&views[i], 0, sizeof(views[i]));
        views[i].type = XR_TYPE_VIEW_CONFIGURATION_VIEW;
    }
    uint32_t viewCount = 0;
    if (XR_FAILED(xrEnumerateViewConfigurationViews(gInstance, gSystem, gViewConfig, MAX_EYES, &viewCount, views))) return 0;
    if (viewCount < MAX_EYES) return 0;
    gSwapW = (int32_t)views[0].recommendedImageRectWidth;
    gSwapH = (int32_t)views[0].recommendedImageRectHeight;
    if (gSwapW < 1 || gSwapH < 1) return 0;

    for (int e = 0; e < MAX_EYES; e++) {
        XrSwapchainCreateInfo info;
        memset(&info, 0, sizeof(info));
        info.type = XR_TYPE_SWAPCHAIN_CREATE_INFO;
        info.usageFlags = XR_SWAPCHAIN_USAGE_COLOR_ATTACHMENT_BIT | XR_SWAPCHAIN_USAGE_SAMPLED_BIT;
        info.format = GL_RGBA8;
        info.sampleCount = 1;
        info.width = gSwapW;
        info.height = gSwapH;
        info.faceCount = 1;
        info.arraySize = 1;
        info.mipCount = 1;
        if (XR_FAILED(xrCreateSwapchain(gSession, &info, &gSwap[e]))) return 0;
        uint32_t count = 0;
        if (XR_FAILED(xrEnumerateSwapchainImages(gSwap[e], 0, &count, NULL))) return 0;
        if (count > MAX_SWAP_IMAGES) count = MAX_SWAP_IMAGES;
        if (count < 1) return 0;
        XrSwapchainImageOpenGLESKHR images[MAX_SWAP_IMAGES];
        for (uint32_t i = 0; i < count; i++) {
            memset(&images[i], 0, sizeof(images[i]));
            images[i].type = XR_TYPE_SWAPCHAIN_IMAGE_OPENGL_ES_KHR;
        }
        if (XR_FAILED(xrEnumerateSwapchainImages(gSwap[e], count, &count, (XrSwapchainImageBaseHeader *)images))) return 0;
        gSwapCount[e] = count;
        for (uint32_t i = 0; i < count; i++) gSwapImage[e][i] = images[i].image;
    }

    XrActionSetCreateInfo asci;
    memset(&asci, 0, sizeof(asci));
    asci.type = XR_TYPE_ACTION_SET_CREATE_INFO;
    strcpy(asci.actionSetName, "game");
    strcpy(asci.localizedActionSetName, "Game");
    if (XR_FAILED(xrCreateActionSet(gInstance, &asci, &gActionSet))) return 0;

    gHandPath[0] = pathOf("/user/hand/left");
    gHandPath[1] = pathOf("/user/hand/right");

    XrActionCreateInfo aci;
    memset(&aci, 0, sizeof(aci));
    aci.type = XR_TYPE_ACTION_CREATE_INFO;
    aci.actionType = XR_ACTION_TYPE_VECTOR2F_INPUT;
    aci.countSubactionPaths = MAX_EYES;
    aci.subactionPaths = gHandPath;
    strcpy(aci.actionName, "move");
    strcpy(aci.localizedActionName, "Move");
    if (XR_FAILED(xrCreateAction(gActionSet, &aci, &gMoveAction))) return 0;
    strcpy(aci.actionName, "turn");
    strcpy(aci.localizedActionName, "Turn");
    if (XR_FAILED(xrCreateAction(gActionSet, &aci, &gTurnAction))) return 0;

    suggestProfile("/interaction_profiles/oculus/touch_controller");
    suggestProfile("/interaction_profiles/bytedance/pico4_controller");
    suggestProfile("/interaction_profiles/bytedance/pico_neo3_controller");
    suggestProfile("/interaction_profiles/khr/simple_controller");

    XrSessionActionSetsAttachInfo attach;
    memset(&attach, 0, sizeof(attach));
    attach.type = XR_TYPE_SESSION_ACTION_SETS_ATTACH_INFO;
    attach.countActionSets = 1;
    attach.actionSets = &gActionSet;
    if (XR_FAILED(xrAttachSessionActionSets(gSession, &attach))) return 0;

    return 1;
}

static int pollEvents(void) {
    for (;;) {
        XrEventDataBuffer ev;
        memset(&ev, 0, sizeof(ev));
        ev.type = XR_TYPE_EVENT_DATA_BUFFER;
        XrResult r = xrPollEvent(gInstance, &ev);
        if (r == XR_EVENT_UNAVAILABLE) return 1;
        if (XR_FAILED(r)) return 0;
        if (ev.type == XR_TYPE_EVENT_DATA_SESSION_STATE_CHANGED) {
            XrEventDataSessionStateChanged *s = (XrEventDataSessionStateChanged *)&ev;
            gState = s->state;
            if (gState == XR_SESSION_STATE_READY && !gRunning) {
                XrSessionBeginInfo bi;
                memset(&bi, 0, sizeof(bi));
                bi.type = XR_TYPE_SESSION_BEGIN_INFO;
                bi.primaryViewConfigurationType = gViewConfig;
                if (XR_SUCCEEDED(xrBeginSession(gSession, &bi))) {
                    gRunning = 1;
                    LOGI("VR session running");
                }
            } else if (gState == XR_SESSION_STATE_STOPPING && gRunning) {
                gRunning = 0;
                xrEndSession(gSession);
            } else if (gState == XR_SESSION_STATE_EXITING || gState == XR_SESSION_STATE_LOSS_PENDING) {
                gShouldQuit = 1;
            }
        }
    }
}

static void releaseImages(void) {
    for (int e = 0; e < MAX_EYES; e++) {
        if (gAcquired > e) {
            XrSwapchainImageReleaseInfo ri;
            memset(&ri, 0, sizeof(ri));
            ri.type = XR_TYPE_SWAPCHAIN_IMAGE_RELEASE_INFO;
            xrReleaseSwapchainImage(gSwap[e], &ri);
        }
    }
    gAcquired = 0;
}

static void endFrameWith(int withLayers) {
    XrCompositionLayerProjection layer;
    XrCompositionLayerProjectionView pv[MAX_EYES];
    const XrCompositionLayerBaseHeader *layers[1];
    uint32_t layerCount = 0;
    memset(&layer, 0, sizeof(layer));
    memset(pv, 0, sizeof(pv));
    if (withLayers) {
        for (int e = 0; e < MAX_EYES; e++) {
            pv[e].type = XR_TYPE_COMPOSITION_LAYER_PROJECTION_VIEW;
            pv[e].pose = gEyePose[e];
            pv[e].fov = gEyeFov[e];
            pv[e].subImage.swapchain = gSwap[e];
            pv[e].subImage.imageRect.offset.x = 0;
            pv[e].subImage.imageRect.offset.y = 0;
            pv[e].subImage.imageRect.extent.width = gSwapW;
            pv[e].subImage.imageRect.extent.height = gSwapH;
            pv[e].subImage.imageArrayIndex = 0;
        }
        layer.type = XR_TYPE_COMPOSITION_LAYER_PROJECTION;
        layer.space = gSpace;
        layer.viewCount = MAX_EYES;
        layer.views = pv;
        layers[0] = (const XrCompositionLayerBaseHeader *)&layer;
        layerCount = 1;
    }
    XrFrameEndInfo fei;
    memset(&fei, 0, sizeof(fei));
    fei.type = XR_TYPE_FRAME_END_INFO;
    fei.displayTime = gDisplayTime;
    fei.environmentBlendMode = gBlend;
    fei.layerCount = layerCount;
    fei.layers = layerCount ? layers : NULL;
    xrEndFrame(gSession, &fei);
}

static float stickAxis(XrAction action, XrPath sub, int axis) {
    XrActionStateGetInfo gi;
    memset(&gi, 0, sizeof(gi));
    gi.type = XR_TYPE_ACTION_STATE_GET_INFO;
    gi.action = action;
    gi.subactionPath = sub;
    XrActionStateVector2f st;
    memset(&st, 0, sizeof(st));
    st.type = XR_TYPE_ACTION_STATE_VECTOR2F;
    if (XR_FAILED(xrGetActionStateVector2f(gSession, &gi, &st))) return 0.0f;
    if (!st.isActive) return 0.0f;
    return axis == 0 ? st.currentState.x : st.currentState.y;
}

static void teardown(void) {
    if (gSession != XR_NULL_HANDLE) {
        releaseImages();
        for (int e = 0; e < MAX_EYES; e++) {
            if (gSwap[e] != XR_NULL_HANDLE) {
                xrDestroySwapchain(gSwap[e]);
                gSwap[e] = XR_NULL_HANDLE;
            }
        }
        if (gSpace != XR_NULL_HANDLE) {
            xrDestroySpace(gSpace);
            gSpace = XR_NULL_HANDLE;
        }
        if (gActionSet != XR_NULL_HANDLE) {
            xrDestroyActionSet(gActionSet);
            gActionSet = XR_NULL_HANDLE;
        }
        gMoveAction = XR_NULL_HANDLE;
        gTurnAction = XR_NULL_HANDLE;
        xrDestroySession(gSession);
        gSession = XR_NULL_HANDLE;
    }
    if (gInstance != XR_NULL_HANDLE) {
        xrDestroyInstance(gInstance);
        gInstance = XR_NULL_HANDLE;
    }
    if (gEglDisplay != EGL_NO_DISPLAY) {
        eglMakeCurrent(gEglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        if (gEglSurface != EGL_NO_SURFACE) eglDestroySurface(gEglDisplay, gEglSurface);
        if (gEglContext != EGL_NO_CONTEXT) eglDestroyContext(gEglDisplay, gEglContext);
        eglTerminate(gEglDisplay);
        gEglDisplay = EGL_NO_DISPLAY;
        gEglSurface = EGL_NO_SURFACE;
        gEglContext = EGL_NO_CONTEXT;
    }
    gRunning = 0;
    gAcquired = 0;
    gState = XR_SESSION_STATE_UNKNOWN;
}

JNIEXPORT jboolean JNICALL Java_com_vrunity_vrapk_Xr_start(JNIEnv *env, jobject thiz, jobject activity) {
    if (gInstance != XR_NULL_HANDLE) return JNI_TRUE;
    gShouldQuit = 0;
    (*env)->GetJavaVM(env, &gVm);
    if (gActivity == NULL) gActivity = (*env)->NewGlobalRef(env, activity);
    if (!createInstance(env, activity)) {
        LOGE("No VR runtime available on this device");
        teardown();
        return JNI_FALSE;
    }
    if (!createSession()) {
        LOGE("Could not open a VR session");
        teardown();
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

// One frame of VR: waits for the runtime's frame, takes both eye images, and hands
// the game the view for each eye. 1 = draw this frame, 0 = nothing to draw yet,
// -1 = the session is over.
JNIEXPORT jint JNICALL Java_com_vrunity_vrapk_Xr_poll(JNIEnv *env, jobject thiz, jfloatArray out) {
    if (gSession == XR_NULL_HANDLE) return -1;
    if (!pollEvents() || gShouldQuit) return -1;
    if (!gRunning) {
        usleep(4000);
        return 0;
    }

    XrFrameWaitInfo waitInfo;
    memset(&waitInfo, 0, sizeof(waitInfo));
    waitInfo.type = XR_TYPE_FRAME_WAIT_INFO;
    XrFrameState frameState;
    memset(&frameState, 0, sizeof(frameState));
    frameState.type = XR_TYPE_FRAME_STATE;
    if (XR_FAILED(xrWaitFrame(gSession, &waitInfo, &frameState))) return -1;
    XrFrameBeginInfo beginInfo;
    memset(&beginInfo, 0, sizeof(beginInfo));
    beginInfo.type = XR_TYPE_FRAME_BEGIN_INFO;
    if (XR_FAILED(xrBeginFrame(gSession, &beginInfo))) return -1;
    gDisplayTime = frameState.predictedDisplayTime;

    if (!frameState.shouldRender) {
        endFrameWith(0);
        return 0;
    }

    XrViewLocateInfo li;
    memset(&li, 0, sizeof(li));
    li.type = XR_TYPE_VIEW_LOCATE_INFO;
    li.viewConfigurationType = gViewConfig;
    li.displayTime = frameState.predictedDisplayTime;
    li.space = gSpace;
    XrViewState vs;
    memset(&vs, 0, sizeof(vs));
    vs.type = XR_TYPE_VIEW_STATE;
    XrView views[MAX_EYES];
    for (int i = 0; i < MAX_EYES; i++) {
        memset(&views[i], 0, sizeof(views[i]));
        views[i].type = XR_TYPE_VIEW;
    }
    uint32_t viewCount = 0;
    if (XR_FAILED(xrLocateViews(gSession, &li, &vs, MAX_EYES, &viewCount, views)) || viewCount < MAX_EYES) {
        endFrameWith(0);
        return 0;
    }

    for (int e = 0; e < MAX_EYES; e++) {
        XrSwapchainImageAcquireInfo ai;
        memset(&ai, 0, sizeof(ai));
        ai.type = XR_TYPE_SWAPCHAIN_IMAGE_ACQUIRE_INFO;
        uint32_t idx = 0;
        if (XR_FAILED(xrAcquireSwapchainImage(gSwap[e], &ai, &idx))) {
            releaseImages();
            endFrameWith(0);
            return -1;
        }
        XrSwapchainImageWaitInfo wi;
        memset(&wi, 0, sizeof(wi));
        wi.type = XR_TYPE_SWAPCHAIN_IMAGE_WAIT_INFO;
        wi.timeout = XR_INFINITE_DURATION;
        if (XR_FAILED(xrWaitSwapchainImage(gSwap[e], &wi))) {
            releaseImages();
            endFrameWith(0);
            return -1;
        }
        gSwapIndex[e] = idx;
        gAcquired = e + 1;
    }

    float values[22];
    for (int e = 0; e < MAX_EYES; e++) {
        int o = e * 11;
        values[o] = views[e].fov.angleLeft;
        values[o + 1] = views[e].fov.angleRight;
        values[o + 2] = views[e].fov.angleUp;
        values[o + 3] = views[e].fov.angleDown;
        values[o + 4] = views[e].pose.position.x;
        values[o + 5] = views[e].pose.position.y;
        values[o + 6] = views[e].pose.position.z;
        values[o + 7] = views[e].pose.orientation.x;
        values[o + 8] = views[e].pose.orientation.y;
        values[o + 9] = views[e].pose.orientation.z;
        values[o + 10] = views[e].pose.orientation.w;
        gEyePose[e] = views[e].pose;
        gEyeFov[e] = views[e].fov;
    }
    jfloat *jout = (*env)->GetFloatArrayElements(env, out, NULL);
    if (jout != NULL) {
        memcpy(jout, values, sizeof(values));
        (*env)->ReleaseFloatArrayElements(env, out, jout, 0);
    }
    return 1;
}

JNIEXPORT void JNICALL Java_com_vrunity_vrapk_Xr_input(JNIEnv *env, jobject thiz, jfloatArray out) {
    float values[4] = { 0.0f, 0.0f, 0.0f, 0.0f };
    if (gSession != XR_NULL_HANDLE && gActionSet != XR_NULL_HANDLE && gRunning) {
        XrActiveActionSet active;
        memset(&active, 0, sizeof(active));
        active.actionSet = gActionSet;
        active.subactionPath = XR_NULL_PATH;
        XrActionsSyncInfo si;
        memset(&si, 0, sizeof(si));
        si.type = XR_TYPE_ACTIONS_SYNC_INFO;
        si.countActiveActionSets = 1;
        si.activeActionSets = &active;
        if (XR_SUCCEEDED(xrSyncActions(gSession, &si))) {
            values[0] = stickAxis(gMoveAction, gHandPath[0], 0);
            values[1] = stickAxis(gMoveAction, gHandPath[0], 1);
            values[2] = stickAxis(gTurnAction, gHandPath[1], 0);
            values[3] = stickAxis(gTurnAction, gHandPath[1], 1);
        }
    }
    jfloat *jout = (*env)->GetFloatArrayElements(env, out, NULL);
    if (jout != NULL) {
        memcpy(jout, values, sizeof(values));
        (*env)->ReleaseFloatArrayElements(env, out, jout, 0);
    }
}

JNIEXPORT jint JNICALL Java_com_vrunity_vrapk_Xr_eyeTexture(JNIEnv *env, jobject thiz, jint eye) {
    if (eye < 0 || eye >= MAX_EYES || gAcquired <= eye) return 0;
    return (jint)gSwapImage[eye][gSwapIndex[eye]];
}

JNIEXPORT jint JNICALL Java_com_vrunity_vrapk_Xr_eyeWidth(JNIEnv *env, jobject thiz) {
    return gSwapW;
}

JNIEXPORT jint JNICALL Java_com_vrunity_vrapk_Xr_eyeHeight(JNIEnv *env, jobject thiz) {
    return gSwapH;
}

JNIEXPORT jboolean JNICALL Java_com_vrunity_vrapk_Xr_floorSpace(JNIEnv *env, jobject thiz) {
    return gFloorSpace ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL Java_com_vrunity_vrapk_Xr_endFrame(JNIEnv *env, jobject thiz) {
    if (gSession == XR_NULL_HANDLE || gAcquired < MAX_EYES) return -1;
    // The frame has to reach the runtime while it still owns the eye images that the
    // frame points at. Releasing them first leaves the compositor with a frame it
    // cannot show, which is a black headset however well the scene was drawn.
    endFrameWith(1);
    releaseImages();
    if (gShouldQuit || !gRunning) return -1;
    return 1;
}

JNIEXPORT void JNICALL Java_com_vrunity_vrapk_Xr_stop(JNIEnv *env, jobject thiz) {
    teardown();
    if (gActivity != NULL) {
        if (gVm != NULL) {
            JNIEnv *e = NULL;
            if ((*gVm)->GetEnv(gVm, (void **)&e, JNI_VERSION_1_6) == JNI_OK) (*e)->DeleteGlobalRef(e, gActivity);
        }
        gActivity = NULL;
    }
}
