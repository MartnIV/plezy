// Step 1 of the immersive player: prove the OpenXR runtime is reachable from
// this process and report what it offers.
//
// Everything here is observable through logcat, which matters because the VR
// compositor cannot be screen-captured (`adb shell screencap` returns black on
// Horizon OS), so the later rendering work has no visual feedback channel from
// a remote shell. This probe establishes the loader wiring, the Android
// instance-creation path and the extension set before any of that is built on
// top of it.

#include <android/log.h>
#include <jni.h>

#include <cstring>
#include <string>
#include <vector>

// openxr_platform.h declares the GLES binding structs in terms of EGL types, so
// these have to land first or it fails to compile on EGLenum.
#include <EGL/egl.h>
#include <GLES3/gl3.h>

#define XR_USE_PLATFORM_ANDROID
#define XR_USE_GRAPHICS_API_OPENGL_ES
#include <openxr/openxr.h>
#include <openxr/openxr_platform.h>

#define LOG_TAG "PlezyXR"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// The extension that makes the whole design work: it hands back an Android
// Surface backed by a compositor swapchain, so mpv can decode straight into an
// XR layer instead of into a SurfaceView the panel owns.
constexpr const char* kSurfaceSwapchainExt = "XR_KHR_android_surface_swapchain";

bool InitializeLoader(JavaVM* vm, jobject activity) {
  PFN_xrInitializeLoaderKHR initializeLoader = nullptr;
  if (XR_FAILED(xrGetInstanceProcAddr(XR_NULL_HANDLE, "xrInitializeLoaderKHR",
                                      reinterpret_cast<PFN_xrVoidFunction*>(&initializeLoader)))) {
    LOGE("xrInitializeLoaderKHR unavailable; loader cannot start on Android");
    return false;
  }
  XrLoaderInitInfoAndroidKHR init{XR_TYPE_LOADER_INIT_INFO_ANDROID_KHR};
  init.applicationVM = vm;
  init.applicationContext = activity;
  const XrResult result = initializeLoader(reinterpret_cast<const XrLoaderInitInfoBaseHeaderKHR*>(&init));
  if (XR_FAILED(result)) {
    LOGE("xrInitializeLoaderKHR failed: %d", result);
    return false;
  }
  return true;
}

}  // namespace

extern "C" JNIEXPORT void JNICALL
Java_com_edde746_plezy_xr_XrProbe_nativeProbe(JNIEnv* env, jclass, jobject activity) {
  JavaVM* vm = nullptr;
  env->GetJavaVM(&vm);
  if (!InitializeLoader(vm, activity)) return;

  uint32_t extensionCount = 0;
  if (XR_FAILED(xrEnumerateInstanceExtensionProperties(nullptr, 0, &extensionCount, nullptr))) {
    LOGE("could not count instance extensions");
    return;
  }
  std::vector<XrExtensionProperties> extensions(extensionCount, {XR_TYPE_EXTENSION_PROPERTIES});
  if (XR_FAILED(xrEnumerateInstanceExtensionProperties(nullptr, extensionCount, &extensionCount, extensions.data()))) {
    LOGE("could not enumerate instance extensions");
    return;
  }
  LOGI("runtime exposes %u instance extensions", extensionCount);
  // Dumped in full because the capability flags below only answer questions
  // already thought of; the design of the player depends on what else is here
  // (stereo/MV-HEVC layers, colour space, alpha blending, passthrough naming).
  for (const auto& extension : extensions) {
    LOGI("  ext %s v%u", extension.extensionName, extension.extensionVersion);
  }

  bool hasSurfaceSwapchain = false;
  bool hasAndroidCreate = false;
  bool hasCompositionLayerCylinder = false;
  bool hasCompositionLayerEquirect = false;
  bool hasPassthrough = false;
  for (const auto& extension : extensions) {
    if (std::strcmp(extension.extensionName, kSurfaceSwapchainExt) == 0) hasSurfaceSwapchain = true;
    if (std::strcmp(extension.extensionName, XR_KHR_ANDROID_CREATE_INSTANCE_EXTENSION_NAME) == 0) hasAndroidCreate = true;
    if (std::strcmp(extension.extensionName, "XR_KHR_composition_layer_cylinder") == 0) hasCompositionLayerCylinder = true;
    if (std::strcmp(extension.extensionName, "XR_KHR_composition_layer_equirect2") == 0) hasCompositionLayerEquirect = true;
    if (std::strcmp(extension.extensionName, "XR_FB_passthrough") == 0) hasPassthrough = true;
  }
  // These four decide what the immersive player can actually offer: a video
  // surface with no GPU copy, a curved screen, 360/180 playback, and dimming
  // the room instead of blacking it out.
  LOGI("capability android_surface_swapchain=%d cylinder=%d equirect2=%d passthrough=%d",
       hasSurfaceSwapchain, hasCompositionLayerCylinder, hasCompositionLayerEquirect, hasPassthrough);

  if (!hasAndroidCreate) {
    LOGE("XR_KHR_android_create_instance missing; cannot create an instance here");
    return;
  }

  std::vector<const char*> enabled{XR_KHR_ANDROID_CREATE_INSTANCE_EXTENSION_NAME};
  if (hasSurfaceSwapchain) enabled.push_back(kSurfaceSwapchainExt);

  XrInstanceCreateInfoAndroidKHR androidInfo{XR_TYPE_INSTANCE_CREATE_INFO_ANDROID_KHR};
  androidInfo.applicationVM = vm;
  androidInfo.applicationActivity = activity;

  XrInstanceCreateInfo createInfo{XR_TYPE_INSTANCE_CREATE_INFO};
  createInfo.next = &androidInfo;
  createInfo.enabledExtensionCount = static_cast<uint32_t>(enabled.size());
  createInfo.enabledExtensionNames = enabled.data();
  std::strncpy(createInfo.applicationInfo.applicationName, "Plezy", XR_MAX_APPLICATION_NAME_SIZE - 1);
  createInfo.applicationInfo.applicationVersion = 1;
  std::strncpy(createInfo.applicationInfo.engineName, "plezy_xr", XR_MAX_ENGINE_NAME_SIZE - 1);
  createInfo.applicationInfo.apiVersion = XR_CURRENT_API_VERSION;

  XrInstance instance = XR_NULL_HANDLE;
  const XrResult created = xrCreateInstance(&createInfo, &instance);
  if (XR_FAILED(created)) {
    LOGE("xrCreateInstance failed: %d", created);
    return;
  }

  XrInstanceProperties instanceProperties{XR_TYPE_INSTANCE_PROPERTIES};
  if (XR_SUCCEEDED(xrGetInstanceProperties(instance, &instanceProperties))) {
    LOGI("runtime '%s' version %u.%u.%u", instanceProperties.runtimeName,
         XR_VERSION_MAJOR(instanceProperties.runtimeVersion), XR_VERSION_MINOR(instanceProperties.runtimeVersion),
         XR_VERSION_PATCH(instanceProperties.runtimeVersion));
  }

  XrSystemGetInfo systemInfo{XR_TYPE_SYSTEM_GET_INFO};
  systemInfo.formFactor = XR_FORM_FACTOR_HEAD_MOUNTED_DISPLAY;
  XrSystemId systemId = XR_NULL_SYSTEM_ID;
  const XrResult gotSystem = xrGetSystem(instance, &systemInfo, &systemId);
  if (XR_SUCCEEDED(gotSystem)) {
    XrSystemProperties systemProperties{XR_TYPE_SYSTEM_PROPERTIES};
    if (XR_SUCCEEDED(xrGetSystemProperties(instance, systemId, &systemProperties))) {
      LOGI("system '%s' maxLayers=%u maxSwapchain=%ux%u", systemProperties.systemName,
           systemProperties.graphicsProperties.maxLayerCount,
           systemProperties.graphicsProperties.maxSwapchainImageWidth,
           systemProperties.graphicsProperties.maxSwapchainImageHeight);
    }
  } else {
    // Expected when the probe runs outside an immersive activity: the headset
    // will not hand out a system to a flat panel app.
    LOGE("xrGetSystem failed: %d", gotSystem);
  }

  xrDestroyInstance(instance);
  LOGI("probe complete");
}
