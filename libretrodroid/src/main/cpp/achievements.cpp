/*
 *     RetroAchievements for LibretroDroid, using rcheevos' rc_client.
 *     Part of RomM TV (GPL-3.0). rcheevos is MIT licensed.
 *
 *     Java does the HTTP (RetroAchievements.kt); rc_client does everything else. Achievements are
 *     checked once per emulated frame on the emulation thread, reading the core's memory through
 *     rcheevos' libretro memory helpers. Always softcore: an app RetroAchievements hasn't reviewed
 *     only earns softcore unlocks anyway.
 */

#include "achievements.h"

#include <cstdio>
#include <cstring>
#include <unordered_map>

#include "core.h"
#include "log.h"

#include "rc_client.h"
#include "rc_api_request.h"
#include "rcheevos/src/rc_libretro.h"

namespace libretrodroid {

namespace {

struct PendingCall {
    rc_client_server_callback_t callback;
    void* callbackData;
    unsigned generation;
};

std::mutex pendingLock;
std::unordered_map<jlong, PendingCall> pendingCalls;
jlong nextHandle = 1;

std::string fromJava(JNIEnv* env, jstring value) {
    if (value == nullptr) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    std::string result(chars ? chars : "");
    if (chars) env->ReleaseStringUTFChars(value, chars);
    return result;
}

jstring toJava(JNIEnv* env, const char* value) {
    return value ? env->NewStringUTF(value) : nullptr;
}

} // namespace

JNIEnv* Achievements::env() {
    if (javaVM == nullptr) return nullptr;
    JNIEnv* result = nullptr;
    jint status = javaVM->GetEnv(reinterpret_cast<void**>(&result), JNI_VERSION_1_6);
    if (status == JNI_EDETACHED) {
        if (javaVM->AttachCurrentThread(&result, nullptr) != JNI_OK) return nullptr;
    }
    return result;
}

void Achievements::registerBridge(JNIEnv* jniEnv, jclass bridge) {
    std::lock_guard<std::recursive_mutex> guard(lock);
    if (bridgeClass != nullptr) return;
    bridgeClass = static_cast<jclass>(jniEnv->NewGlobalRef(bridge));
    onServerCallMethod = jniEnv->GetStaticMethodID(bridge, "serverCall",
        "(JLjava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
    onMessageMethod = jniEnv->GetStaticMethodID(bridge, "onMessage",
        "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;ILjava/lang/String;)V");
    if (jniEnv->ExceptionCheck()) jniEnv->ExceptionClear();
}

static void sendMessage(JNIEnv* env, jclass bridge, jmethodID method, const char* kind, const char* title,
                        const char* description, int points, const char* imageUrl) {
    if (env == nullptr || bridge == nullptr || method == nullptr) return;
    jstring jKind = toJava(env, kind);
    jstring jTitle = toJava(env, title);
    jstring jDescription = toJava(env, description);
    jstring jImage = toJava(env, imageUrl);
    env->CallStaticVoidMethod(bridge, method, jKind, jTitle, jDescription, (jint) points, jImage);
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (jKind) env->DeleteLocalRef(jKind);
    if (jTitle) env->DeleteLocalRef(jTitle);
    if (jDescription) env->DeleteLocalRef(jDescription);
    if (jImage) env->DeleteLocalRef(jImage);
}

void Achievements::start(JNIEnv* jniEnv, const std::string& userAgent, const std::string& username,
                         const std::string& token, int requestedConsoleId, const std::string& path) {
    std::lock_guard<std::recursive_mutex> guard(lock);
    stop();

    consoleId = requestedConsoleId;
    gamePath = path;
    reportedProcessing = false;
    generation++;

    client = rc_client_create(readMemory, serverCall);
    if (client == nullptr) return;

    char clause[64] = {0};
    rc_client_get_user_agent_clause(client, clause, sizeof(clause));
    agentClause = clause;

    rc_client_enable_logging(client, RC_CLIENT_LOG_LEVEL_INFO, logMessage);
    rc_client_set_event_handler(client, eventHandler);
    // Memory is only read inside rc_client_do_frame, on the emulation thread
    rc_client_set_allow_background_memory_reads(client, 0);
    rc_client_set_hardcore_enabled(client, 0);

    rc_client_begin_login_with_token(client, username.c_str(), token.c_str(), loginCallback, nullptr);
}

void Achievements::stop() {
    std::lock_guard<std::recursive_mutex> guard(lock);
    generation++;
    if (client != nullptr) {
        rc_client_destroy(client);
        client = nullptr;
    }
    if (regions != nullptr) {
        auto* r = static_cast<rc_libretro_memory_regions_t*>(regions);
        rc_libretro_memory_destroy(r);
        delete r;
        regions = nullptr;
    }
    regionsReady = false;
    currentCore = nullptr;
}

void Achievements::clearMemoryMap() {
    std::lock_guard<std::recursive_mutex> guard(lock);
    hasMemoryMap = false;
    memoryDescriptors.clear();
    memoryMap = {};
}

void Achievements::setMemoryMap(const struct retro_memory_map* map) {
    std::lock_guard<std::recursive_mutex> guard(lock);
    memoryDescriptors.clear();
    hasMemoryMap = false;
    if (map == nullptr || map->descriptors == nullptr || map->num_descriptors == 0) return;
    memoryDescriptors.assign(map->descriptors, map->descriptors + map->num_descriptors);
    memoryMap.descriptors = memoryDescriptors.data();
    memoryMap.num_descriptors = (unsigned) memoryDescriptors.size();
    hasMemoryMap = true;
    // A new map means the layout changed: build the regions again on the next frame
    regionsReady = false;
}

void Achievements::getCoreMemoryInfo(uint32_t id, rc_libretro_core_memory_info_t* info) {
    Core* core = getInstance().currentCore;
    if (core == nullptr || core->retro_get_memory_data == nullptr || core->retro_get_memory_size == nullptr) {
        info->data = nullptr;
        info->size = 0;
        return;
    }
    info->data = static_cast<uint8_t*>(core->retro_get_memory_data(id));
    info->size = core->retro_get_memory_size(id);
}

void Achievements::initRegionsIfNeeded(Core* core) {
    if (regionsReady) return;
    currentCore = core;
    if (regions == nullptr) {
        regions = new rc_libretro_memory_regions_t();
    } else {
        rc_libretro_memory_destroy(static_cast<rc_libretro_memory_regions_t*>(regions));
    }
    auto* r = static_cast<rc_libretro_memory_regions_t*>(regions);
    std::memset(r, 0, sizeof(*r));
    const int ok = rc_libretro_memory_init(r, hasMemoryMap ? &memoryMap : nullptr, getCoreMemoryInfo, (uint32_t) consoleId);
    regionsReady = true;
    if (!ok || r->total_size == 0) {
        LOGE("RetroAchievements: this emulator core doesn't expose the game's memory");
        sendMessage(env(), bridgeClass, onMessageMethod, "no_memory", nullptr, nullptr, 0, nullptr);
        r->count = 0;
        r->total_size = 0;
    } else {
        char line[96];
        snprintf(line, sizeof(line), "%u memory regions, %s", (unsigned) r->count,
                 hasMemoryMap ? "from the core's memory map" : "from the core's system RAM");
        sendMessage(env(), bridgeClass, onMessageMethod, "memory", nullptr, line, (int) r->total_size, nullptr);
    }
}

uint32_t Achievements::readMemory(uint32_t address, uint8_t* buffer, uint32_t numBytes, rc_client_t*) {
    auto& self = getInstance();
    if (!self.regionsReady || self.regions == nullptr) return 0;
    auto* r = static_cast<rc_libretro_memory_regions_t*>(self.regions);
    if (r->total_size == 0) return 0;
    return rc_libretro_memory_read(r, address, buffer, numBytes);
}

void Achievements::doFrame(Core* core) {
    std::lock_guard<std::recursive_mutex> guard(lock);
    if (client == nullptr) return;
    // rc_client finishes loading a game inside rc_client_do_frame (it checks the achievements' memory
    // addresses there, on this thread), so do_frame has to run before the game counts as loaded.
    // The core is already running the game here, so its memory can be mapped now.
    initRegionsIfNeeded(core);
    currentCore = core;
    rc_client_do_frame(client);
    if (!reportedProcessing && rc_client_is_game_loaded(client)) {
        reportedProcessing = true;
        sendMessage(env(), bridgeClass, onMessageMethod, "processing", nullptr, nullptr, 0, nullptr);
    }
}

void Achievements::idle() {
    std::lock_guard<std::recursive_mutex> guard(lock);
    if (client != nullptr) rc_client_idle(client);
}

void Achievements::onReset() {
    std::lock_guard<std::recursive_mutex> guard(lock);
    if (client != nullptr) rc_client_reset(client);
}

void Achievements::onStateLoaded() {
    std::lock_guard<std::recursive_mutex> guard(lock);
    // Save states don't carry achievement progress here, so start the checks over from the loaded state
    if (client != nullptr) rc_client_deserialize_progress_sized(client, nullptr, 0);
}

// ---- HTTP, done by Java

void Achievements::serverCall(const rc_api_request_t* request, rc_client_server_callback_t callback,
                              void* callbackData, rc_client_t*) {
    auto& self = getInstance();
    JNIEnv* jniEnv = self.env();

    jlong handle;
    {
        std::lock_guard<std::mutex> guard(pendingLock);
        handle = nextHandle++;
        pendingCalls[handle] = PendingCall { callback, callbackData, self.generation };
    }

    if (jniEnv == nullptr || self.bridgeClass == nullptr || self.onServerCallMethod == nullptr) {
        rc_api_server_response_t response {};
        response.http_status_code = RC_API_SERVER_RESPONSE_RETRYABLE_CLIENT_ERROR;
        {
            std::lock_guard<std::mutex> guard(pendingLock);
            pendingCalls.erase(handle);
        }
        callback(&response, callbackData);
        return;
    }

    jstring url = toJava(jniEnv, request->url);
    jstring postData = toJava(jniEnv, request->post_data);
    jstring contentType = toJava(jniEnv, request->content_type);
    jniEnv->CallStaticVoidMethod(self.bridgeClass, self.onServerCallMethod, handle, url, postData, contentType);
    if (jniEnv->ExceptionCheck()) jniEnv->ExceptionClear();
    if (url) jniEnv->DeleteLocalRef(url);
    if (postData) jniEnv->DeleteLocalRef(postData);
    if (contentType) jniEnv->DeleteLocalRef(contentType);
}

void Achievements::onServerResponse(JNIEnv* jniEnv, jlong handle, jbyteArray body, jint status) {
    PendingCall call {};
    {
        std::lock_guard<std::mutex> guard(pendingLock);
        auto it = pendingCalls.find(handle);
        if (it == pendingCalls.end()) return;
        call = it->second;
        pendingCalls.erase(it);
    }

    std::string data;
    if (body != nullptr) {
        jsize length = jniEnv->GetArrayLength(body);
        data.resize((size_t) length);
        if (length > 0) jniEnv->GetByteArrayRegion(body, 0, length, reinterpret_cast<jbyte*>(&data[0]));
    }

    std::lock_guard<std::recursive_mutex> guard(lock);
    // The game was closed (or another started) while this request was out: nothing is waiting for it
    if (client == nullptr || call.generation != generation) return;

    rc_api_server_response_t response {};
    response.body = body != nullptr ? data.c_str() : nullptr;
    response.body_length = body != nullptr ? data.size() : 0;
    response.http_status_code = status;
    call.callback(&response, call.callbackData);
}

// ---- rc_client callbacks

void Achievements::logMessage(const char* message, const rc_client_t*) {
    LOGI("RetroAchievements: %s", message);
    auto& self = getInstance();
    sendMessage(self.env(), self.bridgeClass, self.onMessageMethod, "log", nullptr, message, 0, nullptr);
}

void Achievements::loginCallback(int result, const char* errorMessage, rc_client_t* client, void*) {
    auto& self = getInstance();
    if (result != RC_OK) {
        sendMessage(self.env(), self.bridgeClass, self.onMessageMethod, "login_failed", nullptr,
                    errorMessage ? errorMessage : "Couldn't sign in", 0, nullptr);
        return;
    }
    sendMessage(self.env(), self.bridgeClass, self.onMessageMethod, "login_ok", rc_client_get_user_info(client) ?
                rc_client_get_user_info(client)->display_name : nullptr, nullptr, 0, nullptr);
    rc_client_begin_identify_and_load_game(client, (uint32_t) self.consoleId, self.gamePath.c_str(), nullptr, 0,
                                           loadGameCallback, nullptr);
}

void Achievements::loadGameCallback(int result, const char* errorMessage, rc_client_t* client, void*) {
    auto& self = getInstance();
    if (result != RC_OK) {
        const char* kind = result == RC_NO_GAME_LOADED ? "unknown_game" : "load_failed";
        sendMessage(self.env(), self.bridgeClass, self.onMessageMethod, kind, nullptr, errorMessage, 0, nullptr);
        return;
    }
    const rc_client_game_t* game = rc_client_get_game_info(client);
    if (game != nullptr) {
        sendMessage(self.env(), self.bridgeClass, self.onMessageMethod, "game_info", game->hash, nullptr, (int) game->id, nullptr);
    }
    rc_client_user_game_summary_t summary {};
    rc_client_get_user_game_summary(client, &summary);

    char line[96];
    snprintf(line, sizeof(line), "%u of %u achievements unlocked", summary.num_unlocked_achievements,
             summary.num_core_achievements);
    sendMessage(self.env(), self.bridgeClass, self.onMessageMethod, "game_loaded", game ? game->title : nullptr,
                line, (int) summary.num_core_achievements, game ? game->badge_url : nullptr);
}

void Achievements::eventHandler(const rc_client_event_t* event, rc_client_t* client) {
    auto& self = getInstance();
    switch (event->type) {
        case RC_CLIENT_EVENT_ACHIEVEMENT_TRIGGERED: {
            const rc_client_achievement_t* a = event->achievement;
            sendMessage(self.env(), self.bridgeClass, self.onMessageMethod, "unlocked", a->title, a->description,
                        (int) a->points, a->badge_url);
            break;
        }
        case RC_CLIENT_EVENT_GAME_COMPLETED: {
            const rc_client_game_t* game = rc_client_get_game_info(client);
            sendMessage(self.env(), self.bridgeClass, self.onMessageMethod, "mastered", game ? game->title : nullptr,
                        nullptr, 0, game ? game->badge_url : nullptr);
            break;
        }
        case RC_CLIENT_EVENT_RESET:
            rc_client_reset(client);
            break;
        case RC_CLIENT_EVENT_SERVER_ERROR:
            if (event->server_error != nullptr) {
                LOGE("RetroAchievements server error: %s", event->server_error->error_message);
            }
            break;
        case RC_CLIENT_EVENT_DISCONNECTED:
            sendMessage(self.env(), self.bridgeClass, self.onMessageMethod, "disconnected", nullptr, nullptr, 0, nullptr);
            break;
        case RC_CLIENT_EVENT_RECONNECTED:
            sendMessage(self.env(), self.bridgeClass, self.onMessageMethod, "reconnected", nullptr, nullptr, 0, nullptr);
            break;
        default:
            break;
    }
}

} // namespace libretrodroid

// ---- JNI entry points (com.swordfish.libretrodroid.RetroAchievements)

using libretrodroid::Achievements;

extern "C" {

JNIEXPORT jstring JNICALL Java_com_swordfish_libretrodroid_RetroAchievements_nativeStart(
    JNIEnv* env, jclass clazz, jstring userAgent, jstring username, jstring token, jint consoleId, jstring gamePath) {
    auto& self = Achievements::getInstance();
    JavaVM* vm = nullptr;
    env->GetJavaVM(&vm);
    self.setJavaVM(vm);
    self.registerBridge(env, clazz);
    if (!self.bridgeReady()) return env->NewStringUTF("BRIDGE_FAIL");
    self.start(env, libretrodroid::fromJava(env, userAgent), libretrodroid::fromJava(env, username),
               libretrodroid::fromJava(env, token), consoleId, libretrodroid::fromJava(env, gamePath));
    return env->NewStringUTF(self.userAgentClause().c_str());
}

JNIEXPORT void JNICALL Java_com_swordfish_libretrodroid_RetroAchievements_nativeStop(JNIEnv*, jclass) {
    Achievements::getInstance().stop();
}

JNIEXPORT void JNICALL Java_com_swordfish_libretrodroid_RetroAchievements_nativeServerResponse(
    JNIEnv* env, jclass, jlong handle, jbyteArray body, jint status) {
    Achievements::getInstance().onServerResponse(env, handle, body, status);
}

}
