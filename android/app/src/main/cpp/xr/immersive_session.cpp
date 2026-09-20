// The immersive half of the hybrid app: an OpenXR session that shows one
// composition layer fed by an Android Surface.
//
// There are no draw calls here. A swapchain created through
// xrCreateSwapchainAndroidSurfaceKHR is written by whatever owns the Surface --
// MediaCodec under mpv, or a Canvas while bringing this up -- and the spec
// forbids the application from acquiring or releasing its images. So the frame
// loop's whole job is to wait, then submit a layer that points at it. The EGL
// context below exists only because a session needs a graphics binding; it
// never renders.
//
// Progress is reported through logcat because the VR compositor cannot be
// screen-captured on Horizon OS, which leaves no other feedback channel.

#include <android/log.h>
#include <android/native_window_jni.h>
#include <jni.h>

#include <atomic>
#include <cmath>
#include <condition_variable>
#include <cstring>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include <EGL/egl.h>
#include <GLES3/gl3.h>

#define XR_USE_PLATFORM_ANDROID
#define XR_USE_GRAPHICS_API_OPENGL_ES
#include <openxr/openxr.h>
#include <openxr/openxr_platform.h>

#define LOG_TAG "PlezyXR"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// Screen geometry. A cylinder rather than a flat quad because a wide flat panel
// at close range forces the eyes to refocus toward its edges; curving it keeps
// every point equidistant. These are the starting values -- the whole point of
// getting this on a head is to find out whether they feel right.
constexpr float kScreenRadiusMeters = 4.0f;
constexpr float kScreenCentralAngleRadians = 1.22173f;  // 70 degrees
constexpr float kScreenAspectRatio = 16.0f / 9.0f;
constexpr float kScreenHeightOffsetMeters = 0.0f;

struct Session {
  JavaVM* vm = nullptr;
  jobject activity = nullptr;  // global ref

  XrInstance instance = XR_NULL_HANDLE;
  XrSystemId systemId = XR_NULL_SYSTEM_ID;
  XrSession session = XR_NULL_HANDLE;
  XrSpace space = XR_NULL_HANDLE;
  XrSwapchain swapchain = XR_NULL_HANDLE;
  XrSessionState state = XR_SESSION_STATE_UNKNOWN;

  // Controller input. The Touch controllers are not Android input devices --
  // dumpsys lists only the audio card and the power keys -- so they exist for
  // this app solely through OpenXR's action system.
  XrActionSet actionSet = XR_NULL_HANDLE;
  XrAction playPauseAction = XR_NULL_HANDLE;
  XrAction exitAction = XR_NULL_HANDLE;
  XrAction seekAction = XR_NULL_HANDLE;
  bool actionsAttached = false;
  // Rising-edge latch for the stick, so holding it does not spray seeks.
  int lastSeekDirection = 0;
  bool running = false;  // between xrBeginSession and xrEndSession

  EGLDisplay eglDisplay = EGL_NO_DISPLAY;
  EGLContext eglContext = EGL_NO_CONTEXT;
  EGLSurface eglSurface = EGL_NO_SURFACE;
  EGLConfig eglConfig = nullptr;

  int32_t width = 0;
  int32_t height = 0;

  std::thread thread;
  std::atomic<bool> quit{false};

  // The Surface handed back to Kotlin, published once the swapchain exists.
  std::mutex surfaceMutex;
  std::condition_variable surfaceReady;
  jobject surface = nullptr;  // global ref
  bool surfaceResolved = false;  // set even on failure, so callers stop waiting
};

Session g_session;

bool InitializeLoader(JavaVM* vm, jobject activity) {
  PFN_xrInitializeLoaderKHR initializeLoader = nullptr;
  if (XR_FAILED(xrGetInstanceProcAddr(XR_NULL_HANDLE, "xrInitializeLoaderKHR",
                                      reinterpret_cast<PFN_xrVoidFunction*>(&initializeLoader)))) {
    LOGE("xrInitializeLoaderKHR unavailable");
    return false;
  }
  XrLoaderInitInfoAndroidKHR init{XR_TYPE_LOADER_INIT_INFO_ANDROID_KHR};
  init.applicationVM = vm;
  init.applicationContext = activity;
  return XR_SUCCEEDED(initializeLoader(reinterpret_cast<const XrLoaderInitInfoBaseHeaderKHR*>(&init)));
}

bool CreateEgl(Session& s) {
  s.eglDisplay = eglGetDisplay(EGL_DEFAULT_DISPLAY);
  if (s.eglDisplay == EGL_NO_DISPLAY || !eglInitialize(s.eglDisplay, nullptr, nullptr)) {
    LOGE("eglInitialize failed");
    return false;
  }
  const EGLint configAttribs[] = {EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT, EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
                                  EGL_RED_SIZE,  8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8,
                                  EGL_NONE};
  EGLint configCount = 0;
  if (!eglChooseConfig(s.eglDisplay, configAttribs, &s.eglConfig, 1, &configCount) || configCount == 0) {
    LOGE("eglChooseConfig found no ES3 config");
    return false;
  }
  const EGLint contextAttribs[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
  s.eglContext = eglCreateContext(s.eglDisplay, s.eglConfig, EGL_NO_CONTEXT, contextAttribs);
  if (s.eglContext == EGL_NO_CONTEXT) {
    LOGE("eglCreateContext failed");
    return false;
  }
  // A 1x1 pbuffer purely so the context can be made current; nothing is drawn
  // into it. The runtime composites the surface swapchain directly.
  const EGLint surfaceAttribs[] = {EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE};
  s.eglSurface = eglCreatePbufferSurface(s.eglDisplay, s.eglConfig, surfaceAttribs);
  if (s.eglSurface == EGL_NO_SURFACE || !eglMakeCurrent(s.eglDisplay, s.eglSurface, s.eglSurface, s.eglContext)) {
    LOGE("could not make the EGL context current");
    return false;
  }
  return true;
}

void DestroyEgl(Session& s) {
  if (s.eglDisplay == EGL_NO_DISPLAY) return;
  eglMakeCurrent(s.eglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
  if (s.eglSurface != EGL_NO_SURFACE) eglDestroySurface(s.eglDisplay, s.eglSurface);
  if (s.eglContext != EGL_NO_CONTEXT) eglDestroyContext(s.eglDisplay, s.eglContext);
  eglTerminate(s.eglDisplay);
  s.eglDisplay = EGL_NO_DISPLAY;
  s.eglContext = EGL_NO_CONTEXT;
  s.eglSurface = EGL_NO_SURFACE;
}

bool CreateInstanceAndSystem(Session& s) {
  const char* extensions[] = {
      XR_KHR_ANDROID_CREATE_INSTANCE_EXTENSION_NAME,
      XR_KHR_OPENGL_ES_ENABLE_EXTENSION_NAME,
      "XR_KHR_android_surface_swapchain",
      "XR_KHR_composition_layer_cylinder",
      // Corrects the origin mismatch between Surface producers and the
      // compositor; see the layout chained onto the layer in SubmitFrame.
      "XR_FB_composition_layer_image_layout",
  };

  XrInstanceCreateInfoAndroidKHR androidInfo{XR_TYPE_INSTANCE_CREATE_INFO_ANDROID_KHR};
  androidInfo.applicationVM = s.vm;
  androidInfo.applicationActivity = s.activity;

  XrInstanceCreateInfo createInfo{XR_TYPE_INSTANCE_CREATE_INFO};
  createInfo.next = &androidInfo;
  createInfo.enabledExtensionCount = sizeof(extensions) / sizeof(extensions[0]);
  createInfo.enabledExtensionNames = extensions;
  std::strncpy(createInfo.applicationInfo.applicationName, "Plezy", XR_MAX_APPLICATION_NAME_SIZE - 1);
  createInfo.applicationInfo.applicationVersion = 1;
  std::strncpy(createInfo.applicationInfo.engineName, "plezy_xr", XR_MAX_ENGINE_NAME_SIZE - 1);
  createInfo.applicationInfo.apiVersion = XR_CURRENT_API_VERSION;

  XrResult result = xrCreateInstance(&createInfo, &s.instance);
  if (XR_FAILED(result)) {
    LOGE("xrCreateInstance failed: %d", result);
    return false;
  }

  XrSystemGetInfo systemInfo{XR_TYPE_SYSTEM_GET_INFO};
  systemInfo.formFactor = XR_FORM_FACTOR_HEAD_MOUNTED_DISPLAY;
  result = xrGetSystem(s.instance, &systemInfo, &s.systemId);
  if (XR_FAILED(result)) {
    LOGE("xrGetSystem failed: %d", result);
    return false;
  }
  return true;
}

bool CreateSession(Session& s) {
  // Required before session creation even though the returned versions are not
  // otherwise used; runtimes reject the session if it is skipped.
  PFN_xrGetOpenGLESGraphicsRequirementsKHR getRequirements = nullptr;
  if (XR_SUCCEEDED(xrGetInstanceProcAddr(s.instance, "xrGetOpenGLESGraphicsRequirementsKHR",
                                         reinterpret_cast<PFN_xrVoidFunction*>(&getRequirements))) &&
      getRequirements != nullptr) {
    XrGraphicsRequirementsOpenGLESKHR requirements{XR_TYPE_GRAPHICS_REQUIREMENTS_OPENGL_ES_KHR};
    getRequirements(s.instance, s.systemId, &requirements);
  } else {
    LOGW("xrGetOpenGLESGraphicsRequirementsKHR unavailable");
  }

  XrGraphicsBindingOpenGLESAndroidKHR binding{XR_TYPE_GRAPHICS_BINDING_OPENGL_ES_ANDROID_KHR};
  binding.display = s.eglDisplay;
  binding.config = s.eglConfig;
  binding.context = s.eglContext;

  XrSessionCreateInfo createInfo{XR_TYPE_SESSION_CREATE_INFO};
  createInfo.next = &binding;
  createInfo.systemId = s.systemId;
  XrResult result = xrCreateSession(s.instance, &createInfo, &s.session);
  if (XR_FAILED(result)) {
    LOGE("xrCreateSession failed: %d", result);
    return false;
  }

  // LOCAL keeps the screen fixed relative to where the viewer was when playback
  // started, which is what a cinema screen should do; STAGE would tie it to the
  // room's floor and guardian instead.
  XrReferenceSpaceCreateInfo spaceInfo{XR_TYPE_REFERENCE_SPACE_CREATE_INFO};
  spaceInfo.referenceSpaceType = XR_REFERENCE_SPACE_TYPE_LOCAL;
  spaceInfo.poseInReferenceSpace.orientation.w = 1.0f;
  result = xrCreateReferenceSpace(s.session, &spaceInfo, &s.space);
  if (XR_FAILED(result)) {
    LOGE("xrCreateReferenceSpace failed: %d", result);
    return false;
  }
  return true;
}

// Action codes shared with ImmersiveSession.onInputFromNative.
constexpr int kInputPlayPause = 1;
constexpr int kInputSeekBackward = 2;
constexpr int kInputSeekForward = 3;
constexpr int kInputExit = 4;

XrPath ToPath(XrInstance instance, const char* text) {
  XrPath path = XR_NULL_PATH;
  xrStringToPath(instance, text, &path);
  return path;
}

bool CreateInput(Session& s) {
  XrActionSetCreateInfo setInfo{XR_TYPE_ACTION_SET_CREATE_INFO};
  std::strncpy(setInfo.actionSetName, "player", XR_MAX_ACTION_SET_NAME_SIZE - 1);
  std::strncpy(setInfo.localizedActionSetName, "Player", XR_MAX_LOCALIZED_ACTION_SET_NAME_SIZE - 1);
  if (XR_FAILED(xrCreateActionSet(s.instance, &setInfo, &s.actionSet))) {
    LOGE("xrCreateActionSet failed");
    return false;
  }

  const auto createAction = [&](const char* name, const char* localized, XrActionType type, XrAction* out) {
    XrActionCreateInfo info{XR_TYPE_ACTION_CREATE_INFO};
    std::strncpy(info.actionName, name, XR_MAX_ACTION_NAME_SIZE - 1);
    std::strncpy(info.localizedActionName, localized, XR_MAX_LOCALIZED_ACTION_NAME_SIZE - 1);
    info.actionType = type;
    return XR_SUCCEEDED(xrCreateAction(s.actionSet, &info, out));
  };

  if (!createAction("play_pause", "Play or pause", XR_ACTION_TYPE_BOOLEAN_INPUT, &s.playPauseAction) ||
      !createAction("exit_immersive", "Leave the big screen", XR_ACTION_TYPE_BOOLEAN_INPUT, &s.exitAction) ||
      !createAction("seek", "Seek", XR_ACTION_TYPE_VECTOR2F_INPUT, &s.seekAction)) {
    LOGE("could not create the player actions");
    return false;
  }

  // Both hands are bound for every action: a viewer lying on a sofa should not
  // have to work out which controller the app decided to listen to.
  const std::vector<XrActionSuggestedBinding> bindings{
      {s.playPauseAction, ToPath(s.instance, "/user/hand/right/input/a/click")},
      {s.playPauseAction, ToPath(s.instance, "/user/hand/left/input/x/click")},
      {s.exitAction, ToPath(s.instance, "/user/hand/right/input/b/click")},
      {s.exitAction, ToPath(s.instance, "/user/hand/left/input/y/click")},
      {s.seekAction, ToPath(s.instance, "/user/hand/right/input/thumbstick")},
      {s.seekAction, ToPath(s.instance, "/user/hand/left/input/thumbstick")},
  };

  XrInteractionProfileSuggestedBinding suggested{XR_TYPE_INTERACTION_PROFILE_SUGGESTED_BINDING};
  suggested.interactionProfile = ToPath(s.instance, "/interaction_profiles/oculus/touch_controller");
  suggested.countSuggestedBindings = static_cast<uint32_t>(bindings.size());
  suggested.suggestedBindings = bindings.data();
  const XrResult suggestResult = xrSuggestInteractionProfileBindings(s.instance, &suggested);
  if (XR_FAILED(suggestResult)) {
    LOGE("xrSuggestInteractionProfileBindings failed: %d", suggestResult);
    return false;
  }

  XrSessionActionSetsAttachInfo attachInfo{XR_TYPE_SESSION_ACTION_SETS_ATTACH_INFO};
  attachInfo.countActionSets = 1;
  attachInfo.actionSets = &s.actionSet;
  const XrResult attachResult = xrAttachSessionActionSets(s.session, &attachInfo);
  if (XR_FAILED(attachResult)) {
    LOGE("xrAttachSessionActionSets failed: %d", attachResult);
    return false;
  }
  s.actionsAttached = true;
  LOGI("controller actions attached");
  return true;
}

bool CreateSurfaceSwapchain(Session& s, JNIEnv* env) {
  PFN_xrCreateSwapchainAndroidSurfaceKHR createSurfaceSwapchain = nullptr;
  if (XR_FAILED(xrGetInstanceProcAddr(s.instance, "xrCreateSwapchainAndroidSurfaceKHR",
                                      reinterpret_cast<PFN_xrVoidFunction*>(&createSurfaceSwapchain))) ||
      createSurfaceSwapchain == nullptr) {
    LOGE("xrCreateSwapchainAndroidSurfaceKHR unavailable");
    return false;
  }

  // format, sampleCount, faceCount, arraySize and mipCount must be *zero* here,
  // not the 1 an ordinary swapchain takes: the Android Surface owns those
  // properties, so the application is forbidden from stating them. Passing 1
  // returns XR_ERROR_VALIDATION_FAILURE.
  XrSwapchainCreateInfo createInfo{XR_TYPE_SWAPCHAIN_CREATE_INFO};
  createInfo.usageFlags = XR_SWAPCHAIN_USAGE_SAMPLED_BIT | XR_SWAPCHAIN_USAGE_COLOR_ATTACHMENT_BIT;
  createInfo.width = static_cast<uint32_t>(s.width);
  createInfo.height = static_cast<uint32_t>(s.height);
  createInfo.format = 0;
  createInfo.faceCount = 0;
  createInfo.arraySize = 0;
  createInfo.mipCount = 0;
  createInfo.sampleCount = 0;

  jobject localSurface = nullptr;
  const XrResult result = createSurfaceSwapchain(s.session, &createInfo, &s.swapchain, &localSurface);
  if (XR_FAILED(result) || localSurface == nullptr) {
    LOGE("xrCreateSwapchainAndroidSurfaceKHR failed: %d", result);
    return false;
  }
  s.surface = env->NewGlobalRef(localSurface);
  LOGI("surface swapchain ready %dx%d", s.width, s.height);
  return true;
}

void PublishSurface(Session& s) {
  {
    std::lock_guard<std::mutex> lock(s.surfaceMutex);
    s.surfaceResolved = true;
  }
  s.surfaceReady.notify_all();
}

// Drains the event queue, driving the session state machine.
void PollEvents(Session& s) {
  XrEventDataBuffer event{XR_TYPE_EVENT_DATA_BUFFER};
  while (true) {
    event = {XR_TYPE_EVENT_DATA_BUFFER};
    if (xrPollEvent(s.instance, &event) != XR_SUCCESS) break;
    if (event.type != XR_TYPE_EVENT_DATA_SESSION_STATE_CHANGED) continue;

    const auto& changed = *reinterpret_cast<XrEventDataSessionStateChanged*>(&event);
    s.state = changed.state;
    LOGI("session state -> %d", static_cast<int>(s.state));

    if (s.state == XR_SESSION_STATE_READY) {
      XrSessionBeginInfo beginInfo{XR_TYPE_SESSION_BEGIN_INFO};
      beginInfo.primaryViewConfigurationType = XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO;
      const XrResult begun = xrBeginSession(s.session, &beginInfo);
      if (XR_SUCCEEDED(begun)) {
        s.running = true;
        LOGI("session begun");
      } else {
        LOGE("xrBeginSession failed: %d", begun);
      }
    } else if (s.state == XR_SESSION_STATE_STOPPING) {
      xrEndSession(s.session);
      s.running = false;
      LOGI("session ended");
      // The runtime stops the session when the viewer leaves the app -- the
      // Meta button, taking the headset off, the system menu. Treat it as an
      // exit and let the activity finish, rather than sitting idle forever
      // waiting for a READY that only comes back if they return.
      s.quit = true;
    } else if (s.state == XR_SESSION_STATE_EXITING || s.state == XR_SESSION_STATE_LOSS_PENDING) {
      s.quit = true;
    }
  }
}

void DispatchInput(JNIEnv* env, int action);

// Reads the controllers once per frame and reports button edges.
//
// Only while focused: an unfocused session still syncs, but the runtime is
// giving the controllers to the system menu, and a stray press there must not
// reach playback.
void PollInput(Session& s, JNIEnv* env) {
  if (!s.actionsAttached || s.state != XR_SESSION_STATE_FOCUSED) return;

  XrActiveActionSet active{s.actionSet, XR_NULL_PATH};
  XrActionsSyncInfo syncInfo{XR_TYPE_ACTIONS_SYNC_INFO};
  syncInfo.countActiveActionSets = 1;
  syncInfo.activeActionSets = &active;
  if (XR_FAILED(xrSyncActions(s.session, &syncInfo))) return;

  const auto pressed = [&](XrAction action) {
    XrActionStateGetInfo getInfo{XR_TYPE_ACTION_STATE_GET_INFO};
    getInfo.action = action;
    XrActionStateBoolean state{XR_TYPE_ACTION_STATE_BOOLEAN};
    if (XR_FAILED(xrGetActionStateBoolean(s.session, &getInfo, &state))) return false;
    // changedSinceLastSync is what makes this a press rather than a hold.
    return state.isActive == XR_TRUE && state.currentState == XR_TRUE && state.changedSinceLastSync == XR_TRUE;
  };

  if (pressed(s.playPauseAction)) DispatchInput(env, kInputPlayPause);
  if (pressed(s.exitAction)) DispatchInput(env, kInputExit);

  XrActionStateGetInfo seekInfo{XR_TYPE_ACTION_STATE_GET_INFO};
  seekInfo.action = s.seekAction;
  XrActionStateVector2f seek{XR_TYPE_ACTION_STATE_VECTOR2F};
  if (XR_SUCCEEDED(xrGetActionStateVector2f(s.session, &seekInfo, &seek)) && seek.isActive == XR_TRUE) {
    // Generous deadzone: a thumb resting on the stick should not scrub the
    // film, and the edge latch means one push is one seek however long it
    // is held.
    constexpr float kDeadzone = 0.6f;
    const int direction = seek.currentState.x > kDeadzone ? 1 : (seek.currentState.x < -kDeadzone ? -1 : 0);
    if (direction != s.lastSeekDirection) {
      if (direction > 0) DispatchInput(env, kInputSeekForward);
      if (direction < 0) DispatchInput(env, kInputSeekBackward);
      s.lastSeekDirection = direction;
    }
  }
}

void SubmitFrame(Session& s) {
  XrFrameWaitInfo waitInfo{XR_TYPE_FRAME_WAIT_INFO};
  XrFrameState frameState{XR_TYPE_FRAME_STATE};
  if (XR_FAILED(xrWaitFrame(s.session, &waitInfo, &frameState))) return;

  XrFrameBeginInfo beginInfo{XR_TYPE_FRAME_BEGIN_INFO};
  if (XR_FAILED(xrBeginFrame(s.session, &beginInfo))) return;

  // An Android Surface producer -- Canvas, MediaCodec, anything -- writes with
  // the origin at the top left, while the compositor samples from the bottom
  // left. Without this the picture arrives flipped top to bottom, which reads
  // as upside down *and* mirrored.
  XrCompositionLayerImageLayoutFB imageLayout{XR_TYPE_COMPOSITION_LAYER_IMAGE_LAYOUT_FB};
  imageLayout.flags = XR_COMPOSITION_LAYER_IMAGE_LAYOUT_VERTICAL_FLIP_BIT_FB;

  XrCompositionLayerCylinderKHR cylinder{XR_TYPE_COMPOSITION_LAYER_CYLINDER_KHR};
  cylinder.next = &imageLayout;
  cylinder.layerFlags = XR_COMPOSITION_LAYER_BLEND_TEXTURE_SOURCE_ALPHA_BIT;
  cylinder.space = s.space;
  cylinder.eyeVisibility = XR_EYE_VISIBILITY_BOTH;
  cylinder.subImage.swapchain = s.swapchain;
  cylinder.subImage.imageRect.offset = {0, 0};
  cylinder.subImage.imageRect.extent = {s.width, s.height};
  cylinder.subImage.imageArrayIndex = 0;
  // The cylinder is centred on the viewer, so the screen sits one radius away
  // along -Z and every point of it is the same distance from the eyes.
  cylinder.pose.orientation.w = 1.0f;
  cylinder.pose.position = {0.0f, kScreenHeightOffsetMeters, 0.0f};
  cylinder.radius = kScreenRadiusMeters;
  cylinder.centralAngle = kScreenCentralAngleRadians;
  cylinder.aspectRatio = kScreenAspectRatio;

  const XrCompositionLayerBaseHeader* layers[] = {reinterpret_cast<XrCompositionLayerBaseHeader*>(&cylinder)};

  XrFrameEndInfo endInfo{XR_TYPE_FRAME_END_INFO};
  endInfo.displayTime = frameState.predictedDisplayTime;
  endInfo.environmentBlendMode = XR_ENVIRONMENT_BLEND_MODE_OPAQUE;
  // shouldRender is false while the session is visible but occluded (the system
  // menu is up); submitting no layers then is what the spec asks for.
  endInfo.layerCount = frameState.shouldRender ? 1 : 0;
  endInfo.layers = frameState.shouldRender ? layers : nullptr;
  xrEndFrame(s.session, &endInfo);
}

// Tells Kotlin the session is over so the activity can finish and give the
// viewer back to the panel. Without this the frame loop stops while the
// activity stays up showing nothing, and the only way out is killing the app.
void NotifySessionEnded(JNIEnv* env) {
  jclass cls = env->FindClass("com/edde746/plezy/xr/ImmersiveSession");
  if (cls == nullptr) {
    env->ExceptionClear();
    LOGE("could not find ImmersiveSession to report the session end");
    return;
  }
  jmethodID method = env->GetStaticMethodID(cls, "onSessionEndedFromNative", "()V");
  if (method == nullptr) {
    env->ExceptionClear();
    LOGE("could not find onSessionEndedFromNative");
    env->DeleteLocalRef(cls);
    return;
  }
  env->CallStaticVoidMethod(cls, method);
  if (env->ExceptionCheck()) env->ExceptionClear();
  env->DeleteLocalRef(cls);
}

void DispatchInput(JNIEnv* env, int action) {
  jclass cls = env->FindClass("com/edde746/plezy/xr/ImmersiveSession");
  if (cls == nullptr) {
    env->ExceptionClear();
    return;
  }
  jmethodID method = env->GetStaticMethodID(cls, "onInputFromNative", "(I)V");
  if (method == nullptr) {
    env->ExceptionClear();
    env->DeleteLocalRef(cls);
    return;
  }
  env->CallStaticVoidMethod(cls, method, static_cast<jint>(action));
  if (env->ExceptionCheck()) env->ExceptionClear();
  env->DeleteLocalRef(cls);
}

void RunSession(Session& s) {
  JNIEnv* env = nullptr;
  if (s.vm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
    LOGE("could not attach the session thread to the VM");
    PublishSurface(s);
    return;
  }

  const bool ready = InitializeLoader(s.vm, s.activity) && CreateEgl(s) && CreateInstanceAndSystem(s) &&
                     CreateSession(s) && CreateSurfaceSwapchain(s, env);
  // Input is not fatal: a viewer can still watch without the controllers, so a
  // failure here logs and leaves the screen running.
  if (ready) CreateInput(s);
  // Unblocks the caller whether or not setup worked; a null surface is how it
  // learns that it failed.
  PublishSurface(s);

  if (ready) {
    LOGI("entering frame loop");
    uint64_t frames = 0;
    while (!s.quit) {
      PollEvents(s);
      if (s.running) {
        PollInput(s, env);
        SubmitFrame(s);
        if (++frames % 300 == 0) LOGI("submitted %llu frames", static_cast<unsigned long long>(frames));
      } else {
        // Idle until the runtime moves the session to READY.
        std::this_thread::sleep_for(std::chrono::milliseconds(20));
      }
    }
    LOGI("frame loop finished after %llu frames", static_cast<unsigned long long>(frames));
  }

  if (s.running) xrEndSession(s.session);
  if (s.actionSet != XR_NULL_HANDLE) xrDestroyActionSet(s.actionSet);
  if (s.swapchain != XR_NULL_HANDLE) xrDestroySwapchain(s.swapchain);
  if (s.space != XR_NULL_HANDLE) xrDestroySpace(s.space);
  if (s.session != XR_NULL_HANDLE) xrDestroySession(s.session);
  if (s.instance != XR_NULL_HANDLE) xrDestroyInstance(s.instance);
  DestroyEgl(s);
  if (s.surface != nullptr) env->DeleteGlobalRef(s.surface);
  if (s.activity != nullptr) env->DeleteGlobalRef(s.activity);
  s.surface = nullptr;
  s.activity = nullptr;
  s.instance = XR_NULL_HANDLE;
  s.session = XR_NULL_HANDLE;
  s.swapchain = XR_NULL_HANDLE;
  s.space = XR_NULL_HANDLE;
  NotifySessionEnded(env);
  s.vm->DetachCurrentThread();
  LOGI("session torn down");
}

}  // namespace

extern "C" JNIEXPORT jobject JNICALL Java_com_edde746_plezy_xr_ImmersiveSession_nativeStart(
    JNIEnv* env, jclass, jobject activity, jint width, jint height) {
  Session& s = g_session;
  if (s.thread.joinable()) {
    LOGW("a session is already running");
    return nullptr;
  }
  env->GetJavaVM(&s.vm);
  s.activity = env->NewGlobalRef(activity);
  s.width = width;
  s.height = height;
  s.quit = false;
  s.surfaceResolved = false;
  s.surface = nullptr;
  s.thread = std::thread([&s]() { RunSession(s); });

  std::unique_lock<std::mutex> lock(s.surfaceMutex);
  s.surfaceReady.wait(lock, [&s]() { return s.surfaceResolved; });
  return s.surface;  // null when setup failed
}

extern "C" JNIEXPORT void JNICALL Java_com_edde746_plezy_xr_ImmersiveSession_nativeStop(JNIEnv*, jclass) {
  Session& s = g_session;
  s.quit = true;
  if (s.thread.joinable()) s.thread.join();
}
