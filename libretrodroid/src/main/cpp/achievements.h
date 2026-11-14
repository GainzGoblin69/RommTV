/*
 *     RetroAchievements for LibretroDroid, using rcheevos' rc_client.
 *     Part of RomM TV (GPL-3.0). rcheevos is MIT licensed.
 */

#ifndef LIBRETRODROID_ACHIEVEMENTS_H
#define LIBRETRODROID_ACHIEVEMENTS_H

#include <jni.h>

#include <mutex>
#include <string>
#include <vector>

#include "../../libretro-common/include/libretro.h"

// rcheevos types at global scope (forward-declaring them inside the class would put them in our namespace)
#include "rc_client.h"
#include "rc_api_request.h"
#include "rcheevos/src/rc_libretro.h"

namespace libretrodroid {

class Core;

class Achievements {
public:
    static Achievements& getInstance() {
        static Achievements instance;
        return instance;
    }
    Achievements(Achievements const&) = delete;
    void operator=(Achievements const&) = delete;

    /** Called from Java once the game has been handed to the core. */
    void start(JNIEnv* env, const std::string& userAgent, const std::string& username, const std::string& token,
               int consoleId, const std::string& gamePath);
    void stop();
    /** Forgets the core's memory map; called when the game is closed. */
    void clearMemoryMap();

    void registerBridge(JNIEnv* env, jclass bridge);
    std::string userAgentClause() const { return agentClause; }

    /** Called on the emulation thread after every retro_run(). */
    void doFrame(Core* core);
    /** Called on the emulation thread now and then while paused, to keep the session alive. */
    void idle();
    void onReset();
    void onStateLoaded();

    /** The core's memory map (RETRO_ENVIRONMENT_SET_MEMORY_MAPS). */
    void setMemoryMap(const struct retro_memory_map* map);

    /** An HTTP answer from Java for a request rc_client made. */
    void onServerResponse(JNIEnv* env, jlong handle, jbyteArray body, jint status);

    void setJavaVM(JavaVM* vm) { javaVM = vm; }

private:
    Achievements() = default;

    static uint32_t readMemory(uint32_t address, uint8_t* buffer, uint32_t numBytes, rc_client_t* client);
    static void serverCall(const rc_api_request_t* request, rc_client_server_callback_t callback,
                           void* callbackData, rc_client_t* client);
    static void eventHandler(const rc_client_event_t* event, rc_client_t* client);
    static void logMessage(const char* message, const rc_client_t* client);
    static void loginCallback(int result, const char* errorMessage, rc_client_t* client, void* userdata);
    static void loadGameCallback(int result, const char* errorMessage, rc_client_t* client, void* userdata);
    static void getCoreMemoryInfo(uint32_t id, rc_libretro_core_memory_info_t* info);

    JNIEnv* env();
    void initRegionsIfNeeded(Core* core);

    JavaVM* javaVM = nullptr;
    jclass bridgeClass = nullptr;
    jmethodID onServerCallMethod = nullptr;
    jmethodID onMessageMethod = nullptr;

    std::recursive_mutex lock;
    rc_client_t* client = nullptr;
    unsigned generation = 0;
    int consoleId = 0;
    std::string gamePath;
    std::string agentClause;

    bool regionsReady = false;
    bool reportedProcessing = false;
    void* regions = nullptr;
    Core* currentCore = nullptr;

    bool hasMemoryMap = false;
    std::vector<struct retro_memory_descriptor> memoryDescriptors;
    struct retro_memory_map memoryMap {};
};

} // namespace libretrodroid

#endif // LIBRETRODROID_ACHIEVEMENTS_H
